"""The watchdog must respect retry budgets and support a read-only public preview."""

from contextlib import redirect_stdout
from datetime import datetime
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import publish_bank
import retry_failed_runs as watchdog


def run(identifier, created, conclusion="failure", attempt=1):
    return {
        "id": identifier,
        "created_at": created,
        "conclusion": conclusion,
        "status": "completed",
        "run_attempt": attempt,
        "head_branch": "main",
    }


class WatchdogTest(unittest.TestCase):
    def test_only_recent_failures_below_attempt_budget_are_selected(self):
        runs = [
            run(1, "2026-10-13T09:00:00Z"),
            run(2, "2026-10-13T08:00:00Z", "timed_out", 2),
            run(3, "2026-10-13T08:00:00Z", "failure", 3),
            run(4, "2026-10-11T10:00:00Z"),
            run(5, "2026-10-13T09:00:00Z", "cancelled"),
            run(6, "2026-10-13T09:00:00Z", "action_required"),
            run(7, "2026-10-13T09:00:00Z", "startup_failure"),
        ]
        chosen, skipped = watchdog.select(runs, 3, 24, now=datetime(2026, 10, 13, 10))
        self.assertEqual([1, 2], [item["id"] for item in chosen])
        self.assertEqual({3, 4, 5, 6, 7}, {item[0] for item in skipped})

    def test_newer_success_prevents_obsolete_retry(self):
        runs = [
            dict(run(1, "2026-10-13T09:00:00Z", "success"), updated_at="2026-10-13T09:10:00Z"),
            dict(run(2, "2026-10-13T08:00:00Z"), updated_at="2026-10-13T08:10:00Z"),
        ]
        chosen, _ = watchdog.select(runs, 3, 24, now=datetime(2026, 10, 13, 10))
        self.assertEqual([], chosen)

    def test_later_created_success_cannot_hide_a_failure_that_completed_after_it(self):
        success = dict(run(2, "2026-10-13T09:01:00Z", "success"),
                       updated_at="2026-10-13T09:05:00Z", head_sha="a" * 40)
        failure = dict(run(1, "2026-10-13T09:00:00Z"),
                       updated_at="2026-10-13T09:10:00Z", head_sha="a" * 40)
        chosen, _ = watchdog.select([success, failure], 3, 24, now=datetime(2026, 10, 13, 10))
        self.assertEqual([1], [item["id"] for item in chosen])

    def test_success_without_known_completion_order_does_not_suppress_retry(self):
        runs = [run(2, "2026-10-13T09:01:00Z", "success"), run(1, "2026-10-13T09:00:00Z")]
        chosen, _ = watchdog.select(runs, 3, 24, now=datetime(2026, 10, 13, 10))
        self.assertEqual([1], [item["id"] for item in chosen])

    def test_read_only_preview_works_without_token_and_never_posts(self):
        response = io.BytesIO(json.dumps({"workflow_runs": [run(7, "2026-10-13T09:00:00Z")]}).encode())
        response.status = 200
        response.headers = {}
        seen = []

        def transport(request, **kwargs):
            seen.append(request)
            self.assertEqual("GET", request.get_method())
            self.assertIsNone(request.get_header("Authorization"))
            return response

        with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": "", "GITHUB_REPOSITORY": "fixture/repository"}), \
                patch.object(sys, "argv", ["retry_failed_runs.py", "--dry-run"]), \
                patch.object(publish_bank, "read_token", side_effect=SystemExit("no token")), \
                patch("urllib.request.urlopen", side_effect=transport), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(0, watchdog.main())
        self.assertEqual(1, len(seen))


if __name__ == "__main__":
    unittest.main()
