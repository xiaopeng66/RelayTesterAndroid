#!/usr/bin/env python3
"""One place that decides what is worth retrying, and how long to wait.

The publish chain talks to three flaky things in a row: the GitHub REST API, the
release-asset CDN, and raw.githubusercontent.com. All three fail transiently now and
then — a 503 from the asset CDN seconds after an upload, a connection reset while
streaming 32 MB, `429`/`403` from the API when a token is momentarily over its budget —
and every one of those used to turn a publish that had already done its job into a red
run. Waiting a few seconds and doing the same call again is the whole fix; the part that
matters is *which* answers are worth waiting for.

Two rules, both about not making things worse:

  * only explicitly transient answers are retried — the retryable HTTP statuses
    (408/425/429 and the 5xx family) and transport-level errors (DNS, TLS, timeouts,
    resets). A 401, 404 or 422 is a fact about the request: repeating it burns time and
    buries the real message, so it is returned on the first try.
  * the loop is bounded and every retry is printed with its reason. A permanent failure
    (a bug, a revoked token, a mismatched digest) still fails the job after the cap
    instead of hiding behind an endless stream of attempts.

The numbers come from the environment so a rerun can be made more patient without
editing code: `BANK_RETRY_ATTEMPTS` (default 4), `BANK_RETRY_BASE_DELAY` (2s),
`BANK_RETRY_MAX_DELAY` (30s), `BANK_RETRY_JITTER` (0.25).

No third-party dependency and no state: the caller owns the loop shape through
`run()`, and nothing here writes anything.
"""

import os
import random
import time
import urllib.error

#: Answers that mean "the other side is busy or broken right now", not "your request is
#: wrong". 429 and 403 are both used by GitHub for rate limiting; 403 is deliberately
#: *not* listed here, because a 403 with no rate-limit headers (bad scope, blocked
#: resource) is permanent — callers that can tell the two apart pass the decision through
#: `retry.run(retry_if=...)` instead.
TRANSIENT_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})

#: Transport errors worth another attempt. urllib wraps most of these in URLError, but
#: they can also surface raw (a reset mid-read arrives as ConnectionResetError).
TRANSIENT_ERRORS = (
    urllib.error.URLError,
    TimeoutError,
    ConnectionError,
    ConnectionResetError,
    OSError,
)

DEFAULT_ATTEMPTS = 4
DEFAULT_BASE_DELAY = 2.0
DEFAULT_MAX_DELAY = 30.0
DEFAULT_JITTER = 0.25

#: A `Retry-After` longer than this is not slept through: the job-level retry (a fresh
#: run) starts from a clean slate anyway, and a CI step that naps for an hour looks hung.
RETRY_AFTER_CAP = 120.0


class Transient(Exception):
    """Mark an answer as worth retrying when its raw type does not say so.

    Used where a *decision* about a response makes it transient — a `403` whose headers
    say the rate limit is exhausted, for example, is the same status as a permanent
    "your token may not do that", and only the caller can tell them apart.
    """


def _env_float(name, default):
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        print(f"{name}={raw!r} 不是数字，改用默认值 {default}")
        return default
    return value if value >= 0 else default


def _env_int(name, default):
    value = _env_float(name, float(default))
    return max(1, int(value))


class Policy:
    """How many attempts, and how long to wait between them."""

    def __init__(self, attempts=DEFAULT_ATTEMPTS, base_delay=DEFAULT_BASE_DELAY,
                 max_delay=DEFAULT_MAX_DELAY, jitter=DEFAULT_JITTER):
        self.attempts = max(1, int(attempts))
        self.base_delay = max(0.0, float(base_delay))
        self.max_delay = max(0.0, float(max_delay))
        self.jitter = min(1.0, max(0.0, float(jitter)))

    @classmethod
    def from_env(cls, prefix="BANK_RETRY"):
        """Defaults, overridable per run: BANK_RETRY_ATTEMPTS / _BASE_DELAY / _MAX_DELAY / _JITTER."""
        return cls(
            attempts=_env_int(f"{prefix}_ATTEMPTS", DEFAULT_ATTEMPTS),
            base_delay=_env_float(f"{prefix}_BASE_DELAY", DEFAULT_BASE_DELAY),
            max_delay=_env_float(f"{prefix}_MAX_DELAY", DEFAULT_MAX_DELAY),
            jitter=_env_float(f"{prefix}_JITTER", DEFAULT_JITTER),
        )

    def delay(self, attempt, retry_after=None, rand=random.random, sleep=time.sleep):
        """Seconds to wait before retry number [attempt] (0-based), then sleep them.

        The server's own `Retry-After` wins when it is present — GitHub asks for a
        specific number of seconds when it rate-limits, and arriving earlier than that
        just gets another 403. It is capped (`RETRY_AFTER_CAP`) because a server may ask
        for far longer than a CI job should sleep; anything beyond that is left to the
        job-level retry. Without the header it is an exponential backoff capped at
        `max_delay`, with a little jitter so repeated attempts do not line up.
        """
        if retry_after is not None and retry_after >= 0:
            seconds = min(float(retry_after), RETRY_AFTER_CAP)
        else:
            seconds = min(self.base_delay * (2 ** attempt), self.max_delay)
            seconds *= 1.0 + self.jitter * (rand() * 2 - 1)
        seconds = max(0.0, seconds)
        sleep(seconds)
        return seconds


def transient_status(status):
    """True when an HTTP status means "try again", false when it means "you are wrong"."""
    return status in TRANSIENT_STATUS


def retry_after_seconds(header):
    """Parse a `Retry-After` header value (seconds or HTTP-date) into seconds, or None."""
    if not header:
        return None
    text = str(header).strip()
    if text.isdigit():
        return float(text)
    try:
        from email.utils import parsedate_to_datetime

        when = parsedate_to_datetime(text)
    except (TypeError, ValueError):
        return None
    if when is None:
        return None
    return max(0.0, when.timestamp() - time.time())


def delay_for_status(policy, attempt, headers, sleep=time.sleep):
    """Wait according to a response's `Retry-After` (when it has one), then sleep."""
    retry_after = None
    for key, value in (headers or {}).items():
        if str(key).lower() == "retry-after":
            retry_after = retry_after_seconds(value)
            break
    return policy.delay(attempt, retry_after=retry_after, sleep=sleep)


def transient_error(error):
    """True when a raised exception is a transport hiccup rather than a real bug.

    `urllib.error.HTTPError` is a subclass of URLError, so an HTTP 4xx that reached this
    function without being turned into a status would look retryable; callers that hold a
    status must classify it with `transient_status` first (see publish_bank.request).
    """
    if isinstance(error, urllib.error.HTTPError):
        return transient_status(error.code)
    if isinstance(error, Transient):
        return True
    if isinstance(error, urllib.error.URLError):
        return True
    return isinstance(error, TRANSIENT_ERRORS)


def describe_error(error):
    """One short line for a log, without the traceback."""
    if isinstance(error, urllib.error.HTTPError):
        return f"HTTP {error.code} {error.reason}"
    if isinstance(error, urllib.error.URLError):
        return f"{getattr(error, 'reason', error)}"
    return f"{type(error).__name__}: {error}"


def run(call, policy=None, retry_if=None, label="", sleep=time.sleep, log=print):
    """Call [call] until it stops looking transient, up to `policy.attempts` times.

    [call] is called with no arguments. `retry_if(value)` decides whether a *returned*
    value is worth another attempt; a raised transient error is retried the same way and
    the last one is re-raised. The last value is always returned even when `retry_if`
    still says "transient": the caller owns fatality, so the message it prints is the one
    from its own vocabulary ("回读校验失败") instead of a generic one from here.
    """
    policy = policy or Policy.from_env()
    last = None
    for index in range(policy.attempts):
        try:
            last = call()
        except Exception as error:  # noqa: BLE001 - classification decides, not the call
            if index + 1 >= policy.attempts or not transient_error(error):
                raise
            seconds = policy.delay(index, sleep=sleep)
            log(f"{label or '调用'}失败（第 {index + 1}/{policy.attempts} 次）："
                f"{describe_error(error)}，{seconds:.1f}s 后重试")
            continue
        if index + 1 >= policy.attempts or not (retry_if and retry_if(last)):
            return last
        seconds = policy.delay(index, sleep=sleep)
        log(f"{label or '调用'}得到需要重试的结果（第 {index + 1}/{policy.attempts} 次），"
            f"{seconds:.1f}s 后重试")
    return last
