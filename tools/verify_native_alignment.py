#!/usr/bin/env python3
"""Check that every native library in the APK can load on a 16 KB page size device.

Two separate requirements make an APK 16 KB ready, and this checks both per library:

  1. *ELF alignment* — each `PT_LOAD` segment's `p_align` must be at least 16 KB
     (`0x4000`). A library built for 4 KB pages carries `p_align = 0x1000`, and the dynamic
     linker on a 16 KB device has to map its segments at addresses that are not multiples of
     the device's page size, which it cannot do: the library fails to load.
  2. *APK alignment* — the file must be stored uncompressed with its data at a 16 KB
     boundary. AGP does this automatically for uncompressed `.so` entries, so this half is
     normally a regression guard rather than a fix.

Requirement 1 is a property of the dependency's own build flags (`-Wl,-z,max-page-size=16384`,
i.e. NDK r27+ or r28 with no extra flags) and cannot be changed from this project. The two
QuickJS libraries are listed below as known-bad with the reason, so the check fails the
moment a *new* unaligned library appears while the one upstream problem stays visible in the
output instead of being silently tolerated.

    python tools/verify_native_alignment.py app/build/outputs/apk/optimized/app-optimized.apk
"""

import struct
import sys
import zipfile

# Libraries shipped 4 KB-aligned that this project cannot re-align from here. Each entry is a
# decision someone made, not an absence of information: removing one is what makes a build
# that has fixed them pass with no change to this check.
KNOWN_UNALIGNED = {
    "lib/arm64-v8a/libquickjs-android.so": (
        "io.github.taoweiji.quickjs:quickjs-android:1.4.6 — the newest release on Maven "
        "Central builds with the default max-page-size, so the balance-query JS path is "
        "expected to fail on a 16 KB device until upstream ships an r28 build or this "
        "dependency is replaced"
    ),
    "lib/arm64-v8a/libquickjs.so": (
        "same dependency and same cause as libquickjs-android.so"
    ),
}

PAGE_16K = 16 * 1024


def elf_load_alignments(data):
    """The `p_align` of every `PT_LOAD` segment, for a 32- or 64-bit little/big-endian ELF."""
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    elf_class, data_encoding = data[4], data[5]
    endian = "<" if data_encoding == 1 else ">"
    is64 = elf_class == 2
    if is64:
        program_offset, = struct.unpack_from(endian + "Q", data, 0x20)
        entry_size, entry_count = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        program_offset, = struct.unpack_from(endian + "I", data, 0x1C)
        entry_size, entry_count = struct.unpack_from(endian + "HH", data, 0x2A)
    alignments = []
    for index in range(entry_count):
        base = program_offset + index * entry_size
        segment_type, = struct.unpack_from(endian + "I", data, base)
        if segment_type != 1:  # PT_LOAD
            continue
        align_offset = base + (0x30 if is64 else 0x1C)
        align, = struct.unpack_from(endian + ("Q" if is64 else "I"), data, align_offset)
        alignments.append(align)
    return alignments


def data_offset(apk_path, info):
    """Where an entry's bytes start, which is what has to be page aligned."""
    if zipfile.ZipFile(apk_path).getinfo(info.filename).compress_type != zipfile.ZIP_STORED:
        return None
    with open(apk_path, "rb") as handle:
        handle.seek(info.header_offset)
        header = handle.read(30)
        name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_length + extra_length


def check(path):
    """Raise SystemExit when [path] holds a native library a 16 KB device cannot load."""
    archive = zipfile.ZipFile(path)
    libraries = [info for info in archive.infolist() if info.filename.endswith(".so")]
    if not libraries:
        raise SystemExit(f"{path} 里没有原生库；若这是刻意的，这个检查应当从发布流程里去掉")

    failures = []
    for info in libraries:
        data = archive.read(info.filename)
        try:
            alignments = elf_load_alignments(data)
        except ValueError as error:
            failures.append(f"{info.filename}: 读不出 ELF 头（{error}）")
            continue
        worst = min(alignments) if alignments else 0
        offset = data_offset(path, info)
        offset_ok = offset is not None and offset % PAGE_16K == 0
        elf_ok = worst >= PAGE_16K

        if elf_ok and offset_ok:
            print(f"OK    {info.filename}: p_align 0x{worst:x}，数据偏移 {offset}（16 KB 对齐）")
            continue
        if not elf_ok:
            known = KNOWN_UNALIGNED.get(info.filename)
            if known:
                print(f"KNOWN {info.filename}: p_align 0x{worst:x} < 0x4000 —— {known}")
                continue
            failures.append(
                f"{info.filename}: ELF p_align 只有 0x{worst:x}，16 KB 设备上加载不了这个库"
            )
        if not offset_ok:
            stored = "未压缩" if offset is not None else "被压缩存放"
            failures.append(
                f"{info.filename}: {stored}，数据偏移 {offset} 不是 16 KB 的整数倍"
            )

    if failures:
        print()
        for line in failures:
            print("FAIL  " + line)
        raise SystemExit(
            "有原生库不满足 16 KB 页要求：把依赖升到用 NDK r27+（-Wl,-z,max-page-size=16384）"
            "构建的版本，或按上面的名字登记为已知例外。"
        )
    known_count = sum(1 for info in libraries if info.filename in KNOWN_UNALIGNED)
    print(
        f"native: {len(libraries)} 个原生库检查完毕"
        + (f"，其中 {known_count} 个是已登记的已知例外（见 KNOWN_UNALIGNED）" if known_count else "")
    )


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else (
        "app/build/outputs/apk/optimized/app-optimized.apk"
    )
    check(path)
    print("\n16 KB 页要求：全部原生库检查完毕")
    return 0


if __name__ == "__main__":
    sys.exit(main())
