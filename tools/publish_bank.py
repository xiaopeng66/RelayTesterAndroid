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

GitHub calls use `retry.py` to distinguish transient failures from permanent refusals.
API/download retry budgets come from BANK_RETRY_*; readback owns a bounded budget and
also retries CDN 404s. The updater's rebuild is deterministic for a fixed upstream input:
it skips unchanged assets only after both pass readback, and can recover incomplete
uploads. A rerun may resolve newer upstream data, and --force may replace identical
assets. Same-name replacement is delete then upload, with a short missing-asset window.

Usage:
    python tools/publish_bank.py                 # publish build/lm-fingerprint/lite-bank.bin
    python tools/publish_bank.py --dry-run       # print the manifest, upload nothing
    python tools/publish_bank.py --check         # verify what is published matches
    python tools/publish_bank.py --bank <path>   # publish a different package

Token: GITHUB_TOKEN, or the first line of E:/AI/Zcode/tmp/.ghtoken.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import retry
from fingerprint_package_validation import validate_package

REPO = "xiaopeng66/RelayTesterAndroid"
TAG = "bank"
RELEASE_NAME = "检测包（应用内更新源）"
API = "https://api.github.com"
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BANK = os.path.join(PROJECT, "build/lm-fingerprint/lite-bank.bin")
TOKEN_FILES = ["E:/AI/Zcode/tmp/.ghtoken"]
# The oldest app that can read a package published from this tree. It is a property of the
# *package format and the reading code*, not of whatever version the build file happens to
# be on: the daily detection-package job runs independently of the APK release, so binding
# this to the working tree's versionCode would advertise a requirement for an app that does
# not exist yet, and every installed device would be told to update the app before it may
# use a package it can already read. Raise it only when a package starts using something an
# older app cannot parse, and raise it to the version that learned to — which is 10801, the
# first app that reads the v2 ranker (LMFPA004). Until that APK is out, publishing a 004
# package would hand every installed device a manifest its app refuses as "too new"; the
# order is therefore: release the app, then let the pipeline publish.
MIN_APP_VERSION_CODE = 10_801


class PublishedAssetMismatch(SystemExit):
    """Read-back proved an asset missing or different; transport failures are not this."""


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
    """Read metadata only after the entire package passes the Android shape contract."""
    return validate_package(blob)


def app_version_code():
    """The version code written into the manifest's `minAppVersionCode`."""
    return MIN_APP_VERSION_CODE


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
    if token:
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
        if not retry.transient_status(status, headers) or index + 1 >= policy.attempts:
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
            raise PublishedAssetMismatch(f"发布后找不到资产 {name}")
    bank_url = assets["lite-bank.bin"]["browser_download_url"]
    manifest_url = assets["latest.json"]["browser_download_url"]
    seen = None
    last_error = None
    policy = retry.Policy(attempts=attempts, base_delay=wait_seconds, max_delay=wait_seconds, jitter=0)
    for attempt in range(attempts):
        try:
            fetched_bank = download(bank_url, cache_bust=True, attempts=1)
            fetched_manifest = download(manifest_url, cache_bust=True, attempts=1)
        except Exception as error:  # noqa: BLE001 - classify before retrying or granting repair
            missing = isinstance(error, urllib.error.HTTPError) and error.code == 404
            if not missing and not retry.transient_error(error):
                raise SystemExit(f"回读校验未能完成：{error}") from error
            last_error = error
            print(f"回读失败（第 {attempt + 1}/{attempts} 次）：{error}")
            if attempt + 1 < attempts:
                headers = error.headers if isinstance(error, urllib.error.HTTPError) else None
                retry.delay_for_status(policy, attempt, headers)
            continue
        last_error = None
        if fetched_bank == bank_bytes and fetched_manifest == manifest_bytes:
            print("verified: 两个资产回读均与本地逐字节一致")
            return json.loads(fetched_manifest)
        seen = (len(fetched_bank), len(fetched_manifest))
        if attempt + 1 < attempts:
            time.sleep(wait_seconds)
    if last_error is not None:
        if isinstance(last_error, urllib.error.HTTPError) and last_error.code == 404:
            raise PublishedAssetMismatch(f"回读资产不存在（重试 {attempts} 次）：{last_error}")
        # A stale CDN response followed by a transport failure still leaves the current
        # state unknown. Never turn that uncertainty into permission to delete assets.
        raise SystemExit(f"回读校验未能完成（重试 {attempts} 次）：{last_error}")
    if seen is None:
        raise SystemExit(f"回读校验未能完成（重试 {attempts} 次）")
    raise PublishedAssetMismatch(
        f"回读校验失败（重试 {attempts} 次）：本地 {len(bank_bytes)}/{len(manifest_bytes)} 字节，"
        f"线上 {seen[0]}/{seen[1]} 字节",
    )


def build_time(value):
    """Compare actual instants, never ISO strings with different UTC offsets."""
    try:
        instant = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if instant.utcoffset() is None:
            raise ValueError("timezone required")
        return instant.astimezone(datetime.timezone.utc)
    except (AttributeError, TypeError, ValueError, OverflowError) as error:
        raise SystemExit(f"无法确认检测包构建时间 {value!r}（需要带时区的 ISO 时间），停止发布") from error


def read_published_manifest(release):
    """Return a validated surviving manifest, or None for absent/invalid asset bytes."""
    asset = next((item for item in release.get("assets", []) if item["name"] == "latest.json"), None)
    if asset is None or asset.get("state") == "starter":
        return None
    try:
        raw = download(asset["browser_download_url"], cache_bust=True)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise SystemExit(f"无法读取线上检测包清单，停止发布：{error}") from error
    except Exception as error:
        raise SystemExit(f"无法读取线上检测包清单，停止发布：{error}") from error
    try:
        current = json.loads(raw)
        valid = (
            isinstance(current, dict)
            and all(type(current.get(key)) is int and current[key] > 0
                    for key in ("modelCount", "sizeBytes"))
            and isinstance(current.get("sha256"), str)
            and re.fullmatch(r"[0-9a-fA-F]{64}", current["sha256"]) is not None
            and isinstance(current.get("builtAt"), str)
        )
    except (ValueError, TypeError):
        valid = False
    if not valid:
        return None
    build_time(current["builtAt"])
    return current


def published_identity(token):
    """Read the bank's identity, recovering a broken manifest only from the actual bank.

    A missing/starter manifest is a normal interrupted upload. It grants no permission
    to replace an unknown bank: download and validate the existing payload first. A
    valid manifest remains the fence when its payload was the interrupted upload.
    """
    release = release_by_tag(token)
    if release is None:
        return None, None
    current = read_published_manifest(release)
    if current is not None:
        return current, release
    assets = {asset["name"]: asset for asset in release.get("assets", [])}
    bank_asset = assets.get("lite-bank.bin")
    if bank_asset is None:
        if not assets:
            return None, release  # Empty release, before its first asset upload.
        raise SystemExit("线上检测包清单损坏且没有可核对的包，停止发布；请先核对已有发布")
    if bank_asset.get("state") == "starter":
        raise SystemExit("线上清单和检测包均未上传完整，无法确认已有版本，停止发布")
    try:
        existing = download(bank_asset["browser_download_url"], cache_bust=True)
    except Exception as error:
        raise SystemExit(f"清单损坏且无法核对线上检测包，停止发布：{error}") from error
    current = build_manifest(existing)
    build_time(current["builtAt"])
    print("线上清单缺失或损坏，使用已回读并验证的检测包确认版本")
    return current, release


def verify_not_older(candidate, current):
    candidate_time = build_time(candidate["builtAt"])
    if current is not None and candidate_time < build_time(current["builtAt"]):
        raise SystemExit(
            f"本次检测包构建于 {candidate['builtAt']}，早于线上 {current['builtAt']}，停止发布；"
            "--force 仅重传相同内容，不允许版本倒退"
        )


def verify_payload_not_older(token, candidate, current, release):
    """Return (checked release snapshot, payload matches candidate).

    A newer payload can survive a crash before its manifest is replaced. A CDN 404
    cannot prove it absent; only refreshed release metadata plus a surviving valid
    manifest can authorize repair of an absent/empty-starter payload.
    """
    if release is None:
        return release, False
    asset = next((item for item in release.get("assets", []) if item["name"] == "lite-bank.bin"), None)
    if asset is None:
        return release, False
    if asset.get("state") == "starter":
        if type(asset.get("size")) is not int or asset["size"] != 0 or current is None:
            raise SystemExit("检测包 starter 状态不明确或没有有效清单，停止发布")
        return release, False
    try:
        existing = download(asset["browser_download_url"], cache_bust=True)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise SystemExit(f"无法确认线上检测包版本，停止发布：{error}") from error
        refreshed = release_by_tag(token)
        if refreshed is None:
            raise SystemExit("检测包下载 404 且发行版状态已变化，停止发布") from error
        remaining = next((item for item in refreshed.get("assets", []) if item["name"] == "lite-bank.bin"), None)
        if remaining is not None and not (
            remaining.get("state") == "starter"
            and type(remaining.get("size")) is int and remaining["size"] == 0
        ):
            raise SystemExit("检测包下载 404，但 API 仍列出已上传或状态不明的资产，停止发布") from error
        surviving = read_published_manifest(refreshed)
        if surviving is None:
            raise SystemExit("检测包缺失且没有幸存的有效清单，停止发布") from error
        verify_not_older(candidate, surviving)
        return refreshed, False
    except Exception as error:
        raise SystemExit(f"无法确认线上检测包版本，停止发布：{error}") from error
    try:
        actual = build_manifest(existing)
    except SystemExit:
        # A valid manifest for exactly this candidate can repair its corrupt payload.
        # Different candidate data must not replace a payload of unprovable age.
        if current is not None and current["sha256"].lower() == candidate["sha256"].lower():
            return release, False
        raise
    verify_not_older(candidate, actual)
    matches = actual["sha256"] == candidate["sha256"] and actual["sizeBytes"] == candidate["sizeBytes"]
    if not matches:
        # `current` may be inferred from the payload, not a surviving manifest.
        # Deleting an uncommitted newer payload would erase its only version fence
        # if the next upload fails. Restore its manifest with identical bytes first.
        surviving = read_published_manifest(release)
        verify_not_older(candidate, surviving)
        if surviving is None or build_time(actual["builtAt"]) > build_time(surviving["builtAt"]):
            raise SystemExit(
                f"线上检测包构建于 {actual['builtAt']}，但 latest.json 缺失或早于该检测包，停止替换不同内容；"
                "请先使用与线上检测包逐字节一致的文件恢复 latest.json；--force 不能绕过此保护"
            )
    return release, matches


def verify_workflow_head(token):
    """An old Actions rerun must not publish using a superseded builder."""
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    revision = os.environ.get("GITHUB_SHA", "")
    ref = os.environ.get("GITHUB_REF", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", revision) or not ref.startswith("refs/heads/") or repo != REPO:
        raise SystemExit("发布任务缺少可信的仓库、分支或提交信息，停止发布")
    branch = urllib.parse.quote(ref[len("refs/heads/"):], safe="")
    status, body = request(token, "GET", f"{API}/repos/{repo}/commits/{branch}")
    try:
        head = json.loads(body)["sha"] if status == 200 else None
    except (ValueError, KeyError, TypeError):
        head = None
    if head is None:
        raise SystemExit(f"无法确认发布分支的最新提交（HTTP {status}），停止发布")
    if head != revision:
        raise SystemExit("本次任务的提交已不是发布分支最新提交，停止旧任务覆盖线上检测包；请启动新任务")


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

    current, release = published_identity(token)
    verify_not_older(manifest, current)
    release, payload_matches = verify_payload_not_older(token, manifest, current, release)
    verify_workflow_head(token)
    if release is None:
        release = ensure_release(token)
    if not payload_matches:
        replace_asset(token, release, "lite-bank.bin", bank_bytes, "application/octet-stream")
    replace_asset(token, release, "latest.json", manifest_bytes, "application/json")
    release = release_by_tag(token)
    verify(release, bank_bytes, manifest_bytes)
    print("published", manifest["builtAt"], manifest["sha256"][:12])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
