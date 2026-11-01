import subprocess
import tempfile
import unittest
from pathlib import Path

from ki.git_operations import resolve_repository, run_git_operation


class GitOperationsTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary_directory.name)
        self._git("init", "-b", "main")
        self._git("config", "user.name", "Ki Companion Tests")
        self._git("config", "user.email", "ki-tests@example.invalid")
        (self.repository / "tracked.txt").write_text("initial\n", encoding="utf-8")
        self._git("add", "tracked.txt")
        self._git("commit", "-m", "initial")

    def tearDown(self):
        self.temporary_directory.cleanup()

    def _git(self, *arguments):
        return subprocess.run(
            ["git", *arguments],
            cwd=self.repository,
            capture_output=True,
            text=True,
            check=True,
            shell=False,
        )

    def test_resolves_repository_from_project_subdirectory(self):
        project = self.repository / "nested"
        project.mkdir()

        self.assertEqual(resolve_repository(project), self.repository.resolve())

    def test_stages_all_and_commits_message_as_one_argument(self):
        (self.repository / "new.txt").write_text("new\n", encoding="utf-8")

        staged = run_git_operation(self.repository, "stage_all")
        self.assertIn("A  new.txt", staged["status"])

        message = "add file; do not run shell commands"
        committed = run_git_operation(self.repository, "commit", message)
        self.assertIn(message, committed["output"])
        self.assertNotIn("new.txt", committed["status"])

    def test_diff_includes_staged_and_unstaged_changes(self):
        (self.repository / "tracked.txt").write_text("changed\n", encoding="utf-8")
        (self.repository / "new.txt").write_text("new\n", encoding="utf-8")
        self._git("add", "new.txt")

        result = run_git_operation(self.repository, "diff")

        self.assertIn("tracked.txt", result["output"])
        self.assertIn("new.txt", result["output"])

    def test_pull_refuses_to_run_with_local_changes(self):
        (self.repository / "tracked.txt").write_text("local changes\n", encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "local changes"):
            run_git_operation(self.repository, "pull")

    def test_rejects_unknown_operations(self):
        with self.assertRaisesRegex(ValueError, "supported Git operation"):
            run_git_operation(self.repository, "shell")


if __name__ == "__main__":
    unittest.main()
