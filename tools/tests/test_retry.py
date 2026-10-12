"""Retry transport errors without hiding permanent refusals or losing server delays."""

import io
import json
from pathlib import Path
import sys
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch
import urllib.error

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import publish_bank
import publish_release_apk
import retry
import update_fingerprint_package as updater


def http_error(status, headers=None):
    return urllib.error.HTTPError(
        "https://api.github.com/fixture", status, "fixture", headers or {}, io.BytesIO(b"{}")
    )


def response(body=b'{"ok": true}'):
    result = io.BytesIO(body)
    result.status = 200
    result.headers = {}
    return result


class RetryTest(unittest.TestCase):
    def setUp(self):
        self.policy = retry.Policy(attempts=3, base_delay=2, max_delay=30, jitter=0)

    def test_http_error_uses_retry_after_instead_of_default_backoff(self):
        call = Mock(side_effect=[http_error(429, {"Retry-After": "11"}), "complete"])
        sleep = Mock()
        self.assertEqual("complete", retry.run(call, policy=self.policy, sleep=sleep, log=lambda _: None))
        self.assertEqual(2, call.call_count)
        sleep.assert_called_once_with(11.0)

    def test_rate_limited_403_retries_but_ordinary_403_does_not(self):
        for headers in ({"Retry-After": "7"}, {"x-ratelimit-remaining": "0"}):
            with self.subTest(headers=headers):
                call = Mock(side_effect=[http_error(403, headers), "complete"])
                self.assertEqual("complete", retry.run(call, policy=self.policy, sleep=lambda _: None, log=lambda _: None))
                self.assertEqual(2, call.call_count)
        for headers in ({}, {"X-RateLimit-Remaining": "42"}):
            with self.subTest(headers=headers):
                error = http_error(403, headers)
                call = Mock(side_effect=error)
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    retry.run(call, policy=self.policy, sleep=lambda _: self.fail("must not wait"))
                self.assertIs(error, caught.exception)
                self.assertEqual(1, call.call_count)

    def test_server_errors_retry_but_permanent_client_errors_fail_fast(self):
        for status in (500, 501, 502, 503, 504, 507):
            with self.subTest(status=status):
                call = Mock(side_effect=[http_error(status), "complete"])
                self.assertEqual("complete", retry.run(call, policy=self.policy, sleep=lambda _: None, log=lambda _: None))
        for status in (400, 401, 404, 422):
            with self.subTest(status=status):
                call = Mock(side_effect=http_error(status))
                with self.assertRaises(urllib.error.HTTPError):
                    retry.run(call, policy=self.policy, sleep=lambda _: self.fail("must not wait"))
                self.assertEqual(1, call.call_count)

    def test_attempt_budget_and_exponential_wait_are_bounded(self):
        call = Mock(side_effect=TimeoutError("offline"))
        sleep = Mock()
        with self.assertRaises(TimeoutError):
            retry.run(call, policy=self.policy, sleep=sleep, log=lambda _: None)
        self.assertEqual(3, call.call_count)
        self.assertEqual([2.0, 4.0], [item.args[0] for item in sleep.call_args_list])

    def test_server_wait_is_case_insensitive_and_capped(self):
        sleep = Mock()
        self.assertEqual(120.0, retry.delay_for_status(self.policy, 0, {"retry-after": "9999"}, sleep=sleep))
        sleep.assert_called_once_with(120.0)

    def test_primary_rate_limit_reset_is_respected(self):
        sleep = Mock()
        with patch.object(retry.time, "time", return_value=1000):
            self.assertEqual(25.0, retry.delay_for_status(
                self.policy, 0, {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1025"}, sleep=sleep
            ))
        sleep.assert_called_once_with(25.0)

    def test_http_date_retry_after_is_respected(self):
        sleep = Mock()
        with patch.object(retry.time, "time", return_value=0):
            self.assertEqual(15.0, retry.delay_for_status(
                self.policy, 0, {"Retry-After": "Thu, 01 Jan 1970 00:00:15 GMT"}, sleep=sleep
            ))
        sleep.assert_called_once_with(15.0)


class NetworkCallerRetryTest(unittest.TestCase):
    def run_caller(self, caller, answers):
        policy = retry.Policy(attempts=3, base_delay=0, max_delay=0, jitter=0)
        with patch.object(retry.Policy, "from_env", return_value=policy), \
                patch.object(retry.time, "sleep") as sleep, \
                patch.object(publish_bank, "read_token", return_value="fixture"), \
                patch("urllib.request.urlopen", side_effect=answers) as transport, \
                redirect_stdout(io.StringIO()):
            if caller == "publisher":
                value = publish_bank.request("fixture", "GET", "https://api.github.com/fixture", policy=policy)
                self.assertEqual((200, b'{"ok": true}'), value)
            else:
                self.assertEqual({"ok": True}, updater.api_get("https://api.github.com/fixture"))
            return transport.call_count, sleep

    def test_both_callers_retry_503(self):
        for caller in ("publisher", "updater"):
            with self.subTest(caller=caller):
                count, sleep = self.run_caller(caller, [http_error(503), response()])
                self.assertEqual(2, count)
                sleep.assert_called_once_with(0.0)

    def test_both_callers_preserve_rate_limit_headers(self):
        for caller in ("publisher", "updater"):
            with self.subTest(caller=caller):
                count, sleep = self.run_caller(caller, [http_error(403, {"retry-after": "0"}), response()])
                self.assertEqual(2, count)
                sleep.assert_called_once_with(0.0)

    def test_updater_does_not_retry_unauthorized_403_without_headers(self):
        error = http_error(403)
        policy = retry.Policy(attempts=3, base_delay=0, jitter=0)
        with patch.object(retry.Policy, "from_env", return_value=policy), \
                patch.object(retry.time, "sleep") as sleep, \
                patch.object(publish_bank, "read_token", return_value="fixture"), \
                patch("urllib.request.urlopen", side_effect=error) as transport, \
                redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit):
                updater.api_get("https://api.github.com/fixture")
            self.assertEqual(1, transport.call_count)
            sleep.assert_not_called()


class ReadbackRetryTest(unittest.TestCase):
    """Outer readback retries must preserve HTTP decisions after single-try downloads."""

    MANIFEST = {
        "formatVersion": 1,
        "packageName": "com.relaytester.app",
        "versionCode": 10801,
        "versionName": "1.8.1",
        "sizeBytes": 3,
        "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "apkUrl": "https://example.invalid/app.apk",
        "minSdk": 26,
        "notesUrl": "https://example.invalid/release",
        "notes": "Fixture release.",
        "publishedAt": "2026-01-01T00:00:00+00:00",
    }
    MANIFEST_BYTES = json.dumps(MANIFEST).encode("utf-8")

    def readback(self, caller, *, attempts=3):
        names = ("lite-bank.bin", "latest.json") if caller == "bank" else ("app-latest.json",)
        release = {"assets": [
            {"name": name, "browser_download_url": "https://example.invalid/" + name}
            for name in names
        ]}
        if caller == "bank":
            return publish_bank.verify(release, b"bank", b"{}", attempts=attempts)
        return publish_release_apk.verify_app_manifest(
            release, self.MANIFEST_BYTES, self.MANIFEST, attempts=attempts,
        )

    def test_permanent_401_and_403_stop_after_one_read_without_waiting(self):
        for caller in ("bank", "app"):
            for status in (401, 403):
                with self.subTest(caller=caller, status=status):
                    error = http_error(status)
                    with patch("urllib.request.urlopen", side_effect=error) as transport, \
                            patch.object(retry.time, "sleep") as sleep, \
                            redirect_stdout(io.StringIO()):
                        with self.assertRaises(SystemExit) as stopped:
                            self.readback(caller)
                    self.assertNotIsInstance(stopped.exception, publish_bank.PublishedAssetMismatch)
                    self.assertEqual(1, transport.call_count)
                    sleep.assert_not_called()

    def test_rate_limit_headers_control_readback_waits(self):
        for caller in ("bank", "app"):
            for status, headers in (
                (403, {"retry-after": "60"}),
                (429, {"Retry-After": "Thu, 01 Jan 1970 00:17:40 GMT"}),
                (403, {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1060"}),
            ):
                with self.subTest(caller=caller, status=status, headers=headers):
                    with patch("urllib.request.urlopen", side_effect=http_error(status, headers)) as transport, \
                            patch.object(retry.time, "time", return_value=1000), \
                            patch.object(retry.time, "sleep") as sleep, \
                            redirect_stdout(io.StringIO()):
                        with self.assertRaises(SystemExit) as stopped:
                            self.readback(caller)
                    self.assertNotIsInstance(stopped.exception, publish_bank.PublishedAssetMismatch)
                    self.assertEqual(3, transport.call_count)
                    self.assertEqual([60.0, 60.0], [call.args[0] for call in sleep.call_args_list])

    def test_transient_errors_keep_single_attempt_downloads_and_fallback_wait(self):
        for caller in ("bank", "app"):
            for error in (http_error(503), urllib.error.URLError("offline"), TimeoutError("offline")):
                with self.subTest(caller=caller, error=type(error).__name__):
                    with patch("urllib.request.urlopen", side_effect=error) as transport, \
                            patch.object(retry.time, "sleep") as sleep, \
                            redirect_stdout(io.StringIO()):
                        try:
                            self.readback(caller)
                        except Exception as stopped:
                            self.fail(f"Transient readback escaped without exhausting its budget: {stopped}")
                        except SystemExit as stopped:
                            self.assertNotIsInstance(stopped, publish_bank.PublishedAssetMismatch)
                        else:
                            self.fail("Failed readbacks must not succeed")
                    self.assertEqual(3, transport.call_count)
                    self.assertEqual([5.0, 5.0], [call.args[0] for call in sleep.call_args_list])

    def test_eventual_cdn_404_is_retried_with_the_existing_fallback_wait(self):
        for caller in ("bank", "app"):
            with self.subTest(caller=caller):
                answers = [http_error(404), response(b"bank"), response(b"{}")] if caller == "bank" else [
                    http_error(404), response(self.MANIFEST_BYTES),
                ]
                with patch("urllib.request.urlopen", side_effect=answers) as transport, \
                        patch.object(retry.time, "sleep") as sleep, \
                        redirect_stdout(io.StringIO()):
                    self.assertEqual({} if caller == "bank" else self.MANIFEST, self.readback(caller))
                self.assertEqual(3 if caller == "bank" else 2, transport.call_count)
                sleep.assert_called_once_with(5.0)

    def test_persistent_cdn_404_preserves_missing_asset_classification(self):
        for caller in ("bank", "app"):
            with self.subTest(caller=caller):
                with patch("urllib.request.urlopen", side_effect=http_error(404)) as transport, \
                        patch.object(retry.time, "sleep") as sleep, \
                        redirect_stdout(io.StringIO()):
                    with self.assertRaises(SystemExit) as stopped:
                        self.readback(caller)
                if caller == "bank":
                    self.assertIsInstance(stopped.exception, publish_bank.PublishedAssetMismatch)
                self.assertEqual(3, transport.call_count)
                self.assertEqual([5.0, 5.0], [call.args[0] for call in sleep.call_args_list])

    def test_bank_mismatch_then_transport_error_does_not_allow_repair(self):
        answers = [response(b"stale bank"), response(b"{}"), TimeoutError("offline")]
        with patch("urllib.request.urlopen", side_effect=answers) as transport, \
                patch.object(retry.time, "sleep") as sleep, \
                redirect_stdout(io.StringIO()):
            try:
                self.readback("bank", attempts=2)
            except Exception as stopped:
                self.fail(f"Readback must report transport uncertainty: {stopped}")
            except SystemExit as stopped:
                self.assertNotIsInstance(stopped, publish_bank.PublishedAssetMismatch)
            else:
                self.fail("Uncertain assets must not count as verified")
        self.assertEqual(3, transport.call_count)
        sleep.assert_called_once_with(5.0)


if __name__ == "__main__":
    unittest.main()
