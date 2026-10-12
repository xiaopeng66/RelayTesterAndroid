"""Offline regression tests for the APK publisher's real gates and orchestration.

Only HTTP, credentials and Android SDK commands are substituted. Files, APK ZIP/ELF
checks, release lookups, Markdown conversion and publishing control flow stay real.
"""

import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import urllib.parse
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import publish_release_apk as publisher  # noqa: E402

# Keep local test artifacts inside the workspace, not the Windows account's TEMP.
# Linux CI has no E: drive and uses its explicitly selected system temporary root.
TEST_TMP = (
    Path("E:/AI/Zcode/tmp/relay-tester-publisher-tests")
    if os.name == "nt"
    else Path(tempfile.gettempdir()) / "relay-tester-publisher-tests"
)
REPO_URL = "https://github.com/xiaopeng66/RelayTesterAndroid"
API_URL = "https://api.github.com/repos/xiaopeng66/RelayTesterAndroid"
TAG = "v1.8.1"
APK_NAME = "RelayTester-v1.8.1-android.apk"
APK_URL = f"{REPO_URL}/releases/download/{TAG}/{APK_NAME}"
FEED_URL = f"{REPO_URL}/releases/download/app/app-latest.json"
ABC_DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"


def apk_fixture(*, wording=True, alignment=0x4000):
    """A deterministic ZIP with real wording and a page-aligned ELF PT_LOAD entry."""
    elf = bytearray(120)
    elf[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", elf, 0x20, 64)
    struct.pack_into("<HH", elf, 0x36, 56, 1)
    struct.pack_into("<I", elf, 64, 1)
    struct.pack_into("<Q", elf, 64 + 0x30, alignment)
    library = zipfile.ZipInfo("lib/arm64-v8a/libfixture.so", (2026, 1, 1, 0, 0, 0))
    padding = (-(30 + len(library.filename.encode("ascii")))) % 0x4000
    library.extra = struct.pack("<HH", 0xFFFF, padding - 4) + bytes(padding - 4)
    dex = zipfile.ZipInfo("classes.dex", (2026, 1, 1, 0, 0, 0))
    # The fixture follows the current wording inventory; negative tests omit it.
    words = "\x00".join(publisher.verify_apk_wording.FINAL) if wording else "fixture"
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(library, elf)
        archive.writestr(dex, words.encode("utf-8") + bytes(10_001))
    return output.getvalue()


def release_fixture(tag, release_id, body, asset_name, asset_id, payload):
    return {
        "id": release_id,
        "tag_name": tag,
        "name": tag,
        "body": body,
        "draft": False,
        "prerelease": tag == "app",
        "html_url": f"{REPO_URL}/releases/tag/{tag}",
        "upload_url": (
            f"https://uploads.github.com/repos/xiaopeng66/RelayTesterAndroid/"
            f"releases/{release_id}/assets{{?name,label}}"
        ),
        "assets": [{
            "id": asset_id,
            "name": asset_name,
            "size": len(payload),
            "state": "uploaded",
            "content_type": "application/json" if tag == "app" else "application/vnd.android.package-archive",
            "browser_download_url": f"{REPO_URL}/releases/download/{tag}/{asset_name}",
        }],
    }


class FakeGitHub:
    """In-memory HTTP transport; production request/lookup/upload functions run unchanged."""

    def __init__(self, notes, apk, feed):
        self.releases = {
            TAG: release_fixture(TAG, 10, notes, APK_NAME, 11, apk),
            "app": release_fixture("app", 20, "Update feed", "app-latest.json", 21, feed),
        }
        self.downloads = {APK_URL: apk, FEED_URL: feed}
        self.events = []
        self.failures = {}
        self.next_asset_id = 30

    @property
    def mutations(self):
        return [event for event in self.events if event[0] != "GET"]

    def urlopen(self, request, timeout):
        method = request.get_method()
        url = request.full_url
        payload = request.data
        self.events.append((method, url, payload))
        parts = urllib.parse.urlsplit(url)
        plain_url = urllib.parse.urlunsplit(parts._replace(query=""))
        failure = self.failures.get((method, plain_url))
        if failure is not None:
            self.error(url, failure)
        if method == "GET":
            if plain_url in self.downloads:
                return self.response(self.downloads[plain_url])
            if plain_url == API_URL + "/releases":
                query = urllib.parse.parse_qs(parts.query)
                page = int(query.get("page", ["1"])[0])
                count = int(query.get("per_page", ["30"])[0])
                releases = list(self.releases.values())
                return self.response(json.dumps(releases[(page - 1) * count:page * count]).encode("utf-8"))
            prefix = API_URL + "/releases/tags/"
            if url.startswith(prefix):
                release = self.releases.get(url[len(prefix):])
                if release is None:
                    self.error(url, 404)
                return self.response(json.dumps(release).encode("utf-8"))
        if method == "POST" and url == API_URL + "/releases":
            attributes = json.loads(payload)
            tag = attributes["tag_name"]
            if tag in self.releases:
                self.error(url, 422)
            release_id = max((item["id"] for item in self.releases.values()), default=0) + 10
            release = release_fixture(tag, release_id, attributes.get("body", ""), "", 0, b"")
            release.update(attributes, assets=[])
            self.releases[tag] = release
            return self.response(json.dumps(release).encode("utf-8"), 201)
        for release in self.releases.values():
            release_url = f"{API_URL}/releases/{release['id']}"
            if method == "PATCH" and url == release_url:
                release.update(json.loads(payload))
                return self.response(json.dumps(release).encode("utf-8"))
            upload_url = release["upload_url"].split("{")[0]
            if method == "POST" and plain_url == upload_url:
                name = urllib.parse.parse_qs(parts.query)["name"][0]
                download_url = f"{REPO_URL}/releases/download/{release['tag_name']}/{name}"
                asset = {
                    "id": self.next_asset_id,
                    "name": name,
                    "size": len(payload),
                    "state": "uploaded",
                    "content_type": request.get_header("Content-type"),
                    "browser_download_url": download_url,
                }
                self.next_asset_id += 1
                release["assets"].append(asset)
                self.downloads[download_url] = payload
                return self.response(json.dumps(asset).encode("utf-8"), 201)
            for asset in list(release["assets"]):
                if method == "DELETE" and url == f"{API_URL}/releases/assets/{asset['id']}":
                    release["assets"].remove(asset)
                    self.downloads.pop(asset["browser_download_url"], None)
                    return self.response(b"", 204)
        raise AssertionError(f"Unexpected HTTP boundary: {method} {url}")

    @staticmethod
    def response(body, status=200):
        result = io.BytesIO(body)
        result.status = status
        result.headers = {}
        return result

    @staticmethod
    def error(url, status):
        raise urllib.error.HTTPError(url, status, "fixture HTTP error", {}, io.BytesIO(b"{}"))


class PublisherMainTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        TEST_TMP.mkdir(parents=True, exist_ok=True)
        root = self.stack.enter_context(tempfile.TemporaryDirectory(dir=TEST_TMP))
        self.root = Path(root)
        self.apk = self.root / "app-optimized.apk"
        self.apk_bytes = apk_fixture()
        self.apk.write_bytes(self.apk_bytes)
        self.gradle = self.root / "build.gradle.kts"
        self.gradle.write_text('versionCode = 10_801\nversionName = "1.8.1"\n', encoding="utf-8")
        self.digest = hashlib.sha256(self.apk_bytes).hexdigest()
        self.body = self.notes("Published improvements.")
        self.draft = self.root / "draft.txt"
        self.draft.write_bytes(self.notes("Draft improvements.").encode("utf-8"))
        self.identity = {
            "packageName": "com.relaytester.app",
            "versionCode": 10801,
            "versionName": "1.8.1",
            "minSdk": 26,
        }
        self.signer = publisher.RELEASE_CERT_SHA256
        self.signature_status = 0
        self.tool_calls = []
        self.feed = {
            "formatVersion": 1,
            "packageName": "com.relaytester.app",
            "versionCode": 10801,
            "versionName": "1.8.1",
            "apkUrl": APK_URL,
            "sizeBytes": len(self.apk_bytes),
            "sha256": self.digest,
            "minSdk": 26,
            "notesUrl": f"{REPO_URL}/releases/tag/{TAG}",
            "notes": self.plain_notes("Published improvements."),
            "publishedAt": "2026-01-01T00:00:00+00:00",
        }
        self.http = FakeGitHub(self.body, self.apk_bytes, json.dumps(self.feed).encode("utf-8"))
        self.output = io.StringIO()
        self.errors = io.StringIO()
        self.stack.enter_context(contextlib.redirect_stdout(self.output))
        self.stack.enter_context(contextlib.redirect_stderr(self.errors))
        self.stack.enter_context(mock.patch.object(publisher, "GRADLE", str(self.gradle)))
        self.stack.enter_context(mock.patch.dict(os.environ, {"GITHUB_TOKEN": "fixture-not-a-real-token"}))
        self.stack.enter_context(mock.patch.object(publisher, "TOKEN_FILES", []))
        self.stack.enter_context(mock.patch.object(publisher, "apksigner_path", return_value="fixture-apksigner"))
        self.stack.enter_context(mock.patch.object(publisher, "aapt2_path", return_value="fixture-aapt2"))
        self.stack.enter_context(mock.patch.object(publisher.subprocess, "run", side_effect=self.run_tool))
        self.stack.enter_context(mock.patch.object(publisher.urllib.request, "urlopen", side_effect=self.http.urlopen))
        self.stack.enter_context(mock.patch.object(publisher.time, "sleep"))

    def notes(self, summary):
        return (
            f"# Release 1.8.1\n\n{summary}\n\n"
            f"- 版本代码：`10801`\n- 字节数：`{len(self.apk_bytes):,}`\n- SHA-256：`{self.digest}`\n"
        )

    def plain_notes(self, summary):
        return (
            f"Release 1.8.1\n\n{summary}\n\n"
            f"· 版本代码：10801\n· 字节数：{len(self.apk_bytes):,}\n· SHA-256：{self.digest}"
        )

    def run_tool(self, command, **kwargs):
        self.tool_calls.append(command)
        if command == ["fixture-apksigner", "verify", "--print-certs", str(self.apk)]:
            return subprocess.CompletedProcess(
                command, self.signature_status,
                stdout=f"Signer #1 certificate SHA-256 digest: {self.signer}\n", stderr="",
            )
        if command == ["fixture-aapt2", "dump", "badging", str(self.apk)]:
            identity = self.identity
            output = (
                f"package: name='{identity['packageName']}' versionCode='{identity['versionCode']}' "
                f"versionName='{identity['versionName']}'\nminSdkVersion:'{identity['minSdk']}'\n"
            )
            return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")
        raise AssertionError(f"Unexpected Android SDK command: {command}")

    def run_main(self, *arguments):
        with mock.patch.object(sys, "argv", ["publish_release_apk.py", "--apk", str(self.apk), *arguments]):
            return publisher.main()

    def preview(self):
        output = self.output.getvalue()
        return json.JSONDecoder().raw_decode(output[output.index("{\n"):])[0]

    @contextlib.contextmanager
    def unchanged(self):
        releases = copy.deepcopy(self.http.releases)
        downloads = self.http.downloads.copy()
        files = {path: path.read_bytes() for path in self.root.iterdir() if path.is_file()}
        try:
            yield
        finally:
            self.assertEqual([], self.http.mutations)
            self.assertEqual(releases, self.http.releases)
            self.assertEqual(downloads, self.http.downloads)
            self.assertEqual(files, {path: path.read_bytes() for path in self.root.iterdir() if path.is_file()})

    def set_live_feed(self, **changes):
        self.http.downloads[FEED_URL] = json.dumps(dict(self.feed, **changes)).encode("utf-8")

    def test_dry_run_with_notes_previews_without_credentials_or_network(self):
        with mock.patch.object(publisher, "read_token", side_effect=AssertionError("offline mode read credentials")), \
                mock.patch.object(publisher.urllib.request, "urlopen", side_effect=AssertionError("offline mode used HTTP")):
            self.assertEqual(0, self.run_main("--dry-run", "--notes", str(self.draft)))
        preview = self.preview()
        self.assertEqual("1.8.1", preview["versionName"])
        self.assertEqual(10801, preview["versionCode"])
        self.assertEqual(APK_URL, preview["apkUrl"])
        self.assertEqual(self.digest, preview["sha256"])
        self.assertEqual(len(self.apk_bytes), preview["sizeBytes"])
        self.assertEqual(self.plain_notes("Draft improvements."), preview["notes"])

    def test_dry_run_does_not_require_an_existing_release(self):
        self.http.releases.clear()
        try:
            result = self.run_main("--notes", str(self.draft), "--dry-run")
        except SystemExit as stopped:
            self.fail(f"Offline preview required a remote release: {stopped}")
        self.assertEqual(0, result)
        self.assertEqual([], self.http.events)
        self.assertEqual(10801, self.preview()["versionCode"])

    def test_dry_run_without_notes_runs_only_local_gates(self):
        with mock.patch.object(publisher, "read_token", side_effect=AssertionError("offline mode read credentials")), \
                mock.patch.object(publisher.urllib.request, "urlopen", side_effect=AssertionError("offline mode used HTTP")):
            self.assertEqual(0, self.run_main("--dry-run"))
        self.assertEqual(2, len(self.tool_calls))
        self.assertNotIn('"apkUrl"', self.output.getvalue())

    def test_dry_run_and_check_conflict_before_local_or_remote_work(self):
        with self.assertRaises(SystemExit) as stopped:
            self.run_main("--dry-run", "--check")
        self.assertEqual(2, stopped.exception.code)
        self.assertEqual([], self.tool_calls)
        self.assertEqual([], self.http.events)

    def test_invalid_draft_stops_before_credentials_or_network(self):
        self.draft.write_text(self.notes("Invalid.").replace(self.digest, "0" * 64), encoding="utf-8")
        with mock.patch.object(publisher, "read_token", side_effect=AssertionError("invalid draft read credentials")):
            with self.assertRaisesRegex(SystemExit, "不是同一份构建"):
                self.run_main("--notes", str(self.draft))
        self.assertEqual([], self.http.events)

    def test_stale_apk_version_name_stops_offline(self):
        self.identity["versionName"] = "1.8.0"
        with self.assertRaisesRegex(SystemExit, "versionName"):
            self.run_main("--dry-run")
        self.assertEqual([], self.http.events)

    def test_stale_apk_version_code_stops_even_with_rollback_override(self):
        for code in (10800, 10802):
            with self.subTest(versionCode=code):
                self.identity["versionCode"] = code
                with self.assertRaisesRegex(SystemExit, "versionCode"):
                    self.run_main("--dry-run", "--allow-nonmonotonic")
        self.assertEqual([], self.http.events)

    def test_check_uses_release_body_and_tolerates_only_timestamp(self):
        with self.unchanged():
            self.assertEqual(0, self.run_main("--check"))
        self.assertEqual(self.plain_notes("Published improvements."), self.preview()["notes"])
        self.assertIn("verified: app-latest.json", self.output.getvalue())
        self.assertTrue(any(url.startswith(FEED_URL + "?cb=") for method, url, _ in self.http.events))

    def test_check_with_draft_is_read_only_even_when_release_body_differs(self):
        self.set_live_feed(notes=self.plain_notes("Draft improvements."))
        with self.unchanged():
            self.assertEqual(0, self.run_main("--check", "--notes", str(self.draft)))
        self.assertEqual(self.plain_notes("Draft improvements."), self.preview()["notes"])

    def test_check_refuses_apk_byte_mismatch_without_writes(self):
        self.http.downloads[APK_URL] = b"stale artifact"
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "与本地文件不一致"):
            self.run_main("--check", "--notes", str(self.draft))

    def test_check_refuses_feed_mismatch_without_writes(self):
        self.set_live_feed(sha256="0" * 64)
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "不同的字段：sha256"):
            self.run_main("--check")
        feed_reads = [url for method, url, _ in self.http.events if url.startswith(FEED_URL + "?cb=")]
        self.assertEqual(6, len(feed_reads))

    def test_check_missing_feed_release_does_not_create_it(self):
        del self.http.releases["app"]
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "app release 不存在"):
            self.run_main("--check")

    def test_check_missing_apk_asset_does_not_upload_it(self):
        self.http.releases[TAG]["assets"] = []
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "找不到资产"):
            self.run_main("--check")

    def test_body_notes_are_default_for_publish_and_not_patched(self):
        self.assertEqual(0, self.run_main())
        feed = json.loads(self.http.downloads[FEED_URL])
        self.assertEqual(self.plain_notes("Published improvements."), feed["notes"])
        self.assertEqual(self.body, self.http.releases[TAG]["body"])
        self.assertEqual(self.apk_bytes, self.http.downloads[APK_URL])
        self.assertEqual(self.digest, feed["sha256"])
        self.assertEqual(10801, feed["versionCode"])
        self.assertFalse(any(method == "PATCH" for method, _, _ in self.http.events))
        # Equality against the live code is deliberately allowed for same-version refreshes.
        self.assertEqual(["DELETE", "POST", "DELETE", "POST"], [e[0] for e in self.http.mutations])
        rollback_read = next(i for i, (_, url, _) in enumerate(self.http.events) if url.startswith(FEED_URL))
        first_write = next(i for i, (method, _, _) in enumerate(self.http.events) if method != "GET")
        self.assertLess(rollback_read, first_write)

    def test_publish_with_draft_patches_body_and_uses_same_text_for_feed(self):
        self.http.releases[TAG]["body"] = "Previous release notes have no artifact metadata."
        self.assertEqual(0, self.run_main("--notes", str(self.draft)))
        self.assertEqual(self.draft.read_text(encoding="utf-8"), self.http.releases[TAG]["body"])
        feed = json.loads(self.http.downloads[FEED_URL])
        self.assertEqual(self.plain_notes("Draft improvements."), feed["notes"])
        writes = self.http.mutations
        self.assertEqual(["DELETE", "POST", "PATCH", "DELETE", "POST"], [e[0] for e in writes])
        self.assertEqual(f"{API_URL}/releases/10", writes[2][1])
        self.assertEqual({"body": self.notes("Draft improvements.")}, json.loads(writes[2][2]))
        self.assertEqual(self.apk_bytes, writes[1][2])
        self.assertEqual(self.http.downloads[FEED_URL], writes[-1][2])

    def test_blank_release_body_stops_without_fallback_or_writes(self):
        self.http.releases[TAG]["body"] = " \n"
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "正文是空"):
            self.run_main()

    def test_missing_release_stops_without_creating_it(self):
        del self.http.releases[TAG]
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "release 不存在"):
            self.run_main("--notes", str(self.draft))

    def test_notes_mismatch_in_body_stops_before_mutations(self):
        self.http.releases[TAG]["body"] = self.body.replace(self.digest, "0" * 64)
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "不是同一份构建"):
            self.run_main()

    def test_body_without_exactly_one_byte_count_cannot_be_published(self):
        for body in (
            f"SHA-256：`{self.digest}`",
            self.body + f"字节数：`{len(self.apk_bytes)}`\n",
        ):
            with self.subTest(body=body):
                self.http.releases[TAG]["body"] = body
                with self.unchanged(), self.assertRaisesRegex(SystemExit, "字节数"):
                    self.run_main()

    def test_rollback_is_refused_before_delete_upload_or_notes_patch(self):
        self.set_live_feed(versionCode=10802)
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "低于线上"):
            self.run_main("--notes", str(self.draft))

    def test_unreadable_existing_feed_stops_publish_before_mutations(self):
        for status in (403, 404, 503):
            with self.subTest(status=status):
                self.http.events.clear()
                self.set_live_feed(versionCode=10802)
                self.http.failures[("GET", FEED_URL)] = status
                with self.unchanged(), self.assertRaisesRegex(SystemExit, "无法确认线上版本代码"):
                    self.run_main("--notes", str(self.draft))

    def test_malformed_existing_feed_stops_publish_before_mutations(self):
        for body in (
            b"not json", b"[]", b"null", b"{}", b'{"versionCode": "unknown"}',
            b'{"versionCode": true}', b'{"versionCode": 10801.5}', b'{"versionCode": -1}',
        ):
            with self.subTest(body=body):
                self.http.events.clear()
                self.http.downloads[FEED_URL] = body
                with self.unchanged(), self.assertRaisesRegex(SystemExit, "无法确认线上版本代码"):
                    try:
                        self.run_main("--notes", str(self.draft))
                    except Exception as error:
                        self.fail(f"Malformed feed needs a controlled refusal: {error}")

    def test_temporary_feed_failure_retries_then_refuses_known_rollback(self):
        self.set_live_feed(versionCode=10802)
        original_urlopen = self.http.urlopen
        feed_reads = 0

        def once_unavailable(request, timeout):
            nonlocal feed_reads
            if request.get_method() == "GET" and request.full_url.startswith(FEED_URL):
                feed_reads += 1
                if feed_reads == 1:
                    self.http.events.append(("GET", request.full_url, None))
                    raise urllib.error.HTTPError(
                        request.full_url, 429, "rate limited", {"Retry-After": "7"}, io.BytesIO(b"{}"),
                    )
            return original_urlopen(request, timeout)

        with mock.patch.object(publisher.urllib.request, "urlopen", side_effect=once_unavailable), \
                mock.patch.object(publisher.time, "sleep") as sleep, \
                self.unchanged(), self.assertRaisesRegex(SystemExit, "低于线上"):
            self.run_main("--notes", str(self.draft))
        self.assertEqual(2, feed_reads)
        sleep.assert_called_once_with(7.0)

    def test_missing_feed_release_allows_initial_publication(self):
        del self.http.releases["app"]
        del self.http.downloads[FEED_URL]
        self.assertEqual(0, self.run_main())
        self.assertEqual(10801, json.loads(self.http.downloads[FEED_URL])["versionCode"])
        self.assertTrue(any(method == "POST" and url == API_URL + "/releases"
                            for method, url, _ in self.http.events))

    def test_missing_feed_asset_allows_initial_publication(self):
        self.http.releases["app"]["assets"] = []
        del self.http.downloads[FEED_URL]
        self.assertEqual(0, self.run_main())
        self.assertEqual(10801, json.loads(self.http.downloads[FEED_URL])["versionCode"])
        self.assertFalse(any(method == "POST" and url == API_URL + "/releases"
                             for method, url, _ in self.http.events))

    def test_explicit_rollback_override_still_publishes_matching_local_apk(self):
        self.set_live_feed(versionCode=10802)
        self.assertEqual(0, self.run_main("--notes", str(self.draft), "--allow-nonmonotonic"))
        self.assertEqual(10801, json.loads(self.http.downloads[FEED_URL])["versionCode"])

    def test_newer_version_than_live_feed_can_be_published(self):
        self.set_live_feed(versionCode=10800)
        self.assertEqual(0, self.run_main())
        self.assertEqual(10801, json.loads(self.http.downloads[FEED_URL])["versionCode"])

    def test_failed_apk_delete_does_not_upload_patch_or_touch_feed(self):
        self.http.failures[("DELETE", f"{API_URL}/releases/assets/11")] = 403
        before = self.http.downloads.copy()
        with self.assertRaisesRegex(SystemExit, "删除旧资产"):
            self.run_main("--notes", str(self.draft))
        self.assertEqual(["DELETE"], [e[0] for e in self.http.mutations])
        self.assertEqual(before, self.http.downloads)
        self.assertEqual(self.body, self.http.releases[TAG]["body"])

    def test_failed_apk_upload_does_not_patch_notes_or_publish_feed(self):
        upload = self.http.releases[TAG]["upload_url"].split("{")[0]
        self.http.failures[("POST", upload)] = 403
        feed_before = self.http.downloads[FEED_URL]
        with self.assertRaisesRegex(SystemExit, "上传 .* 失败"):
            self.run_main("--notes", str(self.draft))
        self.assertEqual(["DELETE", "POST"], [e[0] for e in self.http.mutations])
        self.assertEqual(feed_before, self.http.downloads[FEED_URL])
        self.assertEqual(self.body, self.http.releases[TAG]["body"])

    def test_failed_notes_patch_does_not_publish_feed(self):
        self.http.failures[("PATCH", f"{API_URL}/releases/10")] = 403
        feed_before = self.http.downloads[FEED_URL]
        with self.assertRaisesRegex(SystemExit, "更新发行说明失败"):
            self.run_main("--notes", str(self.draft))
        self.assertEqual(["DELETE", "POST", "PATCH"], [e[0] for e in self.http.mutations])
        self.assertEqual(feed_before, self.http.downloads[FEED_URL])

    def test_local_signature_failure_stops_before_network(self):
        for signer, status in (("0" * 64, 0), ("", 0), (self.signer, 1)):
            with self.subTest(signer=signer, status=status):
                self.signer = signer
                self.signature_status = status
                with self.unchanged(), self.assertRaises(SystemExit):
                    self.run_main()
        self.assertEqual([], self.http.events)

    def test_wrong_apk_package_stops_before_network(self):
        self.identity["packageName"] = "com.relaytester.app.debug"
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "不是发布包名"):
            self.run_main()
        self.assertEqual([], self.http.events)

    def test_missing_wording_stops_before_network(self):
        self.apk.write_bytes(apk_fixture(wording=False))
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "缺少最终文案"):
            self.run_main()
        self.assertEqual([], self.http.events)

    def test_bad_native_alignment_stops_before_network(self):
        self.apk.write_bytes(apk_fixture(alignment=0x1000))
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "16 KB 页要求"):
            self.run_main()
        self.assertEqual([], self.http.events)

    def test_unconverted_markdown_in_draft_stops_before_network(self):
        self.draft.write_text(self.notes("> Unsupported blockquote"), encoding="utf-8")
        with self.unchanged(), self.assertRaisesRegex(SystemExit, "未转换"):
            self.run_main("--notes", str(self.draft))
        self.assertEqual([], self.http.events)


class NotesIdentityTests(unittest.TestCase):
    def test_missing_byte_count_is_rejected(self):
        notes = f"SHA-256：`{ABC_DIGEST}`\n".encode("utf-8")
        with self.assertRaisesRegex(SystemExit, "字节数"):
            publisher.verify_notes_identity(notes, b"abc", "draft")

    def test_duplicate_byte_counts_are_rejected_even_when_both_match(self):
        for second_size in (3, 4):
            with self.subTest(second_size=second_size):
                notes = f"SHA-256：`{ABC_DIGEST}`\n字节数：`3`\n字节数：`{second_size}`\n".encode("utf-8")
                with self.assertRaisesRegex(SystemExit, "字节数"):
                    publisher.verify_notes_identity(notes, b"abc", "draft")

    def test_matching_digest_and_size_accept_colon_styles_and_hex_case(self):
        for colon in (":", "："):
            notes = f"SHA-256{colon} `{ABC_DIGEST.upper()}`\n字节数{colon} `3`\n".encode("utf-8")
            with self.subTest(colon=colon), contextlib.redirect_stdout(io.StringIO()):
                publisher.verify_notes_identity(notes, b"abc", "draft")

    def test_missing_duplicate_or_mismatched_digest_is_rejected(self):
        for digest_lines in (
            "",
            f"SHA-256：`{ABC_DIGEST}`\nSHA-256：`{ABC_DIGEST}`",
            "SHA-256：`" + "0" * 64 + "`",
        ):
            notes = f"{digest_lines}\n字节数：`3`\n".encode("utf-8")
            with self.subTest(digests=digest_lines), self.assertRaisesRegex(SystemExit, "SHA-256"):
                publisher.verify_notes_identity(notes, b"abc", "draft")

    def test_mismatched_byte_count_is_rejected(self):
        notes = f"SHA-256：`{ABC_DIGEST}`\n字节数：`4`\n".encode("utf-8")
        with self.assertRaisesRegex(SystemExit, "不是同一份构建"):
            publisher.verify_notes_identity(notes, b"abc", "draft")


class NotesConversionTests(unittest.TestCase):
    def test_supported_markdown_becomes_readable_plain_text(self):
        notes = (
            "\n# Summary\r\n\r\n## Changes\r\n"
            "- **Bold** and __emphasis__\n* `code` and [site](https://example.invalid)\n"
            "+ More\n\n\nParagraph.\n\n"
        ).encode("utf-8")
        self.assertEqual(
            "Summary\n\nChanges\n· Bold and emphasis\n· code and site\n· More\n\nParagraph.",
            publisher.notes_for_feed(notes),
        )

    def test_exactly_4000_characters_survive_without_truncation(self):
        text = "文" * 4000
        self.assertEqual(text, publisher.notes_for_feed(text.encode("utf-8")))
        publisher.verify_feed_notes(text, "fixture")

    def test_long_single_line_reserves_space_for_ellipsis(self):
        self.assertEqual("文" * 3998 + "\n…", publisher.notes_for_feed(("文" * 4001).encode("utf-8")))

    def test_long_notes_prefer_a_complete_line_near_limit(self):
        notes = ("A" * 2100 + "\n" + "B" * 2100).encode("utf-8")
        self.assertEqual("A" * 2100 + "\n…", publisher.notes_for_feed(notes))

    def test_feed_gate_refuses_empty_over_limit_and_unconverted_markdown(self):
        for text in (" \n", "x" * 4001, "**unclosed", "__unclosed", "`unclosed", "> quote", "# header", "- bullet", "[x](url)"):
            with self.subTest(text=text[:40]), self.assertRaises(SystemExit):
                publisher.verify_feed_notes(text, "fixture")


class FeedReadbackTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
        self.stack.enter_context(mock.patch.object(publisher.time, "sleep"))
        self.manifest = {
            "formatVersion": 1,
            "packageName": "com.relaytester.app",
            "versionCode": 10801,
            "versionName": "1.8.1",
            "apkUrl": APK_URL,
            "sizeBytes": 3,
            "sha256": ABC_DIGEST,
            "minSdk": 26,
            "notesUrl": f"{REPO_URL}/releases/tag/{TAG}",
            "notes": "Readable release notes.",
            "publishedAt": "2026-01-02T00:00:00+00:00",
        }
        self.manifest_bytes = (json.dumps(self.manifest, indent=2) + "\n").encode("utf-8")
        self.release = release_fixture("app", 20, "Feed", "app-latest.json", 21, self.manifest_bytes)
        self.responses = []
        self.urls = []
        self.stack.enter_context(mock.patch.object(publisher.urllib.request, "urlopen", side_effect=self.urlopen))

    def urlopen(self, request, timeout):
        self.assertEqual("GET", request.get_method())
        self.assertTrue(request.full_url.startswith(FEED_URL + "?cb="))
        self.urls.append(request.full_url)
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return FakeGitHub.response(response)

    def verify(self, **options):
        return publisher.verify_app_manifest(
            self.release, self.manifest_bytes, self.manifest, wait_seconds=0, **options
        )

    def test_check_accepts_timestamp_and_json_formatting_difference(self):
        online = dict(self.manifest, publishedAt="2026-01-01T00:00:00+00:00")
        self.responses = [json.dumps(online).encode("utf-8")]
        result = self.verify(tolerate_published_at=True, attempts=1)
        self.assertEqual(online, result)

    def test_check_rejects_every_other_field_difference(self):
        changes = {
            "formatVersion": 2,
            "packageName": "com.relaytester.app.debug",
            "versionCode": 10800,
            "versionName": "1.8.0",
            "apkUrl": APK_URL + ".old",
            "sizeBytes": 4,
            "sha256": "0" * 64,
            "minSdk": 27,
            "notesUrl": f"{REPO_URL}/releases/tag/v1.8.0",
            "notes": "Stale notes.",
            "unexpected": None,
        }
        for field, value in changes.items():
            with self.subTest(field=field):
                self.responses = [json.dumps(dict(self.manifest, **{field: value})).encode("utf-8")]
                with self.assertRaisesRegex(SystemExit, "回读校验失败"):
                    self.verify(tolerate_published_at=True, attempts=1)

    def test_publish_requires_exact_bytes_not_semantic_equivalence(self):
        self.responses = [json.dumps(self.manifest).encode("utf-8")]
        with self.assertRaisesRegex(SystemExit, "回读校验失败"):
            self.verify(attempts=1)

    def test_readback_retries_transport_error_and_stale_bytes_then_succeeds(self):
        self.responses = [urllib.error.URLError("fixture timeout"), b"stale", self.manifest_bytes]
        self.assertEqual(self.manifest, self.verify(attempts=3))
        self.assertEqual(3, len(self.urls))

    def test_unreadable_or_nonobject_feed_is_rejected(self):
        for payload in (b"[]", b"null", b"not json", b"\xff"):
            with self.subTest(payload=payload):
                self.responses = [payload]
                with self.assertRaisesRegex(SystemExit, "回读校验失败"):
                    self.verify(tolerate_published_at=True, attempts=1)

    def test_persistent_network_failure_exhausts_bounded_readback(self):
        self.responses = [urllib.error.URLError("fixture timeout")] * 2
        with self.assertRaisesRegex(SystemExit, "未能完成.*2"):
            self.verify(attempts=2)
        self.assertEqual(2, len(self.urls))

    def test_missing_feed_asset_stops_without_http(self):
        self.release["assets"] = []
        with self.assertRaisesRegex(SystemExit, "找不到资产"):
            self.verify(attempts=1)
        self.assertEqual([], self.urls)


if __name__ == "__main__":
    unittest.main()
