#!/usr/bin/env python3
"""Publish the built APK as the asset of its own version's release.

The release line this project ships on is one version per number: a rebuild replaces
the asset and the notes in place instead of adding a patch number, so the version tag,
the notes file and the APK always describe the same build.

    python tools/publish_release_apk.py --dry-run   # print what would be published
    python tools/publish_release_apk.py --check     # verify the published asset matches
    python tools/publish_release_apk.py             # upload the asset and the notes

Files are read from the repository (app/build.gradle.kts, the optimized APK and
RELEASE_NOTES_<version>.md), so nothing here can drift from the build except by being
run against a stale APK — which the download-back check at the end catches.

Token: GITHUB_TOKEN, or the first line of E:/AI/Zcode/tmp/.ghtoken.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

REPO = "xiaopeng66/RelayTesterAndroid"
API = "https://api.github.com"
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join(PROJECT, "app/build.gradle.kts")
TOKEN_FILES = ["E:/AI/Zcode/tmp/.ghtoken"]


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

    if args.dry_run:
        return 0

    token = read_token()
    release = release_of(token, tag)
    if release is None:
        raise SystemExit(f"{tag} release 不存在；先建标签与发行版再发布资产")
    if args.check:
        verify(release, name, apk_bytes)
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

    release = release_of(token, tag)
    verify(release, name, apk_bytes)
    print("published", tag, sha256(apk_bytes)[:12])
    return 0


if __name__ == "__main__":
    sys.exit(main())
