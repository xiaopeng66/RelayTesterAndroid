"""Offline regressions for interrupted feed uploads and stale publishers.

Publication code stays real; the existing fixtures replace only external services,
Android SDK calls, and the upstream build. An interrupted upload may leave GitHub's
zero-byte ``starter`` asset, not just an absent asset.
"""

from contextlib import contextmanager
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
from urllib.parse import urlsplit

TOOLS = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(TOOLS), str(TOOLS / "tests")]
TEST_TMP = TOOLS.parents[2] / "tmp" if os.name == "nt" else Path(tempfile.gettempdir())

import publish_bank
import test_publish_release_apk as apk_fixtures
import test_update_fingerprint_package as bank_fixtures


def with_build_stamp(blob, stamp):
    """Change only the second length-prefixed header string of a valid package."""
    offset = 8
    offset += 4 + struct.unpack_from("<I", blob, offset)[0]
    old_length = struct.unpack_from("<I", blob, offset)[0]
    text = stamp.encode("utf-8")
    return blob[:offset] + struct.pack("<I", len(text)) + text + blob[offset + 4 + old_length:]


def with_corrected_bias(blob):
    """A corrected positional weight can legitimately keep the bank's build stamp."""
    changed = bytearray(blob)
    last_bias = len(blob) - (4 + len(b"shared-detector-v2")) - 4
    previous = struct.unpack_from("<i", blob, last_bias)[0]
    struct.pack_into("<i", changed, last_bias, previous + 1000)
    return bytes(changed)


class PublicationRecoveryTests(unittest.TestCase):
    @contextmanager
    def bank_case(self):
        with patch.object(bank_fixtures, "TEST_TMP", TEST_TMP):
            case = bank_fixtures.PublicationRecoveryTest()
            try:
                case.setUp()
                yield case
            finally:
                case.doCleanups()

    @contextmanager
    def apk_case(self):
        with patch.object(apk_fixtures, "TEST_TMP", TEST_TMP):
            case = apk_fixtures.PublisherMainTests()
            try:
                case.setUp()
                yield case
            finally:
                case.doCleanups()

    def must_complete(self, operation):
        try:
            self.assertEqual(0, operation())
        except (SystemExit, ValueError) as error:
            self.fail(f"A retry must repair the interrupted publication: {type(error).__name__}: {error}")

    def run_bank_entry(self, case, entry):
        if entry != "direct":
            return case.run_update(*(("--force",) if entry == "forced-updater" else ()))
        case.out.write_bytes(case.bank)
        with patch.object(sys, "argv", ["publish_bank.py", "--bank", str(case.out)]):
            return publish_bank.main()

    def test_new_upstream_retry_must_preserve_the_previous_uncommitted_payload_fence(self):
        for entry in ("updater", "forced-updater", "direct"):
            with self.subTest(entry=entry), self.bank_case() as case:
                a = with_build_stamp(case.bank, "2026-10-11T00:00:00+00:00")
                b = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                c = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                case.server.files["lite-bank.bin"] = a
                case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(a)).encode("utf-8")
                original_request = case.server.request

                def crash_before_manifest_delete(token, method, url, data=None, content_type=None, raw=False):
                    if method == "DELETE" and url.endswith("/assets/2"):
                        raise SystemExit("Interrupted after B upload, before manifest delete")
                    return original_request(token, method, url, data, content_type, raw)

                case.bank = b
                with patch.object(publish_bank, "request", side_effect=crash_before_manifest_delete):
                    with self.assertRaises(SystemExit):
                        self.run_bank_entry(case, entry)
                self.assertEqual(b, case.server.files["lite-bank.bin"])
                self.assertEqual("2026-10-11T00:00:00+00:00", json.loads(case.server.files["latest.json"])["builtAt"])
                before = case.server.files.copy()
                writes_before_retry = list(case.server.writes)

                # A normal rerun resolves upstream again and builds C, not byte-identical B.
                # Deleting B before a failed C upload would leave only A's stale fence.
                case.bank = c
                case.server.fail_next_bank_upload = True
                with self.assertRaises(SystemExit) as refused:
                    self.run_bank_entry(case, entry)
                self.assertEqual(writes_before_retry, case.server.writes,
                                 "C must not delete the only surviving fence for B, even with --force")
                self.assertEqual(before, case.server.files)
                self.assertIn("latest.json", str(refused.exception))

                case.server.fail_next_bank_upload = False
                case.bank = a
                with self.assertRaises(SystemExit):
                    self.run_bank_entry(case, entry)
                self.assertEqual(writes_before_retry, case.server.writes, "An old A run must still be refused")
                self.assertEqual(before, case.server.files)

    def test_different_bank_requires_a_surviving_manifest_fence(self):
        for manifest in (None, b"", b"not json"):
            for entry in ("updater", "forced-updater", "direct"):
                with self.subTest(manifest=manifest, entry=entry), self.bank_case() as case:
                    b = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                    case.bank = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                    case.server.files["lite-bank.bin"] = b
                    if manifest is None:
                        del case.server.files["latest.json"]
                    else:
                        case.server.files["latest.json"] = manifest
                    before = case.server.files.copy()
                    case.server.fail_next_bank_upload = True
                    with self.assertRaises(SystemExit) as refused:
                        self.run_bank_entry(case, entry)
                    self.assertEqual([], case.server.writes,
                                     "An identity inferred from B is not a surviving manifest fence")
                    self.assertEqual(before, case.server.files)
                    self.assertIn("latest.json", str(refused.exception))

    def test_committed_bank_can_be_replaced_by_newer_upstream(self):
        for entry in ("updater", "forced-updater", "direct"):
            with self.subTest(entry=entry), self.bank_case() as case:
                a = with_build_stamp(case.bank, "2026-10-11T00:00:00+00:00")
                case.bank = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                case.server.files["lite-bank.bin"] = a
                case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(a)).encode("utf-8")
                self.must_complete(lambda: self.run_bank_entry(case, entry))
                self.assertEqual(case.bank, case.server.files["lite-bank.bin"])
                self.assertEqual("2026-10-12T00:00:00+00:00", json.loads(case.server.files["latest.json"])["builtAt"])
                case.assert_published_bank_matches()

    def test_bank_payload_uploaded_before_manifest_is_also_a_monotonic_fence(self):
        for flags in ((), ("--force",)):
            with self.subTest(flags=flags), self.bank_case() as case:
                case.bank = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                newer = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                # New bank upload completed, but the run died before changing latest.json.
                case.server.files["lite-bank.bin"] = newer
                case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(case.bank)).encode("utf-8")
                before = case.server.files.copy()
                try:
                    case.run_update(*flags)
                except SystemExit:
                    pass
                self.assertEqual([], case.server.writes, "A newer payload must survive its stale companion manifest")
                self.assertEqual(before, case.server.files)

    def test_cdn_404_cannot_overwrite_an_api_listed_newer_bank(self):
        for entry in ("updater", "forced-updater", "direct"):
            with self.subTest(entry=entry), self.bank_case() as case:
                case.bank = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                newer = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                case.server.files["lite-bank.bin"] = newer
                case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(case.bank)).encode("utf-8")
                original_release = case.server.release
                original_download = case.server.download

                def uploaded_release():
                    release = original_release()
                    for asset in release["assets"]:
                        asset["state"] = "uploaded"
                    return release

                def cdn_lag(url, **options):
                    if urlsplit(url).path.endswith("/lite-bank.bin") and not case.server.writes:
                        raise urllib.error.HTTPError(url, 404, "CDN has not caught up", {}, None)
                    return original_download(url, **options)

                before = case.server.files.copy()
                failure = None
                with patch.object(case.server, "release", side_effect=uploaded_release), \
                        patch.object(publish_bank, "download", side_effect=cdn_lag):
                    try:
                        if entry == "direct":
                            case.out.write_bytes(case.bank)
                            with patch.object(sys, "argv", ["publish_bank.py", "--bank", str(case.out)]):
                                publish_bank.main()
                        else:
                            case.run_update(*(("--force",) if entry == "forced-updater" else ()))
                    except SystemExit as error:
                        failure = error
                self.assertEqual([], case.server.writes, "CDN 404 is not proof that an API-listed uploaded bank is absent")
                self.assertEqual(before, case.server.files)
                self.assertIsNotNone(failure, "An unreadable uploaded bank must fail closed")

    def test_cdn_404_repairs_only_api_confirmed_absent_or_empty_starter_bank(self):
        for state in ("absent", "starter"):
            for entry in ("forced-updater", "direct"):
                with self.subTest(state=state, entry=entry), self.bank_case() as case:
                    original_release = case.server.release
                    original_download = case.server.download
                    interrupted = False

                    def release_state():
                        release = original_release()
                        for asset in release["assets"]:
                            asset["state"] = "starter" if asset["size"] == 0 else "uploaded"
                        return release

                    def deletion_completed(url, **options):
                        nonlocal interrupted
                        if urlsplit(url).path.endswith("/lite-bank.bin") and not interrupted:
                            interrupted = True
                            if state == "absent":
                                del case.server.files["lite-bank.bin"]
                            else:
                                case.server.files["lite-bank.bin"] = b""
                            raise urllib.error.HTTPError(url, 404, "Interrupted upload", {}, None)
                        return original_download(url, **options)

                    with patch.object(case.server, "release", side_effect=release_state), \
                            patch.object(publish_bank, "download", side_effect=deletion_completed):
                        if entry == "direct":
                            case.out.write_bytes(case.bank)
                            with patch.object(sys, "argv", ["publish_bank.py", "--bank", str(case.out)]):
                                self.must_complete(publish_bank.main)
                        else:
                            self.must_complete(lambda: case.run_update("--force"))
                    case.assert_published_bank_matches()
                    self.assertEqual(case.bank, case.server.files["lite-bank.bin"])

    def test_manifest_repair_preserves_the_already_uploaded_candidate_bank(self):
        for manifest in ("older", "absent", "invalid"):
            for entry in ("updater", "forced-updater", "direct"):
                with self.subTest(manifest=manifest, entry=entry), self.bank_case() as case:
                    older = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                    case.bank = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                    case.server.files["lite-bank.bin"] = case.bank
                    if manifest == "absent":
                        del case.server.files["latest.json"]
                    elif manifest == "invalid":
                        case.server.files["latest.json"] = b"not json"
                    else:
                        case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(older)).encode("utf-8")
                    # A correct payload must not be deleted on retry: a second interrupted
                    # upload would otherwise erase the only surviving newer-version fence.
                    case.server.fail_next_bank_upload = True
                    self.must_complete(lambda: self.run_bank_entry(case, entry))
                    self.assertEqual(case.bank, case.server.files["lite-bank.bin"])
                    expected = [] if manifest == "absent" else [("DELETE", "latest.json")]
                    self.assertEqual(expected + [("POST", "latest.json")], case.server.writes)
                    case.assert_published_bank_matches()

    def test_cdn_404_recheck_refuses_nonempty_or_unknown_starter_state(self):
        for state, size in (("starter", 123), ("starter", None), ("uploaded", 0)):
            with self.subTest(state=state, size=size), self.bank_case() as case:
                original_release = case.server.release
                readings = 0

                def release_state():
                    nonlocal readings
                    readings += 1
                    release = original_release()
                    for asset in release["assets"]:
                        asset["state"] = "uploaded"
                        if asset["name"] == "lite-bank.bin" and readings > 1:
                            asset["state"] = state
                            if size is None:
                                asset.pop("size", None)
                            else:
                                asset["size"] = size
                    return release

                original_download = case.server.download

                def cdn_lag(url, **options):
                    if urlsplit(url).path.endswith("/lite-bank.bin") and not case.server.writes:
                        raise urllib.error.HTTPError(url, 404, "Unknown payload state", {}, None)
                    return original_download(url, **options)

                case.out.write_bytes(case.bank)
                before = case.server.files.copy()
                with patch.object(case.server, "release", side_effect=release_state), \
                        patch.object(publish_bank, "download", side_effect=cdn_lag), \
                        patch.object(sys, "argv", ["publish_bank.py", "--bank", str(case.out)]):
                    failure = None
                    try:
                        publish_bank.main()
                    except SystemExit as error:
                        failure = error
                self.assertEqual([], case.server.writes)
                self.assertEqual(before, case.server.files)
                self.assertIsNotNone(failure)

    def test_cdn_404_recheck_requires_valid_surviving_manifest(self):
        for state in ("absent", "starter"):
            with self.subTest(state=state), self.bank_case() as case:
                original_release = case.server.release

                def release_state():
                    release = original_release()
                    for asset in release["assets"]:
                        asset["state"] = "starter" if asset["size"] == 0 else "uploaded"
                    return release

                def interrupted_again(url, **options):
                    if urlsplit(url).path.endswith("/lite-bank.bin"):
                        if state == "absent":
                            case.server.files.pop("lite-bank.bin", None)
                        else:
                            case.server.files["lite-bank.bin"] = b""
                        case.server.files["latest.json"] = b"not valid JSON"
                        raise urllib.error.HTTPError(url, 404, "Interrupted state changed", {}, None)
                    return case.server.download(url, **options)

                case.out.write_bytes(case.bank)
                with patch.object(case.server, "release", side_effect=release_state), \
                        patch.object(publish_bank, "download", side_effect=interrupted_again), \
                        patch.object(sys, "argv", ["publish_bank.py", "--bank", str(case.out)]):
                    with self.assertRaises(SystemExit):
                        publish_bank.main()
                self.assertEqual([], case.server.writes)

    def test_cdn_404_recheck_fails_closed_on_lookup_failure_or_newer_manifest(self):
        for result in ("lookup-failure", "newer-manifest"):
            with self.subTest(result=result), self.bank_case() as case:
                original_request = case.server.request
                original_download = case.server.download
                interrupted = False
                older = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                newer = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                case.bank = older
                case.server.files["lite-bank.bin"] = newer
                case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(older)).encode("utf-8")

                def release_lookup(token, method, url, data=None, content_type=None, raw=False):
                    if interrupted and result == "lookup-failure" and method == "GET":
                        return 503, b"GitHub unavailable"
                    return original_request(token, method, url, data, content_type, raw)

                def cdn_lag(url, **options):
                    nonlocal interrupted
                    if urlsplit(url).path.endswith("/lite-bank.bin") and not interrupted:
                        interrupted = True
                        if result == "newer-manifest":
                            del case.server.files["lite-bank.bin"]
                            case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(newer)).encode("utf-8")
                        raise urllib.error.HTTPError(url, 404, "CDN lag", {}, None)
                    return original_download(url, **options)

                case.out.write_bytes(case.bank)
                with patch.object(publish_bank, "request", side_effect=release_lookup), \
                        patch.object(publish_bank, "download", side_effect=cdn_lag), \
                        patch.object(sys, "argv", ["publish_bank.py", "--bank", str(case.out)]):
                    with self.assertRaises(SystemExit):
                        publish_bank.main()
                self.assertEqual([], case.server.writes)

    def test_changed_bank_cannot_replace_an_unreadable_published_payload(self):
        for answer in (b"broken package", urllib.error.URLError("offline")):
            with self.subTest(answer=type(answer).__name__), self.bank_case() as case:
                case.bank = with_build_stamp(case.bank, "2026-12-01T00:00:00+00:00")
                case.server.bank_readbacks = [answer] * 6
                before = case.server.files.copy()
                with self.assertRaises(SystemExit):
                    case.run_update()
                self.assertEqual([], case.server.writes)
                self.assertEqual(before, case.server.files)

    def test_starter_app_feed_transport_uncertainty_cannot_authorize_replacement(self):
        with self.apk_case() as case:
            case.http.releases["app"]["assets"][0].update(state="starter", size=0)
            case.http.failures[("GET", apk_fixtures.API_URL + "/releases")] = 503
            with self.assertRaises(SystemExit):
                case.run_main()
            self.assertEqual([], case.http.mutations)

    def test_bank_retry_repairs_starter_manifest_after_failed_upload(self):
        for flags in ((), ("--force",)):
            with self.subTest(flags=flags), self.bank_case() as case:
                original_request = case.server.request
                original_release = case.server.release

                def release_with_state():
                    release = original_release()
                    for asset in release["assets"]:
                        asset["state"] = "starter" if asset["size"] == 0 else "uploaded"
                    return release

                def fail_manifest_upload(token, method, url, data=None, content_type=None, raw=False):
                    if method == "POST" and "name=latest.json" in url:
                        case.server.files["latest.json"] = b""
                        return 502, b"Bad Gateway; starter asset remains"
                    return original_request(token, method, url, data, content_type, raw)

                with patch.object(case.server, "release", side_effect=release_with_state):
                    with patch.object(publish_bank, "request", side_effect=fail_manifest_upload):
                        with self.assertRaises(SystemExit):
                            case.run_update("--force")
                    self.assertEqual(b"", case.server.files["latest.json"])
                    self.must_complete(lambda: case.run_update(*flags))
                case.assert_published_bank_matches()
                self.assertEqual(case.bank, case.server.files["lite-bank.bin"])

    def test_app_retry_repairs_starter_feed_for_the_current_release(self):
        with self.apk_case() as case:
            feed_asset = case.http.releases["app"]["assets"][0]
            feed_asset.update(state="starter", size=0)
            case.http.downloads[apk_fixtures.FEED_URL] = b""
            self.must_complete(case.run_main)
            published = json.loads(case.http.downloads[apk_fixtures.FEED_URL])
            self.assertEqual(10801, published["versionCode"])
            self.assertEqual(case.digest, published["sha256"])
            self.assertEqual(case.apk_bytes, case.http.downloads[apk_fixtures.APK_URL])

    def test_missing_feed_cannot_let_an_old_publisher_replace_a_newer_release(self):
        for state in ("missing", "starter"):
            with self.subTest(state=state), self.apk_case() as case:
                case.http.releases["v1.8.2"] = apk_fixtures.release_fixture(
                    "v1.8.2", 40, "Release 1.8.2\n版本代码：`10802`\n",
                    "RelayTester-v1.8.2-android.apk", 41, b"newer published APK",
                )
                case.set_live_feed(versionCode=10802, versionName="1.8.2")
                # A failed refresh of that newer feed destroys its old monotonic marker.
                if state == "missing":
                    case.http.releases["app"]["assets"] = []
                    del case.http.downloads[apk_fixtures.FEED_URL]
                else:
                    case.http.releases["app"]["assets"][0].update(state="starter", size=0)
                    case.http.downloads[apk_fixtures.FEED_URL] = b""
                downloads_before = case.http.downloads.copy()
                with self.assertRaises(SystemExit, msg="A missing feed must not authorize a rollback"):
                    case.run_main()
                self.assertEqual([], case.http.mutations)
                self.assertEqual(downloads_before, case.http.downloads)

    def test_old_bank_cannot_overwrite_a_newer_published_build(self):
        for flags in ((), ("--force",)):
            with self.subTest(flags=flags), self.bank_case() as case:
                case.bank = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                newer = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                case.server.files = {
                    "lite-bank.bin": newer,
                    "latest.json": (
                        json.dumps(publish_bank.build_manifest(newer), ensure_ascii=False, indent=2) + "\n"
                    ).encode("utf-8"),
                }
                before = case.server.files.copy()
                try:
                    case.run_update(*flags)
                except SystemExit:
                    pass  # A controlled refusal or a read-only stale-run skip is safe.
                self.assertEqual([], case.server.writes, "Stale bank publication must stop before deleting assets")
                self.assertEqual(before, case.server.files)

    def test_draft_version_code_must_match_the_verified_apk(self):
        with self.apk_case() as case:
            text = case.draft.read_text(encoding="utf-8").replace("`10801`", "`10802`")
            case.draft.write_text(text, encoding="utf-8")
            with self.assertRaises(SystemExit):
                case.run_main("--dry-run", "--notes", str(case.draft))
            self.assertEqual([], case.http.events)

    def test_final_version_check_binds_the_feed_asset_that_can_be_deleted(self):
        with self.apk_case() as case:
            original = case.http.urlopen
            feed_reads = 0
            advanced = False
            newer_feed = json.dumps(dict(case.feed, versionCode=10802, versionName="1.8.2")).encode("utf-8")

            def another_publisher(request, timeout):
                nonlocal feed_reads, advanced
                if request.get_method() == "GET" and request.full_url.startswith(apk_fixtures.FEED_URL):
                    feed_reads += 1
                    response = original(request, timeout)
                    if feed_reads == 2:
                        # The returned bytes describe old asset 21, but a newer publish
                        # replaces it with asset 50 before the checked snapshot is used.
                        case.http.releases["app"]["assets"][0]["id"] = 50
                        case.http.downloads[apk_fixtures.FEED_URL] = newer_feed
                        advanced = True
                    return response
                if request.get_method() == "DELETE" and request.full_url.endswith("/assets/21") and advanced:
                    case.http.events.append(("DELETE", request.full_url, None))
                    case.http.error(request.full_url, 404)
                return original(request, timeout)

            with patch("urllib.request.urlopen", side_effect=another_publisher):
                failure = None
                try:
                    case.run_main()
                except SystemExit as error:
                    failure = error
            self.assertTrue(advanced, "The fixture must advance the feed after the final version read")
            self.assertFalse(any(method == "DELETE" and url.endswith("/assets/50")
                                 for method, url, _ in case.http.events),
                             "Never delete a newer asset whose version was not checked")
            self.assertEqual(newer_feed, case.http.downloads[apk_fixtures.FEED_URL])
            self.assertIsNotNone(failure, "The stale asset id's 404 must stop publication")

    def test_feed_advancing_during_apk_upload_is_rechecked_before_feed_replacement(self):
        with self.apk_case() as case:
            original = case.http.urlopen
            feed_after_other_publish = json.dumps(dict(case.feed, versionCode=10802)).encode("utf-8")

            def another_publish(request, timeout):
                response = original(request, timeout)
                if request.get_method() == "POST" and "name=" + apk_fixtures.APK_NAME in request.full_url:
                    case.http.downloads[apk_fixtures.FEED_URL] = feed_after_other_publish
                return response

            with patch("urllib.request.urlopen", side_effect=another_publish), self.assertRaises(SystemExit):
                case.run_main()
            self.assertEqual(feed_after_other_publish, case.http.downloads[apk_fixtures.FEED_URL])
            feed_upload = case.http.releases["app"]["upload_url"].split("{")[0]
            self.assertFalse(any(method != "GET" and (url.startswith(feed_upload) or url.endswith("/assets/21"))
                                 for method, url, _ in case.http.events))

    def test_missing_app_feed_fails_closed_when_release_discovery_fails(self):
        with self.apk_case() as case:
            case.http.releases["app"]["assets"] = []
            del case.http.downloads[apk_fixtures.FEED_URL]
            case.http.failures[("GET", apk_fixtures.API_URL + "/releases")] = 503
            with self.assertRaises(SystemExit):
                case.run_main()
            self.assertEqual([], case.http.mutations)

    def test_missing_app_feed_checks_all_pages_for_the_largest_version_code(self):
        with self.apk_case() as case:
            case.http.releases["app"]["assets"] = []
            del case.http.downloads[apk_fixtures.FEED_URL]
            for index in range(105):
                tag = f"v0.0.{index}"
                case.http.releases[tag] = apk_fixtures.release_fixture(
                    tag, 100 + index, "版本代码：`1`\n", "old.apk", 1000 + index, b"old",
                )
            case.http.releases["v1.8.2"] = apk_fixtures.release_fixture(
                "v1.8.2", 300, "版本代码：`10802`\n", "new.apk", 3000, b"new",
            )
            with self.assertRaises(SystemExit):
                case.run_main()
            self.assertEqual([], case.http.mutations)

    def test_missing_app_feed_refuses_a_version_release_with_unknown_code(self):
        with self.apk_case() as case:
            case.http.releases["app"]["assets"] = []
            del case.http.downloads[apk_fixtures.FEED_URL]
            case.http.releases["v1.8.2"] = apk_fixtures.release_fixture(
                "v1.8.2", 40, "No trustworthy version code.", "new.apk", 41, b"new",
            )
            with self.assertRaises(SystemExit):
                case.run_main()
            self.assertEqual([], case.http.mutations)

    def test_bank_missing_or_invalid_manifest_uses_the_actual_newer_bank_as_fence(self):
        for manifest in (None, b"", b"not json", b"{}", b"[]"):
            with self.subTest(manifest=manifest), self.bank_case() as case:
                case.bank = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
                newer = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
                case.server.files["lite-bank.bin"] = newer
                if manifest is None:
                    del case.server.files["latest.json"]
                else:
                    case.server.files["latest.json"] = manifest
                before = case.server.files.copy()
                try:
                    case.run_update()
                except SystemExit:
                    pass
                except Exception as error:
                    self.fail(f"Unknown publication state needs a controlled refusal: {error}")
                self.assertEqual([], case.server.writes)
                self.assertEqual(before, case.server.files)

    def test_bank_repair_refuses_unknown_payload_instead_of_deleting_it(self):
        for answer in (b"corrupt package", urllib.error.URLError("offline"),
                       urllib.error.HTTPError("https://example.invalid/bank", 503, "offline", {}, None)):
            with self.subTest(answer=type(answer).__name__), self.bank_case() as case:
                case.server.files["latest.json"] = b"not json"
                case.server.bank_readbacks = [answer] * 6
                before = case.server.files.copy()
                with self.assertRaises(SystemExit):
                    try:
                        case.run_update()
                    except Exception as error:
                        self.fail(f"Unknown publication state needs a controlled refusal: {error}")
                self.assertEqual([], case.server.writes)
                self.assertEqual(before, case.server.files)

    def test_bank_build_stamps_are_compared_as_instants_not_strings(self):
        with self.bank_case() as case:
            # Candidate is 03:00 UTC; published bank is 02:00 UTC despite the later date text.
            case.bank = with_build_stamp(case.bank, "2026-10-12T23:00:00-04:00")
            older = with_build_stamp(case.bank, "2026-10-13T02:00:00+00:00")
            case.server.files["lite-bank.bin"] = older
            case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(older)).encode("utf-8")
            self.must_complete(case.run_update)
            self.assertEqual(case.bank, case.server.files["lite-bank.bin"])

    def test_bank_naive_or_invalid_build_stamp_cannot_authorize_replacement(self):
        for stamp in ("2026-10-13T00:00:00", "not-a-date"):
            with self.subTest(stamp=stamp), self.bank_case() as case:
                case.bank = with_build_stamp(case.bank, stamp)
                before = case.server.files.copy()
                with self.assertRaises(SystemExit):
                    case.run_update()
                self.assertEqual([], case.server.writes)
                self.assertEqual(before, case.server.files)

    def test_bank_publish_from_stale_repository_sha_stops_before_writes(self):
        with self.bank_case() as case:
            original = case.server.request

            def latest_branch(token, method, url, data=None, content_type=None, raw=False):
                if method == "GET" and "/commits/" in url:
                    return 200, json.dumps({"sha": "b" * 40}).encode("utf-8")
                return original(token, method, url, data, content_type, raw)

            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "GITHUB_SHA": "a" * 40,
                                         "GITHUB_REF": "refs/heads/main", "GITHUB_REPOSITORY": publish_bank.REPO}), \
                    patch.object(publish_bank, "request", side_effect=latest_branch):
                before = case.server.files.copy()
                try:
                    case.run_update("--force")
                except SystemExit:
                    pass
                self.assertEqual([], case.server.writes)
                self.assertEqual(before, case.server.files)

    def test_bank_workflow_head_requires_successful_exact_revision_lookup(self):
        for status, head in ((200, "a" * 40), (503, None), (200, None)):
            with self.subTest(status=status, head=head), self.bank_case() as case:
                original = case.server.request

                def latest_branch(token, method, url, data=None, content_type=None, raw=False):
                    if method == "GET" and "/commits/" in url:
                        self.assertTrue(url.endswith("/commits/main"))
                        return status, json.dumps({"sha": head}).encode("utf-8")
                    return original(token, method, url, data, content_type, raw)

                with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "GITHUB_SHA": "a" * 40,
                                             "GITHUB_REF": "refs/heads/main", "GITHUB_REPOSITORY": publish_bank.REPO}), \
                        patch.object(publish_bank, "request", side_effect=latest_branch):
                    if status == 200 and head is not None:
                        self.must_complete(lambda: case.run_update("--force"))
                        case.assert_published_bank_matches()
                    else:
                        with self.assertRaises(SystemExit):
                            case.run_update("--force")
                        self.assertEqual([], case.server.writes)

    def test_direct_bank_publisher_also_refuses_an_older_bank(self):
        with self.bank_case() as case:
            stale = with_build_stamp(case.bank, "2026-10-12T00:00:00+00:00")
            newer = with_build_stamp(case.bank, "2026-10-13T00:00:00+00:00")
            case.out.write_bytes(stale)
            case.server.files["lite-bank.bin"] = newer
            case.server.files["latest.json"] = json.dumps(publish_bank.build_manifest(newer)).encode("utf-8")
            with patch.object(sys, "argv", ["publish_bank.py", "--bank", str(case.out)]):
                try:
                    publish_bank.main()
                except SystemExit:
                    pass
            self.assertEqual([], case.server.writes)
            self.assertEqual(newer, case.server.files["lite-bank.bin"])

    def test_corrected_bank_with_equal_build_stamp_can_still_publish(self):
        with self.bank_case() as case:
            before = case.server.files["lite-bank.bin"]
            case.bank = with_corrected_bias(case.bank)
            self.assertNotEqual(before, case.bank)
            self.assertEqual(
                publish_bank.read_header(before)["built_at"],
                publish_bank.read_header(case.bank)["built_at"],
            )
            self.must_complete(case.run_update)
            case.assert_published_bank_matches()
            self.assertEqual(case.bank, case.server.files["lite-bank.bin"])


if __name__ == "__main__":
    unittest.main()
