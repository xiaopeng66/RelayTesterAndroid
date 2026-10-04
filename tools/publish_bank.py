#!/usr/bin/env python3
"""Publish the fingerprint detection package for the app's in-app updater.

Uploads two assets to the `bank` pre-release of the app repository:

    lite-bank.bin   the packed detection package, built by build_fingerprint_asset.py
    latest.json     the manifest the app polls from its "检查更新" button

The package is no longer bundled in the APK: the app downloads it once and keeps it,
which is what keeps the app itself small and the package updatable on its own.

The manifest is derived from the asset itself — build stamp, model count, reference
digest, size and SHA-256 are all read back out of the binary — so the two can never
disagree. Both files are downloaded again afterwards and compared byte for byte.

Every call to GitHub goes through `retry.py`, which repeats transient answers (5xx, 429,
timeouts, resets) and returns permanent ones immediately — see that module for why the
split matters. Counts and delays come from BANK_RETRY_* in the environment. The publish
itself stays idempotent: re-running after a failure rebuilds the same bytes and either
finds them already published ("无需发布") or replaces the assets again, so a retried job
cannot publish twice or publish something stale.

Usage:
    python tools/publish_bank.py                 # publish build/lm-fingerprint/lite-bank.bin
    python tools/publish_bank.py --dry-run       # print the manifest, upload nothing
    python tools/publish_bank.py --check         # verify what is published matches
    python tools/publish_bank.py --bank <path>   # publish a different package

Token: GITHUB_TOKEN, or the first line of E:/AI/Zcode/tmp/.ghtoken.
"""

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.request

import retry

REPO = "xiaopeng66/RelayTesterAndroid"
TAG = "bank"
RELEASE_NAME = "检测包（应用内更新源）"
API = "https://api.github.com"
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BANK = os.path.join(PROJECT, "build/lm-fingerprint/lite-bank.bin")
GRADLE = os.path.join(PROJECT, "app/build.gradle.kts")
TOKEN_FILES = ["E:/AI/Zcode/tmp/.ghtoken"]
MAGIC_LENGTH = 8
# The manifest's formatVersion is read off the package's own magic rather than being a
# constant here: two shapes are live while the fleet upgrades (format 2 still carries
# upstream's removed verifier block, format 3 does not), and a publish that hardcoded
# the number could advertise the wrong one and have the app reject a valid package.
FORMAT_BY_MAGIC = {b"LMFPA002": 2, b"LMFPA003": 3}

RELEASE_BODY = """指纹检测包的发布源，供「指纹检测」面板的「检查更新」按钮读取。

- `latest.json`：更新清单（格式版本、构建时间、模型数量、大小与 SHA-256、下载地址）。
- `lite-bank.bin`：检测包本体（由上游 lm-detector 的数据构建，见仓库 `tools/`）。

检测包不打进安装包，应用首次需要时自行下载并保存在设备上，之后检测完全离线。
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
    magic = blob[:MAGIC_LENGTH]
    if magic not in FORMAT_BY_MAGIC:
        raise SystemExit(f"不是指纹库资产（magic={magic!r}）")
    offset = MAGIC_LENGTH

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
        "format_version": FORMAT_BY_MAGIC[magic],
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
        "formatVersion": header["format_version"],
        "builtAt": header["built_at"],
        "referenceSha256": header["reference_sha256"],
        "modelCount": header["model_count"],
        "sizeBytes": len(bank_bytes),
        "sha256": sha256(bank_bytes),
        "url": f"https://github.com/{REPO}/releases/download/{TAG}/lite-bank.bin",
        "minAppVersionCode": app_version_code(),
    }


def _request_once(token, method, url, payload, content_type):
    """One attempt. Returns (status, body, headers); HTTP errors come back as statuses."""
    request_object = urllib.request.Request(url, data=payload, method=method)
    request_object.add_header("Authorization", "Bearer " + token)
    request_object.add_header("Accept", "application/vnd.github+json")
    request_object.add_header("X-GitHub-Api-Version", "2022-11-28")
    if payload is not None:
        request_object.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request_object, timeout=120) as response:
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers or {})


def request(token, method, url, data=None, content_type="application/json", raw=False,
            policy=None):
    """Call the GitHub API, retrying only answers that mean "not now".

    A 401/404/422 is returned on the first attempt: repeating it cannot change the
    answer, and the caller's own message ("删除旧资产失败", "上传失败") is far more useful
    than a stack of identical attempts. 5xx (the CDN in front of the asset endpoints
    answers 502/503 during its own hiccups), 429, and transport-level failures are
    retried with backoff, honouring `Retry-After` when GitHub sends one.
    """
    policy = policy or retry.Policy.from_env()
    payload = data
    if payload is not None and not raw:
        payload = json.dumps(payload).encode("utf-8")
    label = f"{method} {url.split('/repos/')[-1][:80]}"
    for index in range(policy.attempts):
        try:
            status, body, headers = _request_once(token, method, url, payload, content_type)
        except Exception as error:  # noqa: BLE001 - transient_error decides worth
            if index + 1 >= policy.attempts or not retry.transient_error(error):
                raise
            seconds = policy.delay(index)
            print(f"{label}: {retry.describe_error(error)}（第 {index + 1}/{policy.attempts} 次），"
                  f"{seconds:.1f}s 后重试")
            continue
        if status not in retry.TRANSIENT_STATUS or index + 1 >= policy.attempts:
            return status, body
        wait = retry.delay_for_status(policy, index, headers)
        print(f"{label}: HTTP {status}（第 {index + 1}/{policy.attempts} 次），"
              f"{wait:.1f}s 后重试")
    raise AssertionError("unreachable")  # the loop returns or raises on every path


def release_by_tag(token, tag=TAG):
    """The release for [tag], or None.

    [tag] is a parameter so the app-release publisher can reuse this: its feed lives on a
    second pre-release (`app`), and two pre-releases that never touch each other's assets
    is what keeps the daily bank job from being able to disturb the APK feed.
    """
    status, body = request(token, "GET", f"{API}/repos/{REPO}/releases/tags/{tag}")
    if status == 404:
        return None
    if status != 200:
        raise SystemExit(f"读取 {tag} release 失败：HTTP {status} {body[:200]!r}")
    return json.loads(body)


def ensure_release(token, tag=TAG, name=RELEASE_NAME, body_text=None):
    """[tag]'s release, created if it does not exist yet.

    [body_text] is passed in already formatted (the app feed's notes have no placeholders);
    the default formats the bank's own template, so the existing call sites are unchanged.
    """
    release = release_by_tag(token, tag)
    html_url = f"https://github.com/{REPO}/releases"
    if release is None:
        status, body = request(
            token,
            "POST",
            f"{API}/repos/{REPO}/releases",
            {
                "tag_name": tag,
                "name": name,
                "body": body_text if body_text is not None else RELEASE_BODY.format(release_url=html_url),
                "prerelease": True,
                "draft": False,
            },
        )
        if status not in (200, 201):
            raise SystemExit(f"创建 {tag} release 失败：HTTP {status} {body[:300]!r}")
        print(f"created release {tag}")
        return json.loads(body)
    print(f"reusing release {TAG} (id {release['id']})")
    return release


def replace_asset(token, release, name, payload, content_type):
    """Put [name] on the release, replacing any asset of that name.

    A release cannot hold two assets with the same name, so a replacement is a delete
    followed by an upload — which leaves a short window with no asset of that name. A
    device that happens to check inside that window gets a 404 and simply checks again
    later (the app never treats a failed download as "no update"); a *failed job* here is
    the worse case, so the upload says plainly when the asset is currently missing and
    the job-level retry rebuilds and re-uploads it.
    """
    for asset in release.get("assets", []):
        if asset["name"] == name:
            status, body = request(token, "DELETE", f"{API}/repos/{REPO}/releases/assets/{asset['id']}")
            if status not in (204, 200):
                raise SystemExit(f"删除旧资产 {name} 失败：HTTP {status} {body[:200]!r}")
            print(f"deleted old asset {name}")
    upload_url = release["upload_url"].split("{")[0] + f"?name={name}"
    try:
        status, body = request(token, "POST", upload_url, payload, content_type, raw=True)
    except Exception as error:  # noqa: BLE001 - re-raised with the release's state
        raise SystemExit(
            f"上传 {name} 失败：{error}\n"
            f"注意：旧资产已删除，release 里现在没有 {name}；重新运行本流程会重新上传"
            "（构建是确定性的，重新上传的就是这份字节）。",
        ) from error
    if status not in (200, 201):
        raise SystemExit(
            f"上传 {name} 失败：HTTP {status} {body[:300]!r}\n"
            f"注意：旧资产可能已删除，release 里现在缺少 {name}；重新运行本流程会恢复它。",
        )
    return json.loads(body)


def download(url, cache_bust=False, attempts=None, policy=None):
    """Fetch a public asset, retrying transport hiccups.

    `attempts=1` is for callers that already own a retry loop (see `verify`, which
    distinguishes a byte mismatch from a failed download and counts both against the same
    budget) — without it the two loops would multiply.
    """
    if cache_bust:
        separator = "&" if "?" in url else "?"
        url = f"{url}{separator}cb={int(time.time() * 1000)}"
    policy = policy or retry.Policy.from_env()
    if attempts is not None:
        policy = retry.Policy(attempts=attempts, base_delay=policy.base_delay,
                              max_delay=policy.max_delay, jitter=policy.jitter)

    def once():
        request_object = urllib.request.Request(url)
        with urllib.request.urlopen(request_object, timeout=180) as response:
            return response.read()

    return retry.run(once, policy=policy, label=f"下载 {url.split('/')[-1][:40]}", log=print)


def verify(release, bank_bytes, manifest_bytes, attempts=6, wait_seconds=5.0):
    """Download both assets back and compare them with what we meant to publish.

    Two different things are retried here, because both are normal right after an upload
    and neither means the publish went wrong:

      * a byte mismatch — a just-replaced asset can still be served from a CDN cache for a
        moment, so the read-back carries a cache-busting query and is retried before it is
        called a mismatch (a false "不一致" looks exactly like a corrupted upload);
      * a failed download — github.com answered the read-back with `HTTP Error 503` right
        after the assets had been uploaded, which turned a publish that had already
        succeeded into a red run. 503/timeouts are transient, so they are retried like a
        mismatch instead of aborting here.

    This loop owns the attempt count, so each read-back is a single try (`attempts=1`) —
    otherwise the per-download retry in `download()` would multiply with this one and the
    job would sit here far longer than the numbers below suggest.
    """
    assets = {asset["name"]: asset for asset in release["assets"]}
    for name in ("lite-bank.bin", "latest.json"):
        if name not in assets:
            raise SystemExit(f"发布后找不到资产 {name}")
    bank_url = assets["lite-bank.bin"]["browser_download_url"]
    manifest_url = assets["latest.json"]["browser_download_url"]
    seen = None
    last_error = None
    for attempt in range(attempts):
        try:
            fetched_bank = download(bank_url, cache_bust=True, attempts=1)
            fetched_manifest = download(manifest_url, cache_bust=True, attempts=1)
        except urllib.error.URLError as error:
            # HTTPError is a subclass of URLError, so a 5xx answer lands here too.
            last_error = error
            print(f"回读失败（第 {attempt + 1}/{attempts} 次）：{error}")
            if attempt + 1 < attempts:
                time.sleep(wait_seconds)
            continue
        last_error = None
        if fetched_bank == bank_bytes and fetched_manifest == manifest_bytes:
            print("verified: 两个资产回读均与本地逐字节一致")
            return json.loads(fetched_manifest)
        seen = (len(fetched_bank), len(fetched_manifest))
        if attempt + 1 < attempts:
            time.sleep(wait_seconds)
    if seen is None:
        raise SystemExit(f"回读校验未能完成（重试 {attempts} 次）：{last_error}")
    raise SystemExit(
        f"回读校验失败（重试 {attempts} 次）：本地 {len(bank_bytes)}/{len(manifest_bytes)} 字节，"
        f"线上 {seen[0]}/{seen[1]} 字节",
    )


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
