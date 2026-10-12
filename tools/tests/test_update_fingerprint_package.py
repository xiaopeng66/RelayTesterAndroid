"""Offline regression tests for detection-package publication and cache recovery."""

import hashlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import urllib.error
from contextlib import ExitStack, redirect_stdout
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

TOOLS = Path(__file__).resolve().parents[1]
TEST_TMP = TOOLS.parents[2] / "tmp" if os.name == "nt" else Path(tempfile.gettempdir())
sys.path.insert(0, str(TOOLS))

import publish_bank
import update_fingerprint_package as updater
import upstream_detector


class ReleaseServer:
    """Stateful fake of only the release endpoints used by the updater."""

    def __init__(self, bank):
        self.files = {
            "lite-bank.bin": bank,
            "latest.json": (
                json.dumps(publish_bank.build_manifest(bank), ensure_ascii=False, indent=2) + "\n"
            ).encode("utf-8"),
        }
        self.writes = []
        self.downloads = []
        self.bank_readbacks = []
        self.fail_next_bank_upload = False
        self.list_missing_bank = False
        self.original_bank = bank

    def release(self):
        listed = dict(self.files)
        if self.list_missing_bank and "lite-bank.bin" not in listed:
            listed["lite-bank.bin"] = self.original_bank
            self.list_missing_bank = False  # The next API read observes the completed deletion.
        return {
            "id": 1,
            "upload_url": "https://uploads.github.com/repos/example/app/releases/1/assets{?name,label}",
            "assets": [
                {
                    "id": 1 if name == "lite-bank.bin" else 2,
                    "name": name,
                    "size": len(body),
                    "digest": "sha256:" + hashlib.sha256(body).hexdigest(),
                    "browser_download_url": "https://github.com/example/app/releases/download/bank/" + name,
                }
                for name, body in listed.items()
            ],
        }

    def request(self, token, method, url, data=None, content_type=None, raw=False):
        parsed = urlsplit(url)
        if method == "GET" and parsed.path.endswith("/releases/tags/bank"):
            return 200, json.dumps(self.release()).encode("utf-8")
        if method == "DELETE" and "/releases/assets/" in parsed.path:
            name = {"1": "lite-bank.bin", "2": "latest.json"}[parsed.path.rsplit("/", 1)[-1]]
            self.writes.append((method, name))
            if name not in self.files:
                return 404, b"Not Found"
            del self.files[name]
            return 204, b""
        if method == "POST" and parsed.hostname == "uploads.github.com":
            name = parse_qs(parsed.query)["name"][0]
            self.writes.append((method, name))
            if name == "lite-bank.bin" and self.fail_next_bank_upload:
                self.fail_next_bank_upload = False
                return 503, b"temporary upload failure"
            self.files[name] = data
            return 201, json.dumps({"name": name}).encode("utf-8")
        raise AssertionError(f"unexpected release request: {method} {url}")

    def download(self, url, cache_bust=False, attempts=None, policy=None):
        name = urlsplit(url).path.rsplit("/", 1)[-1]
        self.downloads.append((name, cache_bust))
        if name == "lite-bank.bin" and cache_bust and self.bank_readbacks:
            answer = self.bank_readbacks.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return answer
        if name not in self.files:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        return self.files[name]


class PublicationRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory(dir=TEST_TMP, prefix="bank-publish-test-")))
        fixture = TOOLS.parent / "app/src/test/resources/lm-fingerprint/lite-bank-small.bin"
        self.bank = fixture.read_bytes()
        self.out = self.directory / "lite-bank.bin"
        self.server = ReleaseServer(self.bank)
        self.stack.enter_context(patch.object(publish_bank, "read_token", return_value="fake-token"))
        self.stack.enter_context(patch.object(publish_bank, "request", side_effect=self.server.request))
        self.stack.enter_context(patch.object(publish_bank, "download", side_effect=self.server.download))
        self.stack.enter_context(patch.object(publish_bank.time, "sleep"))
        self.stack.enter_context(patch.object(updater, "notify_tracking_issue"))
        self.stack.enter_context(patch.object(updater, "build", side_effect=self.build))
        self.stack.enter_context(patch.object(updater, "fetch_data", return_value=str(self.directory)))
        # The base fixture is a local invocation; dedicated workflow-head tests opt in
        # explicitly, independent of whether the suite itself runs inside Actions.
        self.stack.enter_context(patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}))
        # Any missed network boundary is a test failure, never an external request.
        self.stack.enter_context(patch("urllib.request.urlopen", side_effect=AssertionError("network forbidden")))
        self.stack.enter_context(redirect_stdout(io.StringIO()))

    def build(self, data_dir, out_path, args):
        Path(out_path).write_bytes(self.bank)

    def run_update(self, *extra):
        argv = ["update_fingerprint_package.py", "--revision", "test-revision", "--out", str(self.out), *extra]
        with patch.object(sys, "argv", argv):
            return updater.main()

    def assert_published_bank_matches(self):
        self.assertIn("lite-bank.bin", self.server.files)
        self.assertEqual(hashlib.sha256(self.bank).hexdigest(), hashlib.sha256(self.server.files["lite-bank.bin"]).hexdigest())
        manifest = json.loads(self.server.files["latest.json"])
        self.assertEqual(hashlib.sha256(self.bank).hexdigest(), manifest["sha256"])
        self.assertEqual(len(self.bank), manifest["sizeBytes"])

    def test_normal_rerun_repairs_bin_deleted_by_failed_identical_forced_publish(self):
        self.server.fail_next_bank_upload = True
        with self.assertRaises(SystemExit):
            self.run_update("--force")
        self.assertNotIn("lite-bank.bin", self.server.files)
        self.assertIn("latest.json", self.server.files)

        self.assertEqual(0, self.run_update())
        self.assert_published_bank_matches()

    def test_equal_manifest_digest_does_not_hide_different_bank_bytes(self):
        self.server.files["lite-bank.bin"] = self.bank[:-1] + bytes([self.bank[-1] ^ 1])

        self.assertEqual(0, self.run_update())

        self.assert_published_bank_matches()

    def test_identical_publish_is_read_back_without_any_write(self):
        self.assertEqual(0, self.run_update())

        self.assertIn(("lite-bank.bin", True), self.server.downloads)
        self.assertIn(("latest.json", True), self.server.downloads)
        self.assertEqual([], self.server.writes)

    def test_equal_digest_with_stale_manifest_metadata_is_repaired(self):
        expected = json.loads(self.server.files["latest.json"])
        for key, stale in (("sizeBytes", len(self.bank) - 1), ("minAppVersionCode", 1), ("formatVersion", 2)):
            with self.subTest(field=key):
                manifest = dict(expected)
                manifest[key] = stale
                self.server.files["latest.json"] = json.dumps(manifest).encode("utf-8")

                self.assertEqual(0, self.run_update())

                self.assertEqual(expected, json.loads(self.server.files["latest.json"]))
                self.assert_published_bank_matches()

    def test_stale_asset_listing_does_not_hide_bank_download_404(self):
        del self.server.files["lite-bank.bin"]
        self.server.list_missing_bank = True

        self.assertEqual(0, self.run_update())

        self.assert_published_bank_matches()

    def test_transport_failure_during_unchanged_readback_fails_without_writes(self):
        self.server.bank_readbacks = [urllib.error.URLError("connection unavailable") for _ in range(6)]

        with self.assertRaises(SystemExit):
            self.run_update()

        self.assertEqual([], self.server.writes)

    def test_mismatch_followed_by_transport_failure_is_not_treated_as_safe_to_replace(self):
        self.server.bank_readbacks = [b"stale CDN bytes"] + [
            urllib.error.URLError("connection unavailable") for _ in range(5)
        ]

        with self.assertRaises(SystemExit):
            self.run_update()

        self.assertEqual([], self.server.writes)

    def test_failed_repair_upload_remains_a_failure(self):
        del self.server.files["lite-bank.bin"]
        self.server.fail_next_bank_upload = True

        with self.assertRaises(SystemExit):
            self.run_update()

        self.assertNotIn("lite-bank.bin", self.server.files)


class DetectorCacheTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory(dir=TEST_TMP, prefix="bank-cache-test-")))
        header = json.dumps({"revision": "binary"}).encode("utf-8")
        binary = struct.pack("<I", len(header)) + header
        binary += b"\0" * (-len(binary) % 4)
        self.revisions = {
            "binary": {
                "unified_bank.json": b'{"revision": "binary"}',
                "shared_detector.bin": binary,
            },
            "json": {
                "unified_bank.json": b'{"revision": "json"}',
                "shared_detector.json": b'{"revision": "json"}',
            },
        }
        self.downloads = []
        self.stack.enter_context(patch.object(updater, "download", side_effect=self.download))
        self.stack.enter_context(patch("urllib.request.urlopen", side_effect=AssertionError("network forbidden")))
        self.stack.enter_context(redirect_stdout(io.StringIO()))

    def download(self, url, destination, absent_ok=False):
        revision, _, name = urlsplit(url).path.rsplit("/", 3)[-3:]
        self.downloads.append((revision, name))
        body = self.revisions[revision].get(name)
        if body is None:
            if absent_ok:
                return False
            raise AssertionError(f"required input missing in fake revision: {url}")
        if isinstance(body, Exception):
            raise body
        Path(destination).write_bytes(body)
        return destination

    def fetch(self, revision, refresh=False):
        return updater.fetch_data(str(self.directory), revision, refresh)

    def test_pinning_json_revision_after_binary_revision_reads_the_requested_detector(self):
        self.fetch("binary")
        self.fetch("json")

        detector, name = upstream_detector.read_detector(self.directory)
        self.assertEqual("json", detector["revision"])
        self.assertEqual("shared_detector.json", name)
        self.assertFalse((self.directory / "shared_detector.bin").exists())

    def test_switching_to_binary_removes_unused_json_before_cache_is_reused(self):
        self.fetch("json")
        self.fetch("binary")

        self.assertFalse((self.directory / "shared_detector.json").exists())
        count = len(self.downloads)
        self.fetch("binary")
        self.assertEqual(count, len(self.downloads))
        self.assertEqual("binary", upstream_detector.read_detector(self.directory)[0]["revision"])

    def test_ambiguous_existing_cache_is_refetched_even_when_revision_stamp_matches(self):
        for revision in ("binary", "json"):
            for name, body in self.revisions[revision].items():
                (self.directory / name).write_bytes(body)
        (self.directory / "revision.json").write_text('{"sha": "json"}', encoding="utf-8")

        self.fetch("json")

        self.assertEqual("json", upstream_detector.read_detector(self.directory)[0]["revision"])
        self.assertTrue(self.downloads)

    def test_failed_revision_switch_does_not_leave_old_stamp_on_mixed_inputs(self):
        self.fetch("json")
        self.revisions["binary"]["shared_detector.bin"] = OSError("download interrupted")
        with self.assertRaises(OSError):
            self.fetch("binary")

        self.fetch("json")

        bank = json.loads((self.directory / "unified_bank.json").read_text(encoding="utf-8"))
        self.assertEqual("json", bank["revision"])
        self.assertEqual("json", upstream_detector.read_detector(self.directory)[0]["revision"])


if __name__ == "__main__":
    unittest.main()
