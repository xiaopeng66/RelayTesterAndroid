"""Verify a release APK embeds the final wording and none of the retired wording.

The Android build is not byte-reproducible (same source rebuilt gives the same size
but a different hash), so artifact-to-source consistency is checked by reading the
strings out of the shipped dex rather than by comparing hashes.

    python tools/verify_apk_wording.py app/build/outputs/apk/optimized/app-optimized.apk
"""

import re
import sys
import zipfile

# The app's user-visible wording, both directions: the final wording must be present in
# the APK and the retired wording must be gone. Both are checked so a half-applied edit
# or a stale build cannot pass.
FINAL = [
    "暂无模型；可在「模型测试」拉取，或直接输入模型名。",
    "明文 HTTP：密钥与内容不加密，仅限本机/内网。",
    "供应商配置",
    "保存供应商",
    "并行发送三题",
    "查看支持的模型",
    "检查更新",
    # The two-column package row's right half. It is also a substring of two error
    # sentences that stay ("无法删除检测包备份"), so this proves the wording exists rather
    # than which widget prints it; that the button is the row's right half is measured on
    # the device.
    "删除检测包",
    "排队中",
    "接收中",
    " · 需要 ",
    "收起候选",
    "展开候选",
    "检测包支持的模型",
    "没有可以打开这个链接的应用",
    "筛选模型",
    "清空筛选",
    "选择模型",
    # The detection-package card's actionable sentence. Two causes print it (a package
    # format too new to read, and a manifest whose minAppVersionCode is above this app's),
    # so both halves are pinned: the form that carries the version code, and the bare one.
    "线上检测包需要更新版本的 App 才能使用（需要版本代码 ≥ ",
    "线上检测包需要更新版本的 App 才能使用。",
    # The update surface: the header entry (both states of its description, since the dot
    # is the only part that changes), the dialog's title, and the two switches. The launch
    # check's own sentence is gone (the switch now fires at launch and every six hours
    # while the app stays open), so no wording for it survives here.
    "关于与更新",
    "关于与更新，有新版本",
    "去更新软件",
    "自动检查软件更新",
    "自动检查检测包更新",
    # The dialog's one outbound link, added this round.
    "github.com/xiaopeng66/RelayTesterAndroid",
    # 更新说明这一块：说明文本随清单下发、在卡片里内嵌显示，标题与「在浏览器打开」是它新加的
    # 两条可见文案（清单里的说明正文是数据，不是本 APK 的文案，登记在这里没有意义）。
    "更新说明",
    "在浏览器打开",
    # 10702：供应商排序面板的标题、它那句用法说明，以及「选择模型」里手输模型名的短提示
    # （原来那句太长，被省略号截断，看不见后半）。
    "供应商排序",
    "按住右侧手柄拖动，或用箭头上下移；顺序在所有页面一致。",
    "不在列表，直接检测",
]

# Only strings that this round retired everywhere. A word that still has a legitimate
# use elsewhere (「先拉取模型列表，再添加来源」「禁止测试：只拉模型…」) does not belong
# here: the check must not fail on wording that is still correct. The same rule keeps
# 「 · 需要 」 in FINAL rather than here — the manual card still prints it.
#
# A label that no longer exists on its own but survives inside a sentence that stays is
# in neither list: the old package-row button read "删除已安装的检测包", and that string is
# still a substring of "无法删除已安装的检测包", which is a different message the app must
# keep printing. Substring matching cannot tell the two apart, so the row itself is
# verified on the device instead of pretended here.
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
    # 10505 answered an unreadable package format with a format number and no way forward
    # (「更新清单的格式版本 3 不受支持」). It now says which app version to reach instead,
    # so the technical sentence must not come back.
    "更新清单的格式版本",
    # 10600: the panel and the dialog were cut back to the wording that changes. Each entry
    # below was deleted from every source file, which is what makes it safe to demand its
    # absence — checked with a repository-wide grep before it was added, not assumed.
    "让模型凭第一反应写约 300 个 1–355 整数，再与检测包比对。",
    "多模型仍逐个检测",
    "每个模型一组三栏",
    "开跑后每个模型各占一组三栏",
    "还没有记录；每测完一个模型就留一条。",
    "需先下载一次检测包；之后检测完全离线。",
    "检测离线；取清单与装包才联网。",
    "打开本面板时自动检查一次",
    "打开 App 时自动检查，最多每 6 小时一次",
    "两个更新渠道各自独立，可分别关闭自动检查。",
    "自动检查检测包更新的开关在「指纹检测」面板的检测包卡片里。",
    "多个词用英文半角逗号 , 表示 OR",
    "拉取所有供应商的模型并按关键词筛选",
    "并发/限速/重试",
    "数据来自站点 API，不是本地估算",
    "最近一次 API 查询结果",
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


def verify(path, verbose=True):
    """Raise SystemExit when [path] does not match the wording invariants above."""
    haystack = strings_from_apk(path)
    joined = next(iter(h for h in haystack if len(h) > 10000), "")
    missing = [t for t in FINAL if t not in haystack and t not in joined]
    present_retired = [t for t in RETIRED if t in haystack or t in joined]
    if verbose:
        for text in FINAL:
            print(("OK   " if text not in missing else "MISS ") + text)
        for text in RETIRED:
            print(("GONE " if text not in present_retired else "STILL") + " (retired) " + text)
    if missing:
        raise SystemExit("APK 缺少最终文案：%r" % missing)
    if present_retired:
        raise SystemExit("APK 仍含被替换的旧文案：%r" % present_retired)
    # One line rather than none: the publisher calls this in the middle of its own output,
    # and a gate that says nothing is indistinguishable from one that was never run.
    print("wording: 最终文案 %d 条齐备，退役文案 %d 条均不存在" % (len(FINAL), len(RETIRED)))


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else (
        "app/build/outputs/apk/optimized/app-optimized.apk"
    )
    verify(path)
    print("\nAPK wording matches the final source: %d present, %d retired absent" % (
        len(FINAL), len(RETIRED)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
