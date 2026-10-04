#!/usr/bin/env python3
"""Re-run the failed jobs of the publishing workflows, a bounded number of times.

`update_fingerprint_package.py` already retries the individual calls that fail *inside*
a step (see `retry.py`). This tool covers everything a retry inside a step cannot: a job
killed or timed out by the runner, a `git clone` that never got off the ground, a step
that died on an unexpected exception, or a GitHub-side outage in the middle of the run.
Those leave a red run that nobody is watching at 03:17, and the next scheduled run is 24
hours away.

What it does, in one sentence: it asks the Actions API for recent runs of the publish
workflow, and for the ones that ended red it calls `rerun-failed-jobs`, which starts the
failed jobs over from their first step.

Why that cannot disturb a publish:

  * it never runs the workflow itself — it only re-runs a job GitHub already has, from the
    same commit and with the same inputs. It has no `contents: write`, so it cannot touch
    the release or its assets;
  * the publish is idempotent: it rebuilds deterministically and compares digests, so a
    re-run of a job whose upload already happened prints "无需发布" instead of publishing
    twice, and a re-run after a *half-finished* publish re-uploads the missing asset;
  * the publish workflow's own `concurrency` group (`cancel-in-progress: false`) makes two
    runs queue rather than overlap, so a re-run can never race a fresh publish;
  * the work is bounded — `--max-attempts` (default 3) caps how often one run may be
    retried, and `--window-hours` (default 24) stops old failures from being resurrected.
    A run that keeps failing is a real problem and is left red on purpose: retrying it
    forever would only hide it;
  * only `failure`/`timed_out` runs are picked. `cancelled` is somebody's decision,
    `startup_failure` cannot be retried at all (the workflow file itself was rejected),
    and `action_required` is waiting for a human approval;
  * a refused re-run (403/404/409/422 — already running, not retryable, …) is printed and
    skipped, never turned into a red run of its own. A watchdog that fails loudly would
    just add noise to the thing it is watching.

Usage:
    python3 tools/retry_failed_runs.py --dry-run          # list what it would re-run
    python3 tools/retry_failed_runs.py                    # actually re-run
    python3 tools/retry_failed_runs.py --max-attempts 2 --window-hours 6
    python3 tools/retry_failed_runs.py --workflow update-detection-package.yml

Token: GITHUB_TOKEN (the workflow passes the run's own token), or the first line of
E:/AI/Zcode/tmp/.ghtoken when run locally. Re-running needs `actions: write`.
"""

import argparse
import datetime
import json
import os
import sys

import publish_bank
import retry

API = publish_bank.API
DEFAULT_REPO = publish_bank.REPO
#: The workflows this watchdog watches. Deliberately short and explicit: a name typo
#: should mean "nothing happens", never "some other workflow gets re-run".
DEFAULT_WORKFLOWS = ("update-detection-package.yml",)
#: Conclusions worth another attempt. See the module docstring for the rest.
RETRYABLE_CONCLUSIONS = ("failure", "timed_out")


def parse_time(text):
    """Parse GitHub's `created_at` (`2026-10-04T03:17:11Z`) into a naive UTC datetime."""
    if not text:
        return None
    try:
        return datetime.datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return None


def select(runs, max_attempts, window_hours, now=None):
    """Split [runs] into (worth retrying, skipped with a reason).

    Pure: no network, no clock of its own — the whole decision is testable offline. Runs
    come in newest-first, which is what the API returns.

    Two rules beyond the conclusion filter:

      * a run that already has a *later successful* run is skipped. Whatever went wrong
        was overtaken by events (a manual dispatch, or the next schedule); re-running the
        old one would spend a job on work that is already done;
      * the newest failures are retried first, and attempts are counted from the run's own
        `run_attempt`, so a re-run does not reset the budget.
    """
    now = now or datetime.datetime.utcnow()
    cutoff = now - datetime.timedelta(hours=window_hours)
    successes = [
        parse_time(run.get("created_at"))
        for run in runs
        if run.get("conclusion") == "success"
    ]
    successes = [when for when in successes if when is not None]
    newest_success = max(successes) if successes else None

    retryable = []
    skipped = []
    for run in runs:
        identifier = run.get("id")
        created = parse_time(run.get("created_at"))
        conclusion = run.get("conclusion") or run.get("status") or "?"
        attempt = int(run.get("run_attempt") or 1)
        if conclusion not in RETRYABLE_CONCLUSIONS:
            skipped.append((identifier, conclusion, "结论不是失败（不重跑）"))
            continue
        if created is None or created < cutoff:
            skipped.append((identifier, conclusion, f"超出 {window_hours} 小时窗口"))
            continue
        if attempt >= max_attempts:
            skipped.append((identifier, conclusion,
                            f"已重跑到上限（attempt {attempt}/{max_attempts}）"))
            continue
        if newest_success is not None and created < newest_success:
            skipped.append((identifier, conclusion, "已有更新的成功运行，无需重跑"))
            continue
        retryable.append(run)
    return retryable, skipped


def list_runs(token, repo, workflow, per_page=50):
    url = f"{API}/repos/{repo}/actions/workflows/{workflow}/runs?per_page={per_page}"
    status, body = publish_bank.request(token, "GET", url)
    if status == 404:
        raise SystemExit(f"找不到 workflow {workflow}（分支上还没有这个文件？）")
    if status != 200:
        raise SystemExit(f"读取 {workflow} 的运行历史失败：HTTP {status} {body[:200]!r}")
    return json.loads(body).get("workflow_runs", [])


def rerun(token, repo, run_id):
    """Ask GitHub to re-run the failed jobs of [run_id]. Returns (ok, message)."""
    url = f"{API}/repos/{repo}/actions/runs/{run_id}/rerun-failed-jobs"
    status, body = publish_bank.request(token, "POST", url)
    if status in (201, 202):
        return True, "已重跑失败的任务"
    text = body.decode("utf-8", "replace")[:200] if isinstance(body, bytes) else str(body)[:200]
    return False, f"重跑被拒绝：HTTP {status} {text}"


def main():
    parser = argparse.ArgumentParser(description="失败的任务自动重跑（有上限、不碰发布）")
    parser.add_argument("--workflow", action="append", dest="workflows",
                        help=f"要盯的 workflow 文件名，可重复（默认 {', '.join(DEFAULT_WORKFLOWS)}）")
    parser.add_argument("--max-attempts", type=int, default=3,
                        help="同一个 run 最多跑到第几次 attempt（默认 3 = 原始 + 2 次重跑）")
    parser.add_argument("--window-hours", type=float, default=24.0,
                        help="只重跑这个时间窗内结束的失败（默认 24 小时）")
    parser.add_argument("--dry-run", action="store_true", help="只列出会重跑哪些，不真重跑")
    args = parser.parse_args()

    workflows = args.workflows or list(DEFAULT_WORKFLOWS)
    repo = os.environ.get("GITHUB_REPOSITORY") or DEFAULT_REPO
    print(f"watchdog: repo={repo} workflows={workflows} "
          f"max_attempts={args.max_attempts} window={args.window_hours}h dry_run={args.dry_run}")

    if args.dry_run:
        # Listing a public repository works without a token (anonymously rate-limited);
        # a dry run must not require one just to say what it would do.
        try:
            token = publish_bank.read_token()
        except SystemExit:
            token = None
    else:
        token = publish_bank.read_token()

    lines = []
    failed_workflows = 0
    for workflow in workflows:
        try:
            runs = list_runs(token, repo, workflow)
        except SystemExit as error:
            print(f"跳过 {workflow}：{error}")
            failed_workflows += 1
            continue
        retryable, skipped = select(runs, args.max_attempts, args.window_hours)
        print(f"\n{workflow}: 共 {len(runs)} 次运行，"
              f"需要重跑 {len(retryable)} 个，跳过 {len(skipped)} 个")
        for run in retryable:
            created = run.get("created_at", "?")
            attempt = run.get("run_attempt", 1)
            if args.dry_run:
                print(f"  [dry-run] 会重跑 run {run['id']}（{created}，"
                      f"attempt {attempt}→{attempt + 1}，{run.get('display_title') or ''}）")
                continue
            message = {}
            try:
                ok, text = rerun(token, repo, run["id"])
                message = {"id": run["id"], "ok": ok, "message": text}
            except Exception as error:  # noqa: BLE001 - reported, never fatal (see docstring)
                message = {"id": run["id"], "ok": False, "message": retry.describe_error(error)}
            mark = "✓" if message["ok"] else "×"
            print(f"  {mark} run {run['id']}（{created}，attempt {attempt}→{attempt + 1}）："
                  f"{message['message']}")
            lines.append(f"- run `{run['id']}` attempt {attempt}→{attempt + 1}：{message['message']}")
        for run_id, conclusion, reason in skipped:
            print(f"  · run {run_id}（{conclusion}）跳过：{reason}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("## 失败任务自动重跑\n\n")
            handle.write(f"- 盯的 workflow：{', '.join(workflows)}\n")
            handle.write(f"- 上限：attempt < {args.max_attempts}；窗口：{args.window_hours} 小时\n")
            if lines:
                handle.write("\n".join(lines) + "\n")
            else:
                handle.write("- 没有需要重跑的失败任务\n")

    # A watchdog that exits red would add a second failure to the pile it is watching, so
    # a workflow it could not read is reported and swallowed.
    if failed_workflows:
        print(f"\n{failed_workflows} 个 workflow 没能读取（已跳过，不影响其它）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
