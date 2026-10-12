#!/usr/bin/env python3
"""Publish the built APK as the asset of its own version's release, plus the feed the app reads.

The release line this project ships on is one version per number: a rebuild replaces
the asset and the notes in place instead of adding a patch number, so the version tag,
the release page and the APK always describe the same build.

Two releases are involved, and this script is the only thing that touches both:

    v<version>   the APK (RelayTester-v<version>-android.apk) and the release notes
    app          app-latest.json, the manifest the app's update page polls

The manifest is derived from the APK being published — package name, version code and
minimum SDK are read out of the artifact with aapt2, its size and SHA-256 from the same
bytes — so the feed cannot describe a build other than the one that went up. The version
*code* is the field the app compares: this repository republishes under an unchanged
version name, so a tag comparison would call a 10502 install up to date.

Both assets are downloaded again afterwards and compared byte for byte.

Before anything is uploaded, local gates check the release certificate, package and source
version, required wording, native alignment (with the verifier's documented exceptions),
and notes identity. Actual publishing also compares the version code against the live feed
so a known newer version is not overwritten without an explicit rollback override.

The release notes live in the release page's body: the page *is* the record, so no copy of
them sits in the repository to drift from it. This script reads that body (or a draft given
with --notes), refuses it when the size and digest it names are not the artifact being
published, and builds the feed's plain-text copy from the same text.

Usage:
    python tools/publish_release_apk.py --notes <草稿> --dry-run   # local gates + feed preview
    python tools/publish_release_apk.py --dry-run   # APK gates only; no notes or feed preview
    python tools/publish_release_apk.py --check     # read-only online asset comparison
    python tools/publish_release_apk.py             # upload the APK and the feed

Dry runs never read credentials or contact GitHub, even with a draft. They cannot check
release existence, live version rollback or downloaded assets. --dry-run and --check are
mutually exclusive. --notes only updates the release body during an actual publish.

The version and the APK come from app/build.gradle.kts and the optimized build output.
Version mismatches stop before network access; download-back verifies uploaded bytes, not
source freshness. A same-version stale APK still needs the build/wording checks.

Token: GITHUB_TOKEN, or the first line of E:/AI/Zcode/tmp/.ghtoken.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import publish_bank  # noqa: E402 - the sibling module, on the path added just above
import retry  # noqa: E402 - shared HTTP classification and header-aware waits
import verify_apk_wording  # noqa: E402 - same, the wording invariants for the shipped APK
import verify_native_alignment  # noqa: E402 - same, the 16 KB page pairing for its .so files

REPO = "xiaopeng66/RelayTesterAndroid"
API = "https://api.github.com"
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join(PROJECT, "app/build.gradle.kts")
TOKEN_FILES = ["E:/AI/Zcode/tmp/.ghtoken"]

# The package name an APK must carry to be publishable as this app's update source. The feed
# is required to carry the name inside the artifact (see apk_identity), so a debug build would
# publish a feed that no installed app can accept — the update channel would go quiet rather
# than fail loudly.
APP_PACKAGE = "com.relaytester.app"

# The release key's certificate, as `apksigner verify --print-certs` reports it. Hard-coded on
# purpose: it is the one fact that cannot be derived from the artifact being checked, and this
# is the release tool, not the app — a key rotation is a deliberate act that updates this line
# together with the keystore.
RELEASE_CERT_SHA256 = "05c3e56705aa8c420523f999d950ce74482c42f4376ac88918db10e9451d69de"


# The app feed's own pre-release. Separate from `bank` so the daily detection-package job
# and the manual APK publish can never delete each other's assets.
APP_TAG = "app"
APP_RELEASE_NAME = "应用更新源（应用内更新清单）"
APP_ASSET = "app-latest.json"
APP_FORMAT_VERSION = 1
APP_RELEASE_BODY = """应用自身的更新清单，供 App 的「关于与更新」页读取。

- `app-latest.json`：最新版本的版本代码、版本名、安装包地址与 SHA-256，以及这份发行说明的纯文本。

App 只比较版本代码（本仓库会沿用同一个版本名重新发布，版本名不足以判断新旧）。
这里不存放安装包本体，APK 在对应的 `v<版本>` 发行版里。
"""

# 清单里 `notes` 字段的字符上限。与 App 侧 `AppUpdateManifestParser.MAX_NOTES_CHARS`
# 必须是同一个数：两边不一致时，App 会在对话框里给用户看一段截断的文字（带省略号），
# 那是防御，不该是常态。
FEED_NOTES_LIMIT = 4_000


def read_token():
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        return token.strip()
    for path in TOKEN_FILES:
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as handle:
                value = handle.readline().strip()
            if value:
                return value
    raise SystemExit("找不到 GitHub token：设置 GITHUB_TOKEN 或写入 " + TOKEN_FILES[0])


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def version():
    """The version the release line is on, read from the build file."""
    with open(GRADLE, encoding="utf-8") as handle:
        text = handle.read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not name:
        raise SystemExit("app/build.gradle.kts 里找不到 versionName")
    return name.group(1)


def request(token, method, url, data=None, content_type="application/json", raw=False):
    payload = data
    if payload is not None and not raw:
        payload = json.dumps(payload).encode("utf-8")
    request_object = urllib.request.Request(url, data=payload, method=method)
    request_object.add_header("Authorization", "Bearer " + token)
    request_object.add_header("Accept", "application/vnd.github+json")
    request_object.add_header("X-GitHub-Api-Version", "2022-11-28")
    if payload is not None:
        request_object.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request_object, timeout=300) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def download(url):
    request_object = urllib.request.Request(url)
    with urllib.request.urlopen(request_object, timeout=300) as response:
        return response.read()


def release_of(token, tag):
    status, body = request(token, "GET", f"{API}/repos/{REPO}/releases/tags/{tag}")
    if status == 404:
        return None
    if status != 200:
        raise SystemExit(f"读取 {tag} release 失败：HTTP {status} {body[:200]!r}")
    return json.loads(body)


def verify(release, name, apk_bytes):
    """Download the asset back and compare it with the file we meant to publish."""
    assets = {asset["name"]: asset for asset in release["assets"]}
    if name not in assets:
        raise SystemExit(f"发布后找不到资产 {name}")
    fetched = publish_bank.download(assets[name]["browser_download_url"], cache_bust=True)
    if fetched != apk_bytes:
        raise SystemExit(
            f"回读的 {name} 与本地文件不一致（{len(fetched)} 字节 vs {len(apk_bytes)} 字节）"
        )
    print(f"verified: {name} 回读与本地逐字节一致（{len(fetched)} 字节，{sha256(fetched)[:12]}）")


NOTES_DIGEST_RE = re.compile(r"SHA-256[：:]\s*`([0-9a-fA-F]{64})`")
NOTES_SIZE_RE = re.compile(r"字节数[：:]\s*`([\d,]+)`")


def verify_notes_identity(notes_bytes, apk_bytes, where):
    """Stop when the release notes describe bytes other than the APK being published.

    A Gradle Android build is not byte-reproducible: rebuilding the same source yields the
    same size at a different digest, so a digest written into the notes — and shown on the
    release page — goes stale on any rebuild after it was written. The notes are read before
    anything is uploaded, so this is the last point at which the two can be reconciled.

    [where] names the source in the messages ("发行版正文", or the draft's file name).
    """
    text = notes_bytes.decode("utf-8")
    actual_digest = sha256(apk_bytes)
    actual_size = len(apk_bytes)
    stated_digests = NOTES_DIGEST_RE.findall(text)
    if not stated_digests:
        raise SystemExit(
            f"{where} 里没有 `SHA-256：` 一行，无法核对说明与产物是不是同一份构建"
        )
    if len(stated_digests) > 1:
        raise SystemExit(
            f"{where} 里有 {len(stated_digests)} 处 SHA-256："
            f"{stated_digests}，无法判断哪一处是本页产物的摘要"
        )
    stated_sizes = NOTES_SIZE_RE.findall(text)
    if len(stated_sizes) != 1:
        raise SystemExit(
            f"{where} 里需要且只能有一处 `字节数：`，实际找到 {len(stated_sizes)} 处，"
            "无法核对说明与产物是不是同一份构建"
        )
    stated_size = int(stated_sizes[0].replace(",", ""))

    mismatch = stated_digests[0].lower() != actual_digest or stated_size != actual_size
    if not mismatch:
        print(
            f"notes: 说明里的 SHA-256 与字节数就是这份产物"
            f"（{actual_size} 字节 {actual_digest[:12]}）"
        )
        return

    raise SystemExit(
        "发行说明与要发布的产物不是同一份构建，已在任何上传之前停下：\n"
        f"  说明写的 SHA-256：{stated_digests[0]}\n"
        f"  产物的 SHA-256  ：{actual_digest}\n"
        f"  说明写的字节数  ：{stated_size}\n"
        f"  产物的字节数    ：{actual_size}\n"
        f"  把 {where} 改成本次产物的数字再发布"
        "（Android 构建不可字节复现，重建一次摘要就会变）。"
    )


def notes_for_feed(notes_bytes, limit=FEED_NOTES_LIMIT):
    """The release notes flattened into the plain text the feed carries.

    The app's dialog prints this text as it comes — there is no Markdown renderer on that
    side — so the markers the notes are written with come off here, once, where a gate
    can look at the result, instead of on a user's phone:

      * heading markers (`# …`) drop, the heading text stays;
      * list bullets become `· `, which is what the release page shows anyway;
      * emphasis (`**x**`, `__x__`) and code spans (`` `x` ``) unwrap;
      * links keep their label and drop the target — it is long, unclickable inside a plain
        `Text`, and one tap away behind「在浏览器打开」.

    Blank lines collapse to one so paragraph breaks survive without opening gaps, and a
    result past [limit] is cut on a line boundary with an ellipsis. The number matches the
    app's own ceiling (`AppUpdateManifestParser.MAX_NOTES_CHARS`), so a feed written by this
    function is shown whole.
    """
    lines = []
    for raw in notes_bytes.decode("utf-8").splitlines():
        line = raw.strip()
        line = re.sub(r"^#{1,6}\s*", "", line)
        line = re.sub(r"^[-*+]\s+", "· ", line)
        line = re.sub(r"\*\*(.+?)\*\*", r"\1", line)
        line = re.sub(r"__(.+?)__", r"\1", line)
        line = re.sub(r"`([^`]*)`", r"\1", line)
        line = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", line)
        if line == "" and (not lines or lines[-1] == ""):
            continue
        lines.append(line)
    while lines and lines[-1] == "":
        lines.pop()
    text = "\n".join(lines)
    if len(text) <= limit:
        return text
    cut = text[: limit - 2].rstrip()
    newline = cut.rfind("\n")
    if newline > limit // 2:
        cut = cut[:newline]
    return cut.rstrip() + "\n…"


def verify_feed_notes(text, where, limit=FEED_NOTES_LIMIT):
    """Refuse a feed whose notes still look like Markdown, or are past the app's ceiling.

    A gate rather than trust in [notes_for_feed]: the converter is the only thing standing
    between the notes and a dialog that prints exactly what it is handed, and a rule the
    notes grow later (a table, a nested list, an image) would otherwise surface as literal
    `**` on a phone. Cheap to check here, impossible to notice there.
    """
    name = where
    if not text.strip():
        raise SystemExit(f"{name} 转成纯文本后是空的，清单里没有可显示的说明")
    if len(text) > limit:
        raise SystemExit(
            f"清单里的说明 {len(text)} 字，超过上限 {limit}（App 侧会截断成省略号）；"
            f"要么删减 {name}，要么同时提高两边的上限"
        )
    leftovers = [marker for marker in ("**", "__", "`", "](") if marker in text]
    if leftovers:
        raise SystemExit(f"清单里的说明还留着 Markdown 记号 {leftovers}（{name}），补 notes_for_feed 的规则")
    prefixed = next(
        (line for line in text.splitlines() if line.startswith(("#", "- ", "* ", "> "))), None
    )
    if prefixed is not None:
        raise SystemExit(f"清单里的说明还有未转换的行：{prefixed!r}（{name}）")


def sdk_roots():
    """Every SDK root this machine declares, most explicit first."""
    roots = [os.environ[name] for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME") if os.environ.get(name)]
    properties = os.path.join(PROJECT, "local.properties")
    if os.path.isfile(properties):
        with open(properties, encoding="utf-8") as handle:
            for line in handle:
                if line.strip().startswith("sdk.dir="):
                    # A Java properties file escapes the colon on Windows (E\:/sdk).
                    roots.append(line.strip().split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    return roots


def build_tools_tool(name):
    """The newest build-tools copy of [name] (`aapt2`, `apksigner`) this machine has."""
    suffixes = [".bat", ".exe", ""] if os.name == "nt" else ["", ".exe", ".bat"]
    for root in sdk_roots():
        build_tools = os.path.join(root, "build-tools")
        if not os.path.isdir(build_tools):
            continue
        versions = sorted(
            os.listdir(build_tools),
            key=lambda value: [int(part) for part in re.findall(r"\d+", value)],
        )
        for version in reversed(versions):
            for suffix in suffixes:
                candidate = os.path.join(build_tools, version, name + suffix)
                if os.path.isfile(candidate):
                    return candidate
    raise SystemExit(
        f"找不到 {name}：设置 ANDROID_SDK_ROOT，或在 local.properties 里写 sdk.dir"
    )


def aapt2_path():
    """The aapt2 to read the APK with, from whichever SDK this machine declares."""
    return build_tools_tool("aapt2")


def apksigner_path():
    """The apksigner to read the APK's signer with."""
    return build_tools_tool("apksigner")


def verify_apk_signature(apk_path):
    """Stop unless the APK is signed by the release certificate.

    Nothing downstream can catch this. The feed's size and digest are derived from whatever
    bytes are handed to this script, so they stay self-consistent for an unsigned or
    debug-signed artifact; the download-back check compares bytes with bytes; and the notes
    check compares the notes with the same bytes. Every one of those gates passes on an APK
    no installed device will accept, and the update channel then fails silently for everyone
    (the installer refuses it, or the package-name check in the app does). The signature is
    the one property that has to come from the key rather than from the file, so it is the
    one checked against a value this script carries itself.
    """
    result = subprocess.run(
        [apksigner_path(), "verify", "--print-certs", apk_path],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        output = (result.stdout + result.stderr).strip()
        raise SystemExit(f"apksigner 判定这个 APK 不可安装（{os.path.basename(apk_path)}）：{output[:300]!r}")
    digests = {value.lower() for value in re.findall(r"SHA-256 digest:\s*([0-9a-fA-F]{64})", result.stdout)}
    if not digests:
        raise SystemExit(f"apksigner 没有报出签名证书摘要：{result.stdout[:200]!r}")
    if RELEASE_CERT_SHA256 not in digests:
        raise SystemExit(
            "这个 APK 的签名证书不是发布证书，已在任何上传之前停下：\n"
            f"  期望发布证书：{RELEASE_CERT_SHA256}\n"
            f"  实际证书    ：{', '.join(sorted(digests))}\n"
            "  用发布密钥重新构建 optimized 变体，或（换了密钥的话）同步更新本脚本里的指纹。"
        )
    print(f"signature: 由发布证书签名（{RELEASE_CERT_SHA256[:12]}）")


def verify_release_identity(identity, apk_path):
    """Stop unless the artifact is this app and matches the source's release version.

    Digest/read-back checks only prove the bytes agree with each other, not that an APK
    was built for this release. Both release and optimized use the source version without
    a suffix; a stale or debug APK must not acquire the current release's name in the feed.
    """
    if identity["packageName"] != APP_PACKAGE:
        raise SystemExit(
            f"{os.path.basename(apk_path)} 的包名是 {identity['packageName']!r}，"
            f"不是发布包名 {APP_PACKAGE!r}；它不能作为更新源发布"
        )
    with open(GRADLE, encoding="utf-8") as handle:
        code = re.search(r"^\s*versionCode\s*=\s*([0-9][0-9_]*)\s*(?://[^\n]*)?$", handle.read(), re.MULTILINE)
    if code is None:
        raise SystemExit("app/build.gradle.kts 里找不到 versionCode 整数")
    expected = {"versionName": version(), "versionCode": int(code.group(1).replace("_", ""))}
    for field, value in expected.items():
        if identity[field] != value:
            raise SystemExit(
                f"{os.path.basename(apk_path)} 的 {field} 是 {identity[field]!r}，"
                f"与 app/build.gradle.kts 的 {value!r} 不一致；重新构建后再发布"
            )
    print(f"identity: 包名 {identity['packageName']}，版本代码 {identity['versionCode']}")


NOTES_VERSION_CODE_RE = re.compile(r"(?:版本代码|versionCode)[：:]\s*`([0-9]+)`")


def notes_version_code(text, where):
    """Version-release bodies are the durable fallback when a feed upload is interrupted."""
    codes = NOTES_VERSION_CODE_RE.findall(text)
    if len(codes) != 1 or int(codes[0]) <= 0:
        raise SystemExit(f"{where} 缺少唯一的有效版本代码，无法确认线上版本代码，停止发布")
    return int(codes[0])


def released_app_version_code(token):
    """Maximum code from all public version releases, not release creation/tag ordering.

    Rebuilt releases can keep their tag. Never guess a code from semantic versioning or
    silently ignore a version release whose body cannot establish its version code.
    """
    highest = None
    page = 1
    while True:
        status, body = publish_bank.request(
            token, "GET", f"{API}/repos/{REPO}/releases?per_page=100&page={page}",
        )
        if status != 200:
            raise SystemExit(f"无法确认线上版本代码：读取发行历史失败 HTTP {status}")
        try:
            releases = json.loads(body)
            if not isinstance(releases, list):
                raise ValueError("release list is not an array")
            for release in releases:
                if not isinstance(release, dict) or not isinstance(release.get("tag_name"), str):
                    raise ValueError("invalid release metadata")
                tag = release["tag_name"]
                if release.get("draft") or not re.fullmatch(r"v[0-9]+(?:\.[0-9]+)+", tag):
                    continue
                text = release.get("body")
                if not isinstance(text, str):
                    raise ValueError(f"{tag} has no release body")
                code = notes_version_code(text, tag)
                highest = max(highest or 0, code)
        except (ValueError, KeyError, TypeError) as error:
            raise SystemExit(f"无法确认线上版本代码：发行历史损坏（{error}）") from error
        if len(releases) < 100:
            return highest
        page += 1


def published_app_version_code(token):
    """Return (live code, checked release snapshot), using history for absent/starter feeds.

    An uploaded feed that cannot be read still fails closed. The checked asset id must
    also be the one deletion targets; refreshing it after the check could delete a
    newer publication whose version this invocation never checked.
    """
    release = publish_bank.release_by_tag(token, APP_TAG)
    if release is None:
        return released_app_version_code(token), None
    assets = {asset["name"]: asset for asset in release["assets"]}
    if APP_ASSET not in assets or assets[APP_ASSET].get("state") == "starter":
        return released_app_version_code(token), release
    try:
        body = publish_bank.download(assets[APP_ASSET]["browser_download_url"], cache_bust=True)
        manifest = json.loads(body)
        if not isinstance(manifest, dict):
            raise ValueError("线上清单不是 JSON 对象")
        value = manifest.get("versionCode")
        if type(value) is not int or value <= 0:
            raise ValueError("线上清单缺少有效的 versionCode 正整数")
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(f"无法确认线上版本代码，已在任何发布写入前停止：{error}") from error
    return value, release


def verify_monotonic_version_code(token, version_code, allow_nonmonotonic):
    """Stop when the feed would move *backwards*.

    Going backwards is the failure worth catching: devices already on a higher code compare
    `feed > installed`, see false, and report "up to date" forever — the channel stops as
    surely as if the feed were empty, and nothing says so. Publishing the *same* code again
    is this repository's normal release model (one public version per number, rebuilt in
    place), so equality is allowed and only a decrease is refused.
    """
    published, release = published_app_version_code(token)
    if published is None:
        print("monotonic: 线上还没有可比对的清单")
        return release
    if version_code < published:
        if allow_nonmonotonic:
            print(f"monotonic: 本次 {version_code} 低于线上 {published}，按 --allow-nonmonotonic 继续")
            return release
        raise SystemExit(
            f"本次要发布的版本代码 {version_code} 低于线上清单的 {published}，已停止：\n"
            "  发布后线上版本会倒退，已经升级的设备会把更新判成「已是最新」，更新通道静默失效。\n"
            "  确认这是有意为之，再加 --allow-nonmonotonic 重跑。"
        )
    relation = "相同（同号重建，允许）" if version_code == published else "更高"
    print(f"monotonic: 线上 {published} → 本次 {version_code}（{relation}）")
    return release



def apk_identity(apk_path):
    """What the APK says about itself: package name, version code, version name, minSdk.

    Read from the artifact rather than from app/build.gradle.kts: the package name carries
    the variant's suffix (`.debug` while `optimized` still uses one), and the app compares
    the feed's package name with the one it was installed under — so the feed has to carry
    the name that is actually inside the APK, not the one in `defaultConfig`.
    """
    result = subprocess.run(
        [aapt2_path(), "dump", "badging", apk_path],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"aapt2 读不出 APK 信息：{result.stderr[:300]!r}")
    header = re.search(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
        result.stdout,
    )
    min_sdk = re.search(r"minSdkVersion:'(\d+)'", result.stdout)
    if not header or not min_sdk:
        raise SystemExit(f"aapt2 的输出里没有包信息：{result.stdout[:200]!r}")
    return {
        "packageName": header.group(1),
        "versionCode": int(header.group(2)),
        "versionName": header.group(3),
        "minSdk": int(min_sdk.group(1)),
    }


def build_app_manifest(identity, apk_bytes, tag, name, notes_url, notes):
    """The feed document describing the APK that is about to be published.

    `versionName` is the source release version, already checked against the artifact by
    verify_release_identity. Neither release nor optimized has a name suffix, so the APK,
    tag and feed must describe the same version.

    `notes` is the release notes as plain text ([notes_for_feed]), which is what lets the
    app's update dialog show them inline instead of behind a link.
    """
    return {
        "formatVersion": APP_FORMAT_VERSION,
        "packageName": identity["packageName"],
        "versionCode": identity["versionCode"],
        "versionName": version(),
        "apkUrl": f"https://github.com/{REPO}/releases/download/{tag}/{name}",
        "sizeBytes": len(apk_bytes),
        "sha256": sha256(apk_bytes),
        "minSdk": identity["minSdk"],
        "notesUrl": notes_url,
        "notes": notes,
        "publishedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
    }


def verify_app_manifest(release_tag, manifest_bytes, manifest, attempts=6, wait_seconds=5.0,
                        tolerate_published_at=False):
    """Download the feed back and check it is byte for byte the document this run built.

    The byte comparison is the load-bearing check: it is what makes a stale CDN copy, or a
    feed left over from a previous publish, fail instead of looking plausible. `manifest` is
    only used to restate a mismatch in field terms when the bytes *do* match but a field
    does not — which cannot happen while the bytes compared against are the ones built here,
    so that loop is a guard for a future refactor, not this run's evidence.

    [tolerate_published_at] is for `--check` only. A check run rebuilds the document locally,
    so its `publishedAt` is the moment of the check, never the moment the feed went up; the
    byte comparison can therefore never hold on that field. With it on, the documents are
    compared field by field and everything except the timestamp still has to be identical —
    a changed digest is still a failure, named by field.
    """
    assets = {asset["name"]: asset for asset in release_tag["assets"]}
    if APP_ASSET not in assets:
        raise SystemExit(f"发布后找不到资产 {APP_ASSET}")
    url = assets[APP_ASSET]["browser_download_url"]
    seen = None
    last_error = None
    last_differing = None
    policy = retry.Policy(attempts=attempts, base_delay=wait_seconds, max_delay=wait_seconds, jitter=0)
    for attempt in range(attempts):
        try:
            # Cache-busted and single-try per read: this loop owns the attempt count, and a
            # just-replaced asset can be served from a CDN cache for a moment.
            fetched = publish_bank.download(url, cache_bust=True, attempts=1)
        except Exception as error:  # noqa: BLE001 - only transient errors or CDN 404 retry
            missing = isinstance(error, urllib.error.HTTPError) and error.code == 404
            if not missing and not retry.transient_error(error):
                raise SystemExit(f"清单回读校验未能完成：{error}") from error
            last_error = error
            print(f"清单回读失败（第 {attempt + 1}/{attempts} 次）：{error}")
            if attempt + 1 < attempts:
                headers = error.headers if isinstance(error, urllib.error.HTTPError) else None
                retry.delay_for_status(policy, attempt, headers)
            continue
        last_error = None
        if fetched == manifest_bytes:
            online = json.loads(fetched)
            for field in ("packageName", "versionCode", "versionName", "sizeBytes", "sha256", "apkUrl"):
                if online.get(field) != manifest[field]:
                    raise SystemExit(f"线上清单的 {field} 与本次发布不符：{online.get(field)!r} ≠ {manifest[field]!r}")
            print(
                f"verified: {APP_ASSET} 回读一致（版本代码 {online['versionCode']}，"
                f"包名 {online['packageName']}，sha256 {online['sha256'][:12]}）"
            )
            return online
        if tolerate_published_at:
            online, differing = differing_only_in_published_at(fetched, manifest)
            if online is not None:
                print(
                    f"verified: {APP_ASSET} 除 publishedAt 外与本地逐字段一致"
                    f"（线上 {online.get('publishedAt')}；版本代码 {online['versionCode']}，"
                    f"sha256 {online['sha256'][:12]}）"
                )
                return online
            if differing is not None:
                last_differing = differing
                print(f"线上清单与本地的差异字段：{'、'.join(differing)}")
        seen = len(fetched)
        if attempt + 1 < attempts:
            time.sleep(wait_seconds)
    if seen is None:
        raise SystemExit(f"清单回读校验未能完成（重试 {attempts} 次）：{last_error}")
    if last_differing:
        # The field names are the whole diagnosis: "487 字节 vs 487 字节" cannot tell a stale
        # copy from a digest that was rebuilt, and the difference is usually one field.
        raise SystemExit(
            f"清单回读校验失败（重试 {attempts} 次）：本地 {len(manifest_bytes)} 字节，"
            f"线上 {seen} 字节；不同的字段：{'、'.join(last_differing)}",
        )
    raise SystemExit(
        f"清单回读校验失败（重试 {attempts} 次）：本地 {len(manifest_bytes)} 字节，线上 {seen} 字节",
    )


def differing_only_in_published_at(fetched, manifest):
    """Compare a fetched feed with [manifest], ignoring `publishedAt`.

    Returns `(online, None)` when that timestamp is the only difference — the parsed live
    document, which the caller accepts. Returns `(None, fields)` when the body parses but
    other fields differ, naming them so the failure is diagnosable. Returns `(None, None)`
    when the body is not a JSON object at all (that is a stale or broken asset, not a field
    mismatch, and belongs to the retry loop rather than to a field list).
    """
    try:
        online = json.loads(fetched)
    except ValueError:
        return None, None
    if not isinstance(online, dict):
        return None, None
    local = {key: value for key, value in manifest.items() if key != "publishedAt"}
    live = {key: value for key, value in online.items() if key != "publishedAt"}
    if live != local:
        fields = sorted(
            key for key in set(live) | set(local) if live.get(key) != local.get(key)
        )
        return None, (fields if fields else ["（字段集合不同）"])
    return online, None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", help="要发布的 APK，默认取 optimized 构建产物")
    parser.add_argument(
        "--notes",
        help="说明草稿文件；不给就从发行版正文读（正文就是说明的出处），"
             "给了则在发布时把正文同步成这份草稿",
    )
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument(
        "--dry-run",
        action="store_true",
        help="离线本地门禁，不读凭据、不校验线上版本或回读；配 --notes 才核对说明并预览清单",
    )
    modes.add_argument("--check", action="store_true", help="联网只读校验线上与本地是否一致，不发布或改写正文")
    parser.add_argument(
        "--allow-nonmonotonic",
        action="store_true",
        help="允许把低于线上清单的版本代码发出去（默认拒绝，因为会让更新通道静默失效）",
    )
    args = parser.parse_args()

    tag = "v" + version()
    apk_path = args.apk or os.path.join(
        PROJECT, "app/build/outputs/apk/optimized/app-optimized.apk"
    )
    name = f"RelayTester-{tag}-android.apk"
    with open(apk_path, "rb") as handle:
        apk_bytes = handle.read()
    print(f"{tag}: {name} {len(apk_bytes)} 字节 sha256 {sha256(apk_bytes)}")

    # A draft is read here so the notes gates can run offline; without one the text comes
    # from the release body below, which is where the notes live.
    notes_bytes = None
    notes_from = None
    if args.notes:
        with open(args.notes, "rb") as handle:
            notes_bytes = handle.read()
        notes_from = os.path.basename(args.notes)
        print(f"notes: {notes_from} {len(notes_bytes)} 字节")

    # Three gates that no downstream check can substitute for: the APK has to be signed by
    # the release key, carry this app's package name, and hold the wording the source says.
    # All three read the artifact being published, all three run before anything is uploaded.
    verify_apk_signature(apk_path)
    verify_apk_wording.verify(apk_path, verbose=False)
    # A fourth, about the binary rather than the text: a library built for 4 KB pages cannot
    # load on a 16 KB device, and no test on this machine can see that (its page size is 4 KB).
    verify_native_alignment.check(apk_path)

    # Built before anything is uploaded: a feed that cannot be derived (no aapt2, an APK
    # that is not an APK) has to stop the run while the release is still untouched.
    identity = apk_identity(apk_path)
    verify_release_identity(identity, apk_path)

    if args.dry_run:
        print("dry-run: 只跑离线本地门禁；未校验线上发行版、版本代码防倒退或资产回读")
        if notes_bytes is None:
            print("notes: 未提供 --notes，跳过说明门禁与清单预览；用 --notes <草稿> 可补做离线校验")
            return 0

    # With a draft, every local gate (and the whole preview) precedes credentials and HTTP.
    # Without one, the release body is the single source and must be fetched read-only.
    token = None
    release = None
    if notes_bytes is None:
        token = read_token()
        release = release_of(token, tag)
        if release is None:
            raise SystemExit(f"{tag} release 不存在；先建标签与发行版再发布资产")
        body = release.get("body") or ""
        if not body.strip():
            raise SystemExit(f"{tag} 的发行版正文是空的：说明就写在发行版正文里，先补上再发布")
        notes_bytes = body.encode("utf-8")
        notes_from = "发行版正文"
        print(f"notes: {notes_from} {len(notes_bytes)} 字节")

    # The notes name the bytes being published and a rebuild moves the digest under them;
    # this is the last moment before an upload at which the two can be reconciled. The feed's
    # plain-text copy is derived from the same text and checked here, with the other gates.
    verify_notes_identity(notes_bytes, apk_bytes, notes_from)
    if notes_version_code(notes_bytes.decode("utf-8"), notes_from) != identity["versionCode"]:
        raise SystemExit("发行说明里的版本代码与 APK 不一致，已在任何发布写入前停止")
    feed_notes = notes_for_feed(notes_bytes)
    verify_feed_notes(feed_notes, notes_from)
    print(f"notes: 清单内说明 {len(feed_notes)} 字（上限 {FEED_NOTES_LIMIT}）")
    manifest = build_app_manifest(
        identity,
        apk_bytes,
        tag,
        name,
        notes_url=f"https://github.com/{REPO}/releases/tag/{tag}",
        notes=feed_notes,
    )
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    print(f"{APP_TAG}: {identity['packageName']} 版本代码 {identity['versionCode']} "
          f"→ 清单 {len(manifest_bytes)} 字节")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

    if args.dry_run:
        return 0

    if release is None:
        token = read_token()
        release = release_of(token, tag)
        if release is None:
            raise SystemExit(f"{tag} release 不存在；先建标签与发行版再发布资产")

    if args.check:
        verify(release, name, apk_bytes)
        app_release = publish_bank.release_by_tag(token, APP_TAG)
        if app_release is None:
            raise SystemExit(f"{APP_TAG} release 不存在")
        # `publishedAt` is the one field a check run cannot reproduce: it records when the
        # feed went up, and a locally rebuilt document carries the time of the check. Every
        # other field — the digest above all — is still compared exactly; see the helper.
        verify_app_manifest(app_release, manifest_bytes, manifest, tolerate_published_at=True)
        return 0

    # Read-back of the live feed, so publishing cannot silently move its known version code
    # backwards. This online gate is read-only and still precedes every mutation.
    verify_monotonic_version_code(token, identity["versionCode"], args.allow_nonmonotonic)

    for asset in release["assets"]:
        if asset["name"] == name:
            status, body = request(
                token, "DELETE", f"{API}/repos/{REPO}/releases/assets/{asset['id']}"
            )
            if status not in (204, 200):
                raise SystemExit(f"删除旧资产 {name} 失败：HTTP {status} {body[:200]!r}")
            print(f"deleted old asset {name}")

    upload_url = release["upload_url"].split("{")[0] + f"?name={name}"
    status, body = request(
        token, "POST", upload_url, apk_bytes, "application/vnd.android.package-archive", raw=True
    )
    if status not in (200, 201):
        raise SystemExit(f"上传 {name} 失败：HTTP {status} {body[:300]!r}")
    print(f"uploaded {name}")

    if args.notes:
        # The draft was the source, so the page is brought in line with it before the feed —
        # built from the same text — goes up. Without a draft the body already *is* the
        # notes and there is nothing to sync.
        status, body = request(
            token,
            "PATCH",
            f"{API}/repos/{REPO}/releases/{release['id']}",
            {"body": notes_bytes.decode("utf-8")},
        )
        if status != 200:
            raise SystemExit(f"更新发行说明失败：HTTP {status} {body[:200]!r}")
        print(f"updated release notes from {notes_from}")

    # Verify the APK before advertising it, then re-read the live version fence after
    # the potentially slow upload. This catches another publisher advancing the feed.
    release = release_of(token, tag)
    verify(release, name, apk_bytes)
    app_release = verify_monotonic_version_code(token, identity["versionCode"], args.allow_nonmonotonic)
    # Use exactly the checked snapshot. If another publisher replaces its asset,
    # DELETE of this old id returns 404 and stops rather than deleting the newer id.
    if app_release is None:
        status, body = publish_bank.request(token, "POST", f"{API}/repos/{REPO}/releases", {
            "tag_name": APP_TAG, "name": APP_RELEASE_NAME, "body": APP_RELEASE_BODY,
            "prerelease": True, "draft": False,
        })
        if status not in (200, 201):
            raise SystemExit(f"创建 {APP_TAG} release 失败或已有其它发布：HTTP {status} {body[:200]!r}")
        app_release = json.loads(body)
    publish_bank.replace_asset(token, app_release, APP_ASSET, manifest_bytes, "application/json")
    app_release = publish_bank.release_by_tag(token, APP_TAG)
    verify_app_manifest(app_release, manifest_bytes, manifest)

    release = release_of(token, tag)
    verify(release, name, apk_bytes)
    print("published", tag, sha256(apk_bytes)[:12], "版本代码", identity["versionCode"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
