"""Verify a release APK embeds the final wording and none of the retired wording.

The Android build is not byte-reproducible (same source rebuilt gives the same size
but a different hash), so artifact-to-source consistency is checked by reading the
strings out of the shipped dex rather than by comparing hashes.

    python tools/verify_apk_wording.py app/build/outputs/apk/optimized/app-optimized.apk
"""

import re
import sys
import zipfile

# Pairs taken from docs/UI_TEXT_TRIMMING_PLAN.md: the final wording must be present and
# the retired wording must be gone. Both directions are checked so a half-applied edit
# or a stale build cannot pass.
FINAL = [
    "检测离线；取清单与装包才联网。",
    "让模型凭第一反应写约 300 个 1–355 整数，再与检测包比对。",
    "暂无模型；可在「模型测试」拉取，或直接输入模型名。",
    "明文 HTTP：密钥与内容不加密，仅限本机/内网。",
    "供应商配置",
    "保存供应商",
    "并行发送三题",
    "查看支持的模型",
    "检查更新",
    "删除已安装的检测包",
    "排队中",
    "接收中",
    "每个模型一组三栏",
    "开跑后每个模型各占一组三栏",
    " · 需要 ",
    "收起候选",
    "展开候选",
    "检测包支持的模型",
    "没有可以打开这个链接的应用",
    "筛选模型",
    "清空筛选",
    "选择模型",
    # 10505: the update manifest gained a format gate, so a manifest whose package format
    # this build cannot score is refused with this exact sentence.
    "更新清单的格式版本",
]

# Only strings that this round retired everywhere. A word that still has a legitimate
# use elsewhere (「先拉取模型列表，再添加来源」「禁止测试：只拉模型…」) does not belong
# here: the check must not fail on wording that is still correct. The same rule keeps
# 「 · 需要 」 in FINAL rather than here — the manual card still prints it.
RETIRED = [
    "该供应商还没有已拉取的模型",
    "仅建议用于本机或内网自建站点",
    "延迟和用量",
    "App 版本代码",
    "原始额度",
    "继续会替换当前全部配置",
    # 10504: the retry affordance moved into each question column, so the per-question
    # button and the model chooser it opened are gone from the panel.
    "重试本题",
    "选择要重跑这一题的模型：",
    "全部重试（",
    # 10505: upstream dropped its verifier scorer, so the caveat it fed is gone with it.
    # Nothing else in the app prints it, so it must not survive in a build.
    "排名与核验的第一候选不一致，请谨慎。",
]


def strings_from_apk(path):
    """Every printable string in the APK's dex files, plus resource XML as utf-8 text."""
    chunks = []
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.endswith(".dex") or name.endswith(".xml"):
                chunks.append(archive.read(name))
    blob = b"".join(chunks)
    ascii_words = {m.group(0).decode("ascii") for m in re.finditer(rb"[ -~]{4,}", blob)}
    utf8_words = set()
    # UTF-16 as well: dex string data for CJK is MUTF-8, but resources can store UTF-16.
    for match in re.finditer(rb"(?:[\x20-\x7e]\x00){4,}", blob):
        try:
            utf8_words.add(match.group(0).decode("utf-16-le"))
        except UnicodeDecodeError:
            pass
    joined = blob.decode("utf-8", "ignore")
    return ascii_words | utf8_words | {joined}


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else (
        "app/build/outputs/apk/optimized/app-optimized.apk"
    )
    haystack = strings_from_apk(path)
    joined = next(iter(h for h in haystack if len(h) > 10000), "")
    missing = [t for t in FINAL if t not in haystack and t not in joined]
    present_retired = [t for t in RETIRED if t in haystack or t in joined]
    for text in FINAL:
        print(("OK   " if text not in missing else "MISS ") + text)
    for text in RETIRED:
        print(("GONE " if text not in present_retired else "STILL") + " (retired) " + text)
    if missing:
        raise SystemExit("APK 缺少最终文案：%r" % missing)
    if present_retired:
        raise SystemExit("APK 仍含被替换的旧文案：%r" % present_retired)
    print("\nAPK wording matches the final source: %d present, %d retired absent" % (
        len(FINAL), len(RETIRED)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
