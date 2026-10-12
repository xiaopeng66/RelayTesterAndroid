import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


TOOLS = Path(__file__).resolve().parents[1]
WORKSPACE_TMP = (
    TOOLS.parents[2] / "tmp"
    if os.name == "nt"
    else Path(tempfile.gettempdir())
)


class VerifyCiTestsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=WORKSPACE_TMP, prefix="ci-gate-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def run_gate(self, mode):
        return subprocess.run(
            [sys.executable, "-B", str(TOOLS / "verify_ci_tests.py"), mode, str(self.root)],
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "PYTHONIOENCODING": "utf-8"},
        )

    def write_suite(self, *, tests, failures=0, errors=0, skipped=0):
        (self.root / "TEST-example.xml").write_text(
            f'<testsuite name="example" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}"/>',
            encoding="utf-8",
        )

    def test_android_accepts_executed_successes_and_reports_skips(self):
        self.write_suite(tests=3, skipped=1)
        result = self.run_gate("android")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("executed=2", result.stdout)
        self.assertIn("skipped=1", result.stdout)

    def test_android_rejects_absent_results(self):
        result = self.run_gate("android")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No Android test reports", result.stderr)

    def test_android_rejects_all_skipped_results(self):
        self.write_suite(tests=2, skipped=2)
        result = self.run_gate("android")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No Android tests executed", result.stderr)

    def test_android_rejects_failures_and_errors(self):
        for field in ("failures", "errors"):
            with self.subTest(field=field):
                self.write_suite(tests=2, **{field: 1})
                result = self.run_gate("android")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Android tests failed", result.stderr)

    def test_android_rejects_inconsistent_counts(self):
        self.write_suite(tests=1, skipped=2)
        result = self.run_gate("android")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Invalid test counts", result.stderr)

    def write_test(self, body):
        (self.root / "test_example.py").write_text(
            textwrap.dedent(body), encoding="utf-8"
        )

    def test_python_rejects_no_discovered_tests(self):
        result = self.run_gate("python")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No Python tests discovered", result.stderr)

    def test_python_rejects_all_skipped_tests(self):
        self.write_test('''\
            import unittest
            class Example(unittest.TestCase):
                @unittest.skip("fixture")
                def test_example(self):
                    pass
        ''')
        result = self.run_gate("python")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No Python tests executed", result.stderr)

    def test_python_rejects_module_fixture_skipped_only_suite(self):
        self.write_test('''\
            import unittest
            def setUpModule():
                raise unittest.SkipTest("fixture prerequisite unavailable")
            class Example(unittest.TestCase):
                def test_example(self):
                    self.fail("module fixture should prevent execution")
        ''')
        result = self.run_gate("python")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("executed=0", result.stdout)
        self.assertIn("No Python tests executed", result.stderr)

    def test_python_rejects_class_fixture_skipped_only_suite(self):
        self.write_test('''\
            import unittest
            class Example(unittest.TestCase):
                @classmethod
                def setUpClass(cls):
                    raise unittest.SkipTest("fixture prerequisite unavailable")
                def test_example(self):
                    self.fail("class fixture should prevent execution")
        ''')
        result = self.run_gate("python")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("executed=0", result.stdout)
        self.assertIn("No Python tests executed", result.stderr)

    def test_python_accepts_executed_test_alongside_skipped_class_fixture(self):
        self.write_test('''\
            import unittest
            class SkippedExample(unittest.TestCase):
                @classmethod
                def setUpClass(cls):
                    raise unittest.SkipTest("fixture prerequisite unavailable")
                def test_example(self):
                    self.fail("class fixture should prevent execution")
            class ExecutedExample(unittest.TestCase):
                def test_example(self):
                    self.assertEqual(4, 2 + 2)
        ''')
        result = self.run_gate("python")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("executed=1", result.stdout)

    def test_python_accepts_executed_test_with_skipped_subtests(self):
        for skips in (1, 2):
            with self.subTest(skips=skips):
                self.write_test(f'''\
                    import unittest
                    class Example(unittest.TestCase):
                        def test_example(self):
                            for value in range({skips}):
                                with self.subTest(value=value):
                                    self.skipTest("optional subcase unavailable")
                            self.assertEqual(4, 2 + 2)
                ''')
                result = self.run_gate("python")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("executed=1", result.stdout)

    def test_python_rejects_failing_tests(self):
        self.write_test('''\
            import unittest
            class Example(unittest.TestCase):
                def test_example(self):
                    self.assertEqual("expected", "wrong")
        ''')
        result = self.run_gate("python")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("FAIL:", result.stderr)

    def test_python_rejects_import_errors(self):
        self.write_test('raise RuntimeError("fixture import failure")')
        result = self.run_gate("python")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("fixture import failure", result.stderr)

    def test_python_accepts_executed_tests(self):
        self.write_test('''\
            import unittest
            class Example(unittest.TestCase):
                def test_example(self):
                    self.assertTrue(True)
        ''')
        result = self.run_gate("python")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("executed=1", result.stdout)


if __name__ == "__main__":
    unittest.main()
