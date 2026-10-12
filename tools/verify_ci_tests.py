#!/usr/bin/env python3
"""Run tool regressions or validate Android reports without accepting empty gates."""

import argparse
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET


PROJECT = Path(__file__).resolve().parents[1]


def verify_android(directory):
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise SystemExit("No Android test reports found")
    totals = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    for path in reports:
        root = ET.parse(path).getroot()
        counts = {name: int(root.get(name, "0")) for name in totals}
        if (
            any(value < 0 for value in counts.values())
            or counts["failures"] + counts["errors"] + counts["skipped"] > counts["tests"]
        ):
            raise SystemExit(f"Invalid test counts in {path.name}")
        for name, value in counts.items():
            totals[name] += value
    executed = totals["tests"] - totals["skipped"]
    print(
        f"Android tests: total={totals['tests']}, executed={executed}, "
        f"failures={totals['failures']}, errors={totals['errors']}, skipped={totals['skipped']}"
    )
    if not executed:
        raise SystemExit("No Android tests executed")
    if totals["failures"] or totals["errors"]:
        raise SystemExit("Android tests failed")
    return 0


class ExecutionCountingResult(unittest.TextTestResult):
    """Count test methods, not skipped fixture holders or individual subtests."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.executed = 0
        self._current_test = None

    def startTest(self, test):
        super().startTest(test)
        self._current_test = test
        self.executed += 1

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        if test is self._current_test:
            self.executed -= 1

    def stopTest(self, test):
        super().stopTest(test)
        self._current_test = None


def verify_python(directory):
    suite = unittest.TestLoader().discover(str(directory), pattern="test_*.py")
    if not suite.countTestCases():
        raise SystemExit("No Python tests discovered")
    result = unittest.TextTestRunner(verbosity=2, resultclass=ExecutionCountingResult).run(suite)
    executed = result.executed
    print(
        f"Python tests: total={result.testsRun}, executed={executed}, "
        f"failures={len(result.failures)}, errors={len(result.errors)}, skipped={len(result.skipped)}"
    )
    if executed <= 0:
        raise SystemExit("No Python tests executed")
    return 0 if result.wasSuccessful() else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("android", "python"))
    parser.add_argument("directory", nargs="?", type=Path)
    args = parser.parse_args()
    if args.mode == "android":
        return verify_android(args.directory or PROJECT / "app/build/test-results/testDebugUnitTest")
    return verify_python(args.directory or PROJECT / "tools/tests")


if __name__ == "__main__":
    sys.exit(main())
