#!/usr/bin/env python3
"""Read upstream lm-detector's detection artifact, whichever form the revision ships.

Upstream publishes the detector in two forms, and both still turn up:

  * `data/shared_detector.bin` (since upstream `2d71e10c`, 2026-10-11) — a JSON header with the
    numbers moved out into a block of float32 values. Every array in the header is a
    `{"$f32": offset, "n": length}` placeholder, scalars and strings keep full precision, and
    the float block is stored **byte plane by byte plane**: all first bytes, then all second
    bytes, and so on, each float little-endian. That split is what makes the file compress
    well (the numbers are ~4/5 of it), and it is why decoding is not a plain unpack: the four
    planes have to be interleaved back into floats before any offset means anything. Layout:

        u32 header length (LE) | UTF-8 JSON header | zero padding to 4 bytes | float planes

  * `data/shared_detector.json` (before that commit) — the same header with the numbers inline.

Both produce the same dict, so `build_fingerprint_asset.py` does not care which one it was
handed: a pinned old revision and a fresh clone build the same package.

This mirrors upstream's own `shared/detector-codec.ts` (`decodeDetector`); the expansion of the
`$f32` placeholders is the same reviver, and it drops every other key of a placeholder object
exactly as upstream does.
"""

import json
import struct
from pathlib import Path

#: Newest first. The first one that exists wins, so the same code reads a fresh clone and a
#: revision from before the switch.
DETECTOR_FILES = ("shared_detector.bin", "shared_detector.json")
#: The reference bank the detector is bound to; its own format has not changed.
BANK_FILE = "unified_bank.json"


def decode_detector(blob):
    """Expand the binary artifact [blob] into the dict its header describes."""
    if len(blob) < 4:
        raise SystemExit("shared_detector.bin 太短：连 4 字节的头部长度都没有")
    header_length = struct.unpack_from("<I", blob, 0)[0]
    start = -(-(4 + header_length) // 4) * 4
    if start > len(blob):
        raise SystemExit(
            f"shared_detector.bin 头部长度 {header_length} 超出文件大小 {len(blob)}",
        )
    payload = blob[start:]
    if len(payload) % 4:
        raise SystemExit(f"shared_detector.bin 的浮点区不是 4 的整数倍：{len(payload)} 字节")

    count = len(payload) // 4
    # Byte planes back into floats. The strided assignment is what keeps this off a Python
    # loop over ~1.1M values: plane b holds byte b of every float, in order.
    raw = bytearray(count * 4)
    view = memoryview(raw)
    for plane in range(4):
        view[plane::4] = payload[plane * count:(plane + 1) * count]
    floats = struct.unpack(f"<{count}f", raw)

    header = blob[4:4 + header_length].decode("utf-8")
    return json.loads(header, object_hook=lambda node: _expand(node, floats))


def _expand(node, floats):
    """One `{"$f32": offset, "n": length}` placeholder -> its slice of [floats]."""
    if "$f32" not in node:
        return node
    offset = int(node["$f32"])
    length = int(node["n"])
    if offset < 0 or length < 0 or offset + length > len(floats):
        raise SystemExit(f"shared_detector.bin 的 $f32 区间越界：offset={offset} n={length}")
    return list(floats[offset:offset + length])


def find_detector(data_dir):
    """The detector artifact in [data_dir], or None when it holds neither name."""
    for name in DETECTOR_FILES:
        candidate = Path(data_dir) / name
        if candidate.is_file():
            return candidate
    return None


def read_detector(data_dir):
    """Load the detector from [data_dir]. Returns (dict, the file name it came from)."""
    path = find_detector(data_dir)
    if path is None:
        raise SystemExit(
            f"{data_dir} 里既没有 {DETECTOR_FILES[0]} 也没有 {DETECTOR_FILES[1]}："
            "先下载上游 data/ 目录下的检测器，或用 --data-dir 指向上游 clone",
        )
    if path.suffix == ".json":
        return json.loads(path.read_text(encoding="utf-8")), path.name
    return decode_detector(path.read_bytes()), path.name
