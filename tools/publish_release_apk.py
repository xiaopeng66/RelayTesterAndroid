#!/usr/bin/env python3
"""Publish the built APK as the asset of its own version's release, plus the feed the app reads.

The release line this project ships on is one version per number: a rebuild replaces
the asset and the notes in place instead of adding a patch number, so the version tag,
the notes file and the APK always describe the same build.

Two releases are involved, and this script is the only thing that touches both:

    v<version>   the APK (RelayTester-v<version>-android.apk) and the release notes
    app          app-latest.json, the manifest the app's update page polls

The manifest is derived from the APK being published — package name, version code and
minimum SDK are read out of the artifact with aapt2, its size and SHA-256 from the same
bytes — so the feed cannot describe a build other than the one that went up. The version
*code* is the field the app compares: this repository republishes under an unchanged
version name, so a tag comparison would call a 10502 install up to date.

Both assets are downloaded again afterwards and compared byte for byte.

Before anything is uploaded, the artifact has to pass four gates that a self-consistent
feed cannot substitute for: it must be signed by the release certificate, carry this app's
package name, still hold the wording the source says, and (compared against the live feed)
not move the advertised version code backwards. The README's line for this version is
rewritten to the numbers being published, because it went stale once already.

Usage:
    python tools/publish_release_apk.py --dry-run   # print what would be published
    python tools/publish_release_apk.py --check     # verify the published assets match
    python tools/publish_release_apk.py             # upload the APK, the notes and the feed

Files are read from the repository (app/build.gradle.kts, the optimized APK and
release-notes/RELEASE_NOTES_<version>.md), so nothing here can drift from the build except
by being run against a stale APK — which the download-back check at the end catches.

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
import verify_apk_wording  # noqa: E402 - same, the wording invariants for the shipped APK
import verify_native_alignment  # noqa: E402 - same, the 16 KB page pairing for its .so files

REPO = "xiaopeng66/RelayTesterAndroid"
API = "https://api.github.com"
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join(PROJECT, "app/build.gradle.kts")
README = os.path.join(PROJECT, "README.md")
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

- `app-latest.json`：最新版本的版本代码、版本名、安装包地址与 SHA-256。

App 只比较版本代码（本仓库会沿用同一个版本名重新发布，版本名不足以判断新旧）。
这里不存放安装包本体，APK 在对应的 `v<版本>` 发行版里。
"""


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
    fetched = download(assets[name]["browser_download_url"])
    if fetched != apk_bytes:
        raise SystemExit(
            f"回读的 {name} 与本地文件不一致（{len(fetched)} 字节 vs {len(apk_bytes)} 字节）"
        )
    print(f"verified: {name} 回读与本地逐字节一致（{len(fetched)} 字节，{sha256(fetched)[:12]}）")


NOTES_DIGEST_RE = re.compile(r"SHA-256[：:]\s*`([0-9a-fA-F]{64})`")
NOTES_SIZE_RE = re.compile(r"字节数[：:]\s*`([\d,]+)`")


def verify_notes_identity(notes_bytes, apk_bytes, notes_path):
    """Stop when the release notes describe bytes other than the APK being published.

    A Gradle Android build is not byte-reproducible: rebuilding the same source yields the
    same size at a different digest, so a digest written into the notes — and shown on the
    release page — goes stale on any rebuild after it was written. The notes are read before
    anything is uploaded, so this is the last point at which the two can be reconciled.
    """
    text = notes_bytes.decode("utf-8")
    actual_digest = sha256(apk_bytes)
    actual_size = len(apk_bytes)
    stated_digests = NOTES_DIGEST_RE.findall(text)
    if not stated_digests:
        raise SystemExit(
            f"{os.path.basename(notes_path)} 里没有 `SHA-256：` 一行，"
            "无法核对说明与产物是不是同一份构建"
        )
    if len(stated_digests) > 1:
        raise SystemExit(
            f"{os.path.basename(notes_path)} 里有 {len(stated_digests)} 处 SHA-256："
            f"{stated_digests}，无法判断哪一处是本页产物的摘要"
        )
    size_match = NOTES_SIZE_RE.search(text)
    stated_size = int(size_match.group(1).replace(",", "")) if size_match else None

    mismatch = stated_digests[0].lower() != actual_digest
    if stated_size is not None and stated_size != actual_size:
        mismatch = True
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
        f"  说明写的字节数  ：{stated_size if stated_size is not None else '（未写）'}\n"
        f"  产物的字节数    ：{actual_size}\n"
        f"  把 {os.path.basename(notes_path)} 改成本次产物的数字再发布"
        "（Android 构建不可字节复现，重建一次摘要就会变）。"
    )


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
    """Stop unless the artifact is this app's own package.

    A debug build carries a different package name; the feed would carry it too, and every
    installed device would reject the update before downloading anything — the channel stops
    without an error anyone can see. The name is checked against a constant here rather than
    against the build file because the point is to notice a *different* artifact, which the
    build file cannot say.
    """
    if identity["packageName"] != APP_PACKAGE:
        raise SystemExit(
            f"{os.path.basename(apk_path)} 的包名是 {identity['packageName']!r}，"
            f"不是发布包名 {APP_PACKAGE!r}；它不能作为更新源发布"
        )
    print(f"identity: 包名 {identity['packageName']}，版本代码 {identity['versionCode']}")


README_APK_RE = re.compile(
    r"(`RelayTester-v([0-9.]+)-android\.apk`\s*·\s*`)([\d,]+)"
    r"(`\s*字节\s*·\s*SHA-256\s*`)([0-9a-fA-F]{64})(`)"
)


def sync_readme_digest(apk_bytes, release_version):
    """Rewrite README's line for this version so it names the bytes being published.

    A rebuild moves the digest while the source stays put (the Android build is not
    byte-reproducible), so a number written by hand is stale after the next one — which is
    how this line came to disagree with the release notes, the artifact and the live feed all
    at once. The release notes are *checked* and stop the run; the README is *rewritten*,
    because it is prose this step already owns and a stale line there misleads a reader who
    verifies a download against the file he was given. Only the two numbers move.
    """
    actual_size = len(apk_bytes)
    actual_digest = sha256(apk_bytes)
    with open(README, encoding="utf-8") as handle:
        text = handle.read()

    matches = [match for match in README_APK_RE.finditer(text) if match.group(2) == release_version]
    if not matches:
        print(f"readme: 没有 {release_version} 的安装包行，未改动（若已在别处说明，可忽略）")
        return
    if len(matches) > 1:
        raise SystemExit(f"README.md 里有 {len(matches)} 处 {release_version} 的安装包行，无法判断改哪一处")

    match = matches[0]
    if match.group(3).replace(",", "") == str(actual_size) and match.group(5).lower() == actual_digest:
        print(f"readme: 已与产物一致（{actual_size} 字节 {actual_digest[:12]}）")
        return
    replacement = (
        f"{match.group(1)}{actual_size:,}{match.group(4)}{actual_digest}{match.group(6)}"
    )
    with open(README, "w", encoding="utf-8", newline="") as handle:
        handle.write(text[: match.start()] + replacement + text[match.end():])
    print(
        f"readme: 已改写为本次产物（{match.group(3)} → {actual_size:,} 字节，"
        f"{match.group(5)[:12]} → {actual_digest[:12]}）；记得把 README.md 一并提交"
    )


def published_app_version_code(token):
    """The version code the live feed advertises, or None when there is nothing to compare.

    A missing release, a missing asset or an unreadable body all mean "unknown" rather than
    "older": the caller must not refuse a publish because the network hiccuped.
    """
    release = publish_bank.release_by_tag(token, APP_TAG)
    if release is None:
        return None
    assets = {asset["name"]: asset for asset in release["assets"]}
    if APP_ASSET not in assets:
        return None
    try:
        body = publish_bank.download(
            assets[APP_ASSET]["browser_download_url"], cache_bust=True, attempts=1
        )
        value = json.loads(body).get("versionCode")
    except (urllib.error.URLError, ValueError, KeyError, TypeError) as error:
        print(f"读线上清单失败，跳过版本代码比较：{error}")
        return None
    return int(value) if value is not None else None


def verify_monotonic_version_code(token, version_code, allow_nonmonotonic):
    """Stop when the feed would move *backwards*.

    Going backwards is the failure worth catching: devices already on a higher code compare
    `feed > installed`, see false, and report "up to date" forever — the channel stops as
    surely as if the feed were empty, and nothing says so. Publishing the *same* code again
    is this repository's normal release model (one public version per number, rebuilt in
    place), so equality is allowed and only a decrease is refused.
    """
    published = published_app_version_code(token)
    if published is None:
        print("monotonic: 线上还没有可比对的清单")
        return
    if version_code < published:
        if allow_nonmonotonic:
            print(f"monotonic: 本次 {version_code} 低于线上 {published}，按 --allow-nonmonotonic 继续")
            return
        raise SystemExit(
            f"本次要发布的版本代码 {version_code} 低于线上清单的 {published}，已停止：\n"
            "  发布后线上版本会倒退，已经升级的设备会把更新判成「已是最新」，更新通道静默失效。\n"
            "  确认这是有意为之，再加 --allow-nonmonotonic 重跑。"
        )
    relation = "相同（同号重建，允许）" if version_code == published else "更高"
    print(f"monotonic: 线上 {published} → 本次 {version_code}（{relation}）")



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


def build_app_manifest(identity, apk_bytes, tag, name, notes_url):
    """The feed document describing the APK that is about to be published.

    `versionName` comes from the build file rather than from the APK because the APK's
    carries the variant suffix (`1.5.0-optimized`) while the release it belongs to is
    tagged with the public line — the same field the tag is built from, so the two agree
    by construction.
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
    for attempt in range(attempts):
        try:
            # Cache-busted and single-try per read: this loop owns the attempt count, and a
            # just-replaced asset can be served from a CDN cache for a moment.
            fetched = publish_bank.download(url, cache_bust=True, attempts=1)
        except urllib.error.URLError as error:
            last_error = error
            print(f"清单回读失败（第 {attempt + 1}/{attempts} 次）：{error}")
            if attempt + 1 < attempts:
                time.sleep(wait_seconds)
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
    parser.add_argument("--dry-run", action="store_true", help="只打印，不联网")
    parser.add_argument("--check", action="store_true", help="只校验线上与本地是否一致")
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
    notes_path = os.path.join(PROJECT, "release-notes", f"RELEASE_NOTES_{version()}.md")
    name = f"RelayTester-{tag}-android.apk"
    with open(apk_path, "rb") as handle:
        apk_bytes = handle.read()
    with open(notes_path, "rb") as handle:
        notes_bytes = handle.read()
    print(f"{tag}: {name} {len(apk_bytes)} 字节 sha256 {sha256(apk_bytes)}")
    print(f"notes: {os.path.basename(notes_path)} {len(notes_bytes)} 字节")
    # The notes are part of what goes up, and a rebuild moves the digest under them; checked
    # before anything is uploaded, like the feed below it.
    verify_notes_identity(notes_bytes, apk_bytes, notes_path)

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
    manifest = build_app_manifest(
        identity,
        apk_bytes,
        tag,
        name,
        notes_url=f"https://github.com/{REPO}/releases/tag/{tag}",
    )
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    print(f"{APP_TAG}: {identity['packageName']} 版本代码 {identity['versionCode']} "
          f"→ 清单 {len(manifest_bytes)} 字节")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

    if args.dry_run:
        return 0

    # Local and network-independent: the README names the artifact that exists on disk, so it
    # is corrected before the release is touched. A `--dry-run` deliberately writes nothing.
    sync_readme_digest(apk_bytes, version())

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

    # Read-back of the live feed, so a release built from a stale APK cannot move the version
    # code backwards. Read-only, and the only gate here that needs the network.
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

    status, body = request(
        token,
        "PATCH",
        f"{API}/repos/{REPO}/releases/{release['id']}",
        {"body": notes_bytes.decode("utf-8")},
    )
    if status != 200:
        raise SystemExit(f"更新发行说明失败：HTTP {status} {body[:200]!r}")
    print("updated release notes from", os.path.basename(notes_path))

    # The feed goes up last: it must never advertise an APK that is not there yet, and the
    # read-back below compares it with the version code this run just published.
    app_release = publish_bank.ensure_release(
        token, tag=APP_TAG, name=APP_RELEASE_NAME, body_text=APP_RELEASE_BODY
    )
    publish_bank.replace_asset(token, app_release, APP_ASSET, manifest_bytes, "application/json")
    app_release = publish_bank.release_by_tag(token, APP_TAG)
    verify_app_manifest(app_release, manifest_bytes, manifest)

    release = release_of(token, tag)
    verify(release, name, apk_bytes)
    print("published", tag, sha256(apk_bytes)[:12], "版本代码", identity["versionCode"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
