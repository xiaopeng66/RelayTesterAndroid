import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]
WORKSPACE_TMP = PROJECT.parents[1] / "tmp" if os.name == "nt" else Path(tempfile.gettempdir())


def checkout_script(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        if "- name: Check out" not in line:
            continue
        for start in range(index + 1, len(lines)):
            if lines[start].startswith("      - name:"):
                break
            if not lines[start].startswith("        run:"):
                continue
            inline = lines[start].partition("run:")[2].strip()
            if inline != "|":
                return inline
            script = []
            for body in lines[start + 1:]:
                if body.strip() and not body.startswith("          "):
                    break
                script.append(body[10:] if body else "")
            return "\n".join(script)
    raise AssertionError(f"No checkout step found in {path.name}")


class WorkflowCheckoutTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=WORKSPACE_TMP, prefix="checkout-gate-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / "source.git"
        self.source.mkdir()
        self.git(self.source, "init", "--quiet", "--initial-branch=main")
        for name in ("app/build.gradle.kts", "tools/update_fingerprint_package.py", "tools/retry_failed_runs.py"):
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("fixture\n", encoding="utf-8")
        self.git(self.source, "add", ".")
        self.git(self.source, "commit", "--quiet", "-m", "Original workflow commit")
        self.original = self.git(self.source, "rev-parse", "HEAD").stdout.strip()
        (self.source / "newer.txt").write_text("Newer branch tip\n", encoding="utf-8")
        self.git(self.source, "add", ".")
        self.git(self.source, "commit", "--quiet", "-m", "Branch advanced after original run")

    def git(self, directory, *args):
        return subprocess.run(
            ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
             "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false",
             "-C", str(directory), *args],
            check=True, text=True, capture_output=True,
        )

    def test_workflows_check_out_the_run_commit_after_the_branch_advances(self):
        for name in ("verify-app.yml", "update-detection-package.yml", "retry-failed-detection-package.yml"):
            with self.subTest(workflow=name):
                run_root = self.root / name
                run_root.mkdir()
                script = checkout_script(PROJECT / ".github/workflows" / name)
                script_path = run_root / "checkout.sh"
                script_path.write_text(script, encoding="utf-8")
                result = subprocess.run(
                    [shutil.which("bash"), script_path.as_posix()], cwd=run_root,
                    env={**os.environ,
                         "GITHUB_SERVER_URL": self.root.as_posix(),
                         "GITHUB_REPOSITORY": "source", "GITHUB_REF": "refs/heads/main",
                         "GITHUB_SHA": self.original},
                    text=True, capture_output=True,
                )
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                checkout = run_root if name == "verify-app.yml" else run_root / "work"
                actual = self.git(checkout, "rev-parse", "HEAD").stdout.strip()
                self.assertEqual(self.original, actual, f"{name} checked out a newer commit")


if __name__ == "__main__":
    unittest.main()
