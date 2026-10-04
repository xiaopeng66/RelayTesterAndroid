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

Usage:
    python tools/publish_release_apk.py --dry-run   # print what would be published
    python tools/publish_release_apk.py --check     # verify the published assets match
    python tools/publish_release_apk.py             # upload the APK, the notes and the feed

Files are read from the repository (app/build.gradle.kts, the optimized APK and
RELEASE_NOTES_<version>.md), so nothing here can drift from the build except by being
run against a stale APK — which the download-back check at the end catches.

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

REPO = "xiaopeng66/RelayTesterAndroid"
API = "https://api.github.com"
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join(PROJECT, "app/build.gradle.kts")
TOKEN_FILES = ["E:/AI/Zcode/tmp/.ghtoken"]

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


def aapt2_path():
    """The aapt2 to read the APK with, from whichever SDK this machine declares."""
    roots = [os.environ[name] for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME") if os.environ.get(name)]
    properties = os.path.join(PROJECT, "local.properties")
    if os.path.isfile(properties):
        with open(properties, encoding="utf-8") as handle:
            for line in handle:
                if line.strip().startswith("sdk.dir="):
                    # A Java properties file escapes the colon on Windows (E\:/sdk).
                    roots.append(line.strip().split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    executable = "aapt2.exe" if os.name == "nt" else "aapt2"
    for root in roots:
        build_tools = os.path.join(root, "build-tools")
        if not os.path.isdir(build_tools):
            continue
        versions = sorted(
            os.listdir(build_tools),
            key=lambda value: [int(part) for part in re.findall(r"\d+", value)],
        )
        for version in reversed(versions):
            candidate = os.path.join(build_tools, version, executable)
            if os.path.isfile(candidate):
                return candidate
    raise SystemExit("找不到 aapt2：设置 ANDROID_SDK_ROOT，或在 local.properties 里写 sdk.dir")


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


def verify_app_manifest(release_tag, manifest_bytes, manifest, attempts=6, wait_seconds=5.0):
    """Download the feed back and check it is byte for byte the document this run built.

    The byte comparison is the load-bearing check: it is what makes a stale CDN copy, or a
    feed left over from a previous publish, fail instead of looking plausible. `manifest` is
    only used to restate a mismatch in field terms when the bytes *do* match but a field
    does not — which cannot happen while the bytes compared against are the ones built here,
    so that loop is a guard for a future refactor, not this run's evidence.
    """
    assets = {asset["name"]: asset for asset in release_tag["assets"]}
    if APP_ASSET not in assets:
        raise SystemExit(f"发布后找不到资产 {APP_ASSET}")
    url = assets[APP_ASSET]["browser_download_url"]
    seen = None
    last_error = None
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
        seen = len(fetched)
        if attempt + 1 < attempts:
            time.sleep(wait_seconds)
    if seen is None:
        raise SystemExit(f"清单回读校验未能完成（重试 {attempts} 次）：{last_error}")
    raise SystemExit(
        f"清单回读校验失败（重试 {attempts} 次）：本地 {len(manifest_bytes)} 字节，线上 {seen} 字节",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", help="要发布的 APK，默认取 optimized 构建产物")
    parser.add_argument("--dry-run", action="store_true", help="只打印，不联网")
    parser.add_argument("--check", action="store_true", help="只校验线上与本地是否一致")
    args = parser.parse_args()

    tag = "v" + version()
    apk_path = args.apk or os.path.join(
        PROJECT, "app/build/outputs/apk/optimized/app-optimized.apk"
    )
    notes_path = os.path.join(PROJECT, f"RELEASE_NOTES_{version()}.md")
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

    # Built before anything is uploaded: a feed that cannot be derived (no aapt2, an APK
    # that is not an APK) has to stop the run while the release is still untouched.
    identity = apk_identity(apk_path)
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

    token = read_token()
    release = release_of(token, tag)
    if release is None:
        raise SystemExit(f"{tag} release 不存在；先建标签与发行版再发布资产")
    if args.check:
        verify(release, name, apk_bytes)
        app_release = publish_bank.release_by_tag(token, APP_TAG)
        if app_release is None:
            raise SystemExit(f"{APP_TAG} release 不存在")
        verify_app_manifest(app_release, manifest_bytes, manifest)
        return 0

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
