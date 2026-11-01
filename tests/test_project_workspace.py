import hashlib
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ki.project_workspace import (
    ProjectFileConflictError,
    list_project_entries,
    preview_project_file,
    read_project_file,
    save_project_file,
)


class ProjectWorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.user_profile = Path(self.temporary_directory.name)
        self.environment = patch.dict(os.environ, {"USERPROFILE": str(self.user_profile)})
        self.environment.start()
        self.projects = self.user_profile / "Projects"
        self.projects.mkdir()

    def tearDown(self):
        self.environment.stop()
        self.temporary_directory.cleanup()

    def test_lists_only_supported_project_files_and_folders(self):
        (self.projects / "app.py").write_text("print('hello')\n", encoding="utf-8")
        (self.projects / "secret.env").write_text("not for the editor", encoding="utf-8")
        (self.projects / "notes.pdf").write_bytes(b"not editable")
        (self.projects / "src").mkdir()
        (self.projects / ".git").mkdir()
        (self.projects / ".git" / "config").write_text("private git metadata", encoding="utf-8")

        listing = list_project_entries()

        self.assertEqual(listing["path"], "")
        self.assertEqual(
            {(entry["name"], entry["type"]) for entry in listing["entries"]},
            {("src", "directory"), ("app.py", "file")},
        )

    def test_rejects_parent_paths_and_hidden_secrets(self):
        for path in ("../outside.py", "src/../../outside.py", ".env", "src/.env", r"src\app.py"):
            with self.subTest(path=path):
                with self.assertRaises(ValueError):
                    list_project_entries(path)

    def test_reviews_and_saves_only_when_file_revision_matches(self):
        file_path = self.projects / "main.py"
        original = "print('before')\n"
        edited = "print('after')\n"
        file_path.write_bytes(original.encode("utf-8"))
        original_hash = hashlib.sha256(original.encode("utf-8")).hexdigest()

        preview = preview_project_file("main.py", edited, original_hash)
        self.assertIn("-print('before')", preview["diff"])
        self.assertIn("+print('after')", preview["diff"])

        saved = save_project_file("main.py", edited, original_hash)

        self.assertEqual(file_path.read_text(encoding="utf-8"), edited)
        self.assertEqual(saved["sha256"], hashlib.sha256(edited.encode("utf-8")).hexdigest())

    def test_refuses_to_preview_or_save_over_a_changed_file(self):
        file_path = self.projects / "main.py"
        file_path.write_bytes(b"changed elsewhere\n")

        with self.assertRaises(ProjectFileConflictError):
            preview_project_file("main.py", "new content\n", "0" * 64)
        with self.assertRaises(ProjectFileConflictError):
            save_project_file("main.py", "new content\n", "0" * 64)

    def test_reads_utf8_files_and_rejects_binary_content(self):
        (self.projects / "readme.md").write_bytes("héllo\n".encode("utf-8"))
        (self.projects / "image.png").write_bytes(b"\x89PNG")

        self.assertEqual(read_project_file("readme.md")["content"], "héllo\n")
        with self.assertRaises(FileNotFoundError):
            read_project_file("image.png")

    def test_allows_project_configuration_folders_but_not_git_metadata(self):
        workflows = self.projects / ".github" / "workflows"
        workflows.mkdir(parents=True)
        (workflows / "build.yml").write_text("name: build\n", encoding="utf-8")
        (self.projects / ".git").mkdir()

        root_entries = list_project_entries()["entries"]
        github_folder = next(entry for entry in root_entries if entry["name"] == ".github")
        workflow_entries = list_project_entries(github_folder["path"])["entries"]

        self.assertEqual([entry["name"] for entry in workflow_entries], ["workflows"])
        self.assertNotIn(".git", {entry["name"] for entry in root_entries})

    def test_does_not_follow_links_outside_projects(self):
        outside = self.user_profile / "outside"
        outside.mkdir()
        (outside / "secret.py").write_text("secret\n", encoding="utf-8")
        try:
            os.symlink(outside, self.projects / "linked", target_is_directory=True)
        except OSError as error:
            self.skipTest(f"Creating a directory symlink is unavailable: {error}")

        with self.assertRaises(ValueError):
            list_project_entries("linked")
        self.assertEqual(list_project_entries()["entries"], [])

    def test_rejects_a_projects_root_that_is_a_link(self):
        outside = self.user_profile / "outside"
        outside.mkdir()
        self.projects.rmdir()
        try:
            os.symlink(outside, self.projects, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"Creating a directory symlink is unavailable: {error}")

        with self.assertRaises(ValueError):
            list_project_entries()


if __name__ == "__main__":
    unittest.main()
