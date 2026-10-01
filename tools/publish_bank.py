#!/usr/bin/env python3
"""Publish the reference bank for the app's in-app updater.

Uploads two assets to the `bank` pre-release of the app repository:

    lite-bank.bin   the packed bank, byte-identical to what ships in the APK
    latest.json     the manifest the app polls from its "检查更新" button

The manifest is derived from the asset itself — build stamp, model count, reference
digest, size and SHA-256 are all read back out of the binary — so the two can never
disagree. Both files are downloaded again afterwards and compared byte for byte.

Usage:
    python tools/publish_bank.py                 # publish the packaged asset
    python tools/publish_bank.py --dry-run       # print the manifest, upload nothing
    python tools/publish_bank.py --check         # verify what is published matches
    python tools/publish_bank.py --bank <path>   # publish a different asset

Token: GITHUB_TOKEN, or the first line of E:/AI/Zcode/tmp/.ghtoken.
"""

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import urllib.error
import urllib.request

REPO = "xiaopeng66/RelayTesterAndroid"
TAG = "bank"
RELEASE_NAME = "参考库（应用内更新源）"
API = "https://api.github.com"
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BANK = os.path.join(PROJECT, "app/src/main/assets/lm-fingerprint/lite-bank.bin")
GRADLE = os.path.join(PROJECT, "app/build.gradle.kts")
TOKEN_FILES = ["E:/AI/Zcode/tmp/.ghtoken"]
MAGIC = b"LMFPA001"
FORMAT_VERSION = 1

RELEASE_BODY = """参考库的应用内更新源，供「指纹检测」面板的「检查更新」按钮读取。

- `latest.json`：更新清单（格式版本、构建时间、模型数量、大小与 SHA-256、下载地址）。
- `lite-bank.bin`：与安装包内完全一致的参考库文件。

这里只发布数据文件，不发布 APK；应用版本请看 [Releases]({release_url})。
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


def read_header(blob):
    """Read the asset's own header so the manifest cannot drift from the file."""
    if blob[: len(MAGIC)] != MAGIC:
        raise SystemExit(f"不是指纹库资产（magic={blob[:8]!r}）")
    offset = len(MAGIC)

    def u32():
        nonlocal offset
        value = struct.unpack_from("<I", blob, offset)[0]
        offset += 4
        return value

    def text():
        nonlocal offset
        length = u32()
        value = blob[offset:offset + length].decode("utf-8")
        offset += length
        return value

    return {
        "source_reference_sha256": text(),
        "built_at": text(),
        "reference_sha256": text(),
        "model_count": u32(),
    }


def app_version_code():
    """The version code the published bank requires; read from the build file."""
    with open(GRADLE, encoding="utf-8") as handle:
        text = handle.read()
    match = re.search(r"versionCode\s*=\s*([0-9_]+)", text)
    if not match:
        raise SystemExit("app/build.gradle.kts 里找不到 versionCode")
    return int(match.group(1).replace("_", ""))


def build_manifest(bank_bytes):
    header = read_header(bank_bytes)
    return {
        "formatVersion": FORMAT_VERSION,
        "builtAt": header["built_at"],
        "referenceSha256": header["reference_sha256"],
        "modelCount": header["model_count"],
        "sizeBytes": len(bank_bytes),
        "sha256": sha256(bank_bytes),
        "url": f"https://github.com/{REPO}/releases/download/{TAG}/lite-bank.bin",
        "minAppVersionCode": app_version_code(),
    }


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
        with urllib.request.urlopen(request_object, timeout=120) as response:
            body = response.read()
            return response.status, body
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def release_by_tag(token):
    status, body = request(token, "GET", f"{API}/repos/{REPO}/releases/tags/{TAG}")
    if status == 404:
        return None
    if status != 200:
        raise SystemExit(f"读取 {TAG} release 失败：HTTP {status} {body[:200]!r}")
    return json.loads(body)


def ensure_release(token):
    release = release_by_tag(token)
    html_url = f"https://github.com/{REPO}/releases"
    if release is None:
        status, body = request(
            token,
            "POST",
            f"{API}/repos/{REPO}/releases",
            {
                "tag_name": TAG,
                "name": RELEASE_NAME,
                "body": RELEASE_BODY.format(release_url=html_url),
                "prerelease": True,
                "draft": False,
            },
        )
        if status not in (200, 201):
            raise SystemExit(f"创建 {TAG} release 失败：HTTP {status} {body[:300]!r}")
        print(f"created release {TAG}")
        return json.loads(body)
    print(f"reusing release {TAG} (id {release['id']})")
    return release


def replace_asset(token, release, name, payload, content_type):
    for asset in release.get("assets", []):
        if asset["name"] == name:
            status, body = request(token, "DELETE", f"{API}/repos/{REPO}/releases/assets/{asset['id']}")
            if status not in (204, 200):
                raise SystemExit(f"删除旧资产 {name} 失败：HTTP {status} {body[:200]!r}")
            print(f"deleted old asset {name}")
    upload_url = release["upload_url"].split("{")[0] + f"?name={name}"
    status, body = request(token, "POST", upload_url, payload, content_type, raw=True)
    if status not in (200, 201):
        raise SystemExit(f"上传 {name} 失败：HTTP {status} {body[:300]!r}")
    return json.loads(body)


def download(url):
    request_object = urllib.request.Request(url)
    with urllib.request.urlopen(request_object, timeout=180) as response:
        return response.read()


def verify(release, bank_bytes, manifest_bytes):
    """Download both assets back and compare them with what we meant to publish."""
    assets = {asset["name"]: asset for asset in release["assets"]}
    for name in ("lite-bank.bin", "latest.json"):
        if name not in assets:
            raise SystemExit(f"发布后找不到资产 {name}")
    fetched_bank = download(assets["lite-bank.bin"]["browser_download_url"])
    fetched_manifest = download(assets["latest.json"]["browser_download_url"])
    if fetched_bank != bank_bytes:
        raise SystemExit("回读的 lite-bank.bin 与本地文件不一致")
    if fetched_manifest != manifest_bytes:
        raise SystemExit("回读的 latest.json 与本地文件不一致")
    print("verified: 两个资产回读均与本地逐字节一致")
    return json.loads(fetched_manifest)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bank", default=DEFAULT_BANK, help="要发布的库文件")
    parser.add_argument("--dry-run", action="store_true", help="只打印清单，不联网")
    parser.add_argument("--check", action="store_true", help="只校验线上与本地是否一致")
    args = parser.parse_args()

    with open(args.bank, "rb") as handle:
        bank_bytes = handle.read()
    manifest = build_manifest(bank_bytes)
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

    if args.dry_run:
        return 0

    token = read_token()
    if args.check:
        release = release_by_tag(token)
        if release is None:
            raise SystemExit(f"{TAG} release 不存在")
        verify(release, bank_bytes, manifest_bytes)
        return 0

    release = ensure_release(token)
    replace_asset(token, release, "lite-bank.bin", bank_bytes, "application/octet-stream")
    replace_asset(token, release, "latest.json", manifest_bytes, "application/json")
    release = release_by_tag(token)
    verify(release, bank_bytes, manifest_bytes)
    print("published", manifest["builtAt"], manifest["sha256"][:12])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
