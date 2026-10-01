#!/usr/bin/env python3
"""Keep the published fingerprint detection package in step with upstream lm-detector.

One command does the whole round trip:

    1. ask GitHub which revision of `data/shared_detector.json` upstream is on,
    2. download that revision's `shared_detector.json` + `unified_bank.json` (cached),
    3. rebuild the package with `build_fingerprint_asset.py`,
    4. compare it with the manifest already published on the `bank` release,
    5. publish it — and only if the digest actually changed.

Step 5 is where the app picks the new package up: the panel's "检查更新" button reads
that manifest, and a device with no package checks it once by itself on first entry.
Nothing is pushed to the device, so "tell me when there is a new package" is answered
by opening the panel, not by a notification.

Usage:
    python tools/update_fingerprint_package.py                 # check, rebuild, publish if changed
    python tools/update_fingerprint_package.py --dry-run       # do everything but the upload
    python tools/update_fingerprint_package.py --data-dir DIR   # build from a local clone instead
    python tools/update_fingerprint_package.py --force          # publish even if unchanged
    python tools/update_fingerprint_package.py --ref-bits 8     # smaller package, see below
    python tools/update_fingerprint_package.py --revision SHA   # skip the API, use this revision

Scheduling: this runs itself. `.github/workflows/update-detection-package.yml` calls it on a
daily cron and on manual dispatch, using the run's own scoped token — nothing to configure
locally and no long-lived credential anywhere. Runs that find nothing new are a no-op: the
build is deterministic (its `builtAt` comes from upstream's data, not from the clock), so the
rebuilt digest is compared with the published manifest and the upload is skipped.

Network note: raw.githubusercontent.com is not always reachable from every network.
Set HTTPS_PROXY if you need a proxy, or pass --data-dir with a clone of upstream.

Token: GITHUB_TOKEN, or the first line of E:/AI/Zcode/tmp/.ghtoken. It is only needed
to publish and to read the release; a public upstream needs no token, but one is used
when present to stay clear of the anonymous rate limit.
"""

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

import publish_bank

TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(TOOLS)
BUILDER = os.path.join(TOOLS, "build_fingerprint_asset.py")
UPSTREAM = "https://github.com/Ikaleio/lm-detector"
UPSTREAM_API = "https://api.github.com/repos/Ikaleio/lm-detector"
RAW = "https://raw.githubusercontent.com/Ikaleio/lm-detector"
#: The two files the builder needs, and the one whose revision defines "upstream moved".
DATA_FILES = ("shared_detector.json", "unified_bank.json")
TRACKED_FILE = "shared_detector.json"
DEFAULT_OUT = os.path.join(PROJECT, "build/lm-fingerprint/lite-bank.bin")
DEFAULT_CACHE = os.path.join(PROJECT, "build/lm-fingerprint/upstream")


def api_get(url):
    """GET a GitHub API URL, using the token when one is available."""
    request = urllib.request.Request(url)
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    try:
        token = publish_bank.read_token()
    except SystemExit:
        token = None
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        if error.code in (403, 429):
            raise SystemExit(
                f"GitHub API 限流或拒绝（HTTP {error.code}）：写入 token 后重试，"
                "或改用 --data-dir 指向本地上游副本",
            )
        raise SystemExit(f"读取 {url} 失败：HTTP {error.code} {error.read()[:200]!r}")


def upstream_revision():
    """The newest revision of the tracked data file: ({sha}, {date})."""
    url = f"{UPSTREAM_API}/commits?path=data/{TRACKED_FILE}&per_page=1"
    commits = api_get(url)
    if not commits:
        raise SystemExit(f"上游没有 data/{TRACKED_FILE} 的提交记录")
    commit = commits[0]
    return commit["sha"], commit["commit"]["committer"]["date"]


def download(url, destination):
    """Stream one upstream file to disk so a 32 MB JSON never sits in memory twice."""
    request = urllib.request.Request(url)
    partial = destination + ".part"
    try:
        with urllib.request.urlopen(request, timeout=600) as response, open(partial, "wb") as handle:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                handle.write(chunk)
    except (urllib.error.URLError, OSError) as error:
        if os.path.exists(partial):
            os.remove(partial)
        raise SystemExit(
            f"下载 {url} 失败：{error}\n"
            "该域名在部分网络不可达：设置 HTTPS_PROXY，或用 --data-dir 指向上游 clone。",
        )
    os.replace(partial, destination)


def fetch_data(cache_dir, revision, refresh):
    """Make sure the cache holds [revision]'s data files; return the directory."""
    stamp_path = os.path.join(cache_dir, "revision.json")
    cached = None
    if os.path.isfile(stamp_path):
        with open(stamp_path, encoding="utf-8") as handle:
            cached = json.load(handle).get("sha")
    present = all(os.path.isfile(os.path.join(cache_dir, name)) for name in DATA_FILES)
    if cached == revision and present and not refresh:
        print(f"upstream data cached at {revision[:12]}")
        return cache_dir
    os.makedirs(cache_dir, exist_ok=True)
    for name in DATA_FILES:
        target = os.path.join(cache_dir, name)
        print(f"downloading data/{name} @ {revision[:12]}")
        download(f"{RAW}/{revision}/data/{name}", target)
    with open(stamp_path, "w", encoding="utf-8") as handle:
        json.dump({"sha": revision, "source": f"{UPSTREAM}/tree/{revision}/data"}, handle, indent=2)
        handle.write("\n")
    return cache_dir


def build(data_dir, out_path, args):
    command = [sys.executable, BUILDER, data_dir, out_path, "--ref-bits", str(args.ref_bits)]
    if args.models:
        command += ["--models", str(args.models)]
    if args.pick:
        command += ["--pick", args.pick]
    if args.emit_detector_json:
        command += ["--emit-detector-json", args.emit_detector_json]
    if args.emit_bank_json:
        command += ["--emit-bank-json", args.emit_bank_json]
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    print("building: " + " ".join(command[1:]))
    result = subprocess.run(command, cwd=PROJECT)
    if result.returncode != 0:
        raise SystemExit(f"构建失败（退出码 {result.returncode}）")


def published_manifest(token):
    """The manifest currently on the `bank` release, or None when nothing is up yet."""
    release = publish_bank.release_by_tag(token)
    if release is None:
        return None, None
    for asset in release.get("assets", []):
        if asset["name"] == "latest.json":
            body = publish_bank.download(asset["browser_download_url"])
            return json.loads(body), release
    return None, release


def describe(manifest):
    if manifest is None:
        return "（线上还没有发布过）"
    return (
        f"{manifest['modelCount']} 个模型 / {manifest['sizeBytes'] / 1024 / 1024:.2f} MB / "
        f"构建于 {manifest['builtAt']} / {manifest['sha256'][:12]}"
    )


def main():
    parser = argparse.ArgumentParser(description="上游变了就重新构建并发布检测包")
    parser.add_argument("--data-dir", help="上游 data/ 目录（给了就不联网下载上游）")
    parser.add_argument("--cache-dir", default=DEFAULT_CACHE, help="上游数据缓存目录")
    parser.add_argument("--out", default=DEFAULT_OUT, help="构建产物路径")
    parser.add_argument("--ref-bits", type=int, default=16, choices=(8, 16), help="参考张量位宽")
    parser.add_argument("--models", type=int, help="只构建前 N 个模型（用于夹具）")
    parser.add_argument("--pick", help="只构建指定模型 id（逗号分隔，用于夹具）")
    parser.add_argument("--emit-detector-json", help="同时导出未量化的检测器 JSON（用于黄金向量）")
    parser.add_argument("--emit-bank-json", help="同时导出未量化的库 JSON")
    parser.add_argument("--refresh", action="store_true", help="忽略缓存的上游版本，强制重新下载")
    parser.add_argument(
        "--revision",
        help="直接指定上游修订，不问 API（CI 用 `git ls-remote <upstream> HEAD` 取值，"
             "省掉一次可能被限流的第三方 API 调用）",
    )
    parser.add_argument("--dry-run", action="store_true", help="构建并比较，不上传")
    parser.add_argument("--force", action="store_true", help="即使与线上一致也重新上传")
    args = parser.parse_args()

    if args.data_dir:
        data_dir = args.data_dir
        revision, revision_date = "local", "（本地副本）"
        if not os.path.isfile(os.path.join(data_dir, TRACKED_FILE)):
            raise SystemExit(f"{data_dir} 里找不到 {TRACKED_FILE}")
        print(f"using local upstream data: {data_dir}")
    else:
        if args.revision:
            revision = args.revision
            print(f"upstream revision: {revision[:12]}（由调用方给定）")
        else:
            revision, revision_date = upstream_revision()
            print(f"upstream {TRACKED_FILE} @ {revision[:12]} ({revision_date})")
        data_dir = fetch_data(args.cache_dir, revision, args.refresh)

    build(data_dir, args.out, args)
    with open(args.out, "rb") as handle:
        built = handle.read()
    header = publish_bank.read_header(built)
    built_manifest = publish_bank.build_manifest(built)
    print(f"built: {describe(built_manifest)}")

    if args.dry_run:
        print(json.dumps(built_manifest, ensure_ascii=False, indent=2))
        return 0

    token = publish_bank.read_token()
    current, release = published_manifest(token)
    print(f"published now: {describe(current)}")
    if current is not None and current["sha256"] == built_manifest["sha256"] and not args.force:
        print(
            f"无需发布：本地构建与线上逐字节一致（上游 {revision[:12]}，"
            f"{built_manifest['modelCount']} 个模型）",
        )
        return 0

    if current is not None:
        delta = built_manifest["sizeBytes"] - current["sizeBytes"]
        print(
            f"发布更新：{current['modelCount']} → {built_manifest['modelCount']} 个模型，"
            f"体积 {delta:+d} 字节，构建于 {built_manifest['builtAt']}",
        )
    else:
        print(f"首次发布：{header['built_at']}")

    manifest_bytes = (
        json.dumps(built_manifest, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")
    release = publish_bank.ensure_release(token)
    publish_bank.replace_asset(token, release, "lite-bank.bin", built, "application/octet-stream")
    publish_bank.replace_asset(token, release, "latest.json", manifest_bytes, "application/json")
    release = publish_bank.release_by_tag(token)
    publish_bank.verify(release, built, manifest_bytes)

    print(
        "\n已发布。应用侧取用方式：面板「检查更新」→「下载/更新检测包」；"
        "未安装检测包的设备在首次进入面板时自动查一次清单。",
    )
    print(f"上游版本：{UPSTREAM}/tree/{revision}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
