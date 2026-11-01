from __future__ import annotations

import difflib
import hashlib
import os
import re
import stat
import tempfile
from pathlib import Path


PROJECT_FILE_SUFFIXES = {
    ".bat",
    ".c",
    ".cc",
    ".cpp",
    ".cs",
    ".css",
    ".dart",
    ".go",
    ".gradle",
    ".h",
    ".hpp",
    ".htm",
    ".html",
    ".ini",
    ".ipynb",
    ".java",
    ".js",
    ".json",
    ".jsx",
    ".kt",
    ".kts",
    ".md",
    ".php",
    ".properties",
    ".ps1",
    ".py",
    ".rb",
    ".rs",
    ".sh",
    ".sql",
    ".swift",
    ".toml",
    ".ts",
    ".tsx",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}
PROJECT_SPECIAL_FILES = {
    ".editorconfig",
    ".env.example",
    ".gitattributes",
    ".gitignore",
    "dockerfile",
    "makefile",
    "requirements.txt",
}
PROJECT_HIDDEN_DIRECTORIES = {".github", ".vscode"}
PROJECT_MAX_FILE_BYTES = 256 * 1024
PROJECT_MAX_DIFF_CHARACTERS = 100_000
PROJECT_MAX_DIRECTORY_ENTRIES = 200
_WINDOWS_RESERVED_NAME = re.compile(r"^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$", re.IGNORECASE)


class ProjectFileConflictError(ValueError):
    pass


def _project_root() -> Path:
    profile = os.environ.get("USERPROFILE")
    configured_root = (Path(profile) if profile else Path.home()) / "Projects"
    if _is_link_or_reparse_point(configured_root):
        raise ValueError("The Projects folder must not be a symbolic link or junction.")
    configured_root.mkdir(parents=True, exist_ok=True)
    if _is_link_or_reparse_point(configured_root):
        raise ValueError("The Projects folder must not be a symbolic link or junction.")
    return configured_root.resolve()


def _is_link_or_reparse_point(path: Path) -> bool:
    if path.is_symlink():
        return True
    if os.name == "nt" and path.exists():
        attributes = getattr(os.lstat(path), "st_file_attributes", 0)
        reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
        return bool(attributes & reparse_flag)
    return False


def _is_within_root(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
    except ValueError:
        return False
    return True


def _validated_relative_parts(relative_path: str, *, allow_root: bool) -> tuple[str, ...]:
    if not isinstance(relative_path, str) or len(relative_path) > 2_048:
        raise ValueError("The project path is invalid or too long.")
    if not relative_path:
        if allow_root:
            return ()
        raise ValueError("Choose a project file.")
    if "\\" in relative_path or "\x00" in relative_path or any(char in "\r\n" for char in relative_path):
        raise ValueError("Use a relative project path.")
    parts = tuple(relative_path.split("/"))
    if any(part in {"", ".", ".."} for part in parts):
        raise ValueError("The project path cannot contain empty, current, or parent folders.")
    for index, part in enumerate(parts):
        if (
            any(ord(char) < 32 or char in '<>:"|?*' for char in part)
            or part.endswith((" ", "."))
            or _WINDOWS_RESERVED_NAME.fullmatch(part)
        ):
            raise ValueError("The project path contains a name Windows cannot safely open.")
        if part.startswith("."):
            is_allowed_hidden_directory = part.casefold() in PROJECT_HIDDEN_DIRECTORIES
            is_allowed_hidden_file = index == len(parts) - 1 and part.casefold() in PROJECT_SPECIAL_FILES
            if not (is_allowed_hidden_directory or is_allowed_hidden_file):
                raise ValueError("Hidden files and folders are not available in the Tot project browser.")
    return parts


def _resolve_project_path(relative_path: str, *, allow_root: bool = False) -> tuple[Path, Path]:
    root = _project_root()
    parts = _validated_relative_parts(relative_path, allow_root=allow_root)
    target = root.joinpath(*parts).resolve()
    if not _is_within_root(target, root):
        raise ValueError("The project path must stay inside your Projects folder.")
    current = root
    for part in parts:
        current = current / part
        if _is_link_or_reparse_point(current):
            raise ValueError("Symbolic links are not available in the Tot project browser.")
    return root, target


def _is_editable_file(path: Path) -> bool:
    return path.name.casefold() in PROJECT_SPECIAL_FILES or path.suffix.casefold() in PROJECT_FILE_SUFFIXES


def list_project_entries(relative_path: str = "") -> dict[str, object]:
    root, directory = _resolve_project_path(relative_path, allow_root=True)
    if not directory.is_dir():
        raise FileNotFoundError("That Projects folder does not exist.")
    entries = []
    for child in sorted(directory.iterdir(), key=lambda item: (not item.is_dir(), item.name.casefold())):
        if child.name.casefold() == ".git" or _is_link_or_reparse_point(child):
            continue
        relative_path = child.relative_to(root).as_posix()
        try:
            _validated_relative_parts(relative_path, allow_root=False)
        except ValueError:
            continue
        resolved = child.resolve()
        if not _is_within_root(resolved, root):
            continue
        if child.is_dir():
            entries.append({"name": child.name, "path": relative_path, "type": "directory", "size": 0})
        elif child.is_file() and _is_editable_file(child):
            size = child.stat().st_size
            entries.append({
                "name": child.name,
                "path": relative_path,
                "type": "file",
                "size": size,
                "editable": size <= PROJECT_MAX_FILE_BYTES,
            })
        if len(entries) >= PROJECT_MAX_DIRECTORY_ENTRIES:
            break
    return {
        "path": directory.relative_to(root).as_posix() if directory != root else "",
        "entries": entries,
    }


def read_project_file(relative_path: str) -> dict[str, str]:
    root, file_path = _resolve_project_path(relative_path)
    if not file_path.is_file() or not _is_editable_file(file_path):
        raise FileNotFoundError("That editable project file was not found.")
    if file_path.stat().st_size > PROJECT_MAX_FILE_BYTES:
        raise ValueError("That file is too large to edit in Tot. Use Remote VS Code instead.")
    content_bytes = file_path.read_bytes()
    if len(content_bytes) > PROJECT_MAX_FILE_BYTES:
        raise ValueError("That file is too large to edit in Tot. Use Remote VS Code instead.")
    try:
        content = content_bytes.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("Tot can edit UTF-8 text files only.") from error
    return {
        "path": file_path.relative_to(root).as_posix(),
        "content": content,
        "sha256": hashlib.sha256(content_bytes).hexdigest(),
    }


def preview_project_file(relative_path: str, content: str, expected_sha256: str) -> dict[str, str]:
    if not isinstance(content, str):
        raise ValueError("The edited file content must be text.")
    if not isinstance(expected_sha256, str) or re.fullmatch(r"[a-fA-F0-9]{64}", expected_sha256) is None:
        raise ValueError("The project file revision is invalid.")
    original = read_project_file(relative_path)
    if original["sha256"] != expected_sha256:
        raise ProjectFileConflictError("This file changed on the PC. Reload it before reviewing or saving.")
    try:
        edited_bytes = content.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ValueError("The edited file contains invalid Unicode text.") from error
    if len(edited_bytes) > PROJECT_MAX_FILE_BYTES:
        raise ValueError("The edited file is too large for Tot to save.")
    diff = "".join(
        difflib.unified_diff(
            original["content"].splitlines(keepends=True),
            content.splitlines(keepends=True),
            fromfile=f"a/{original['path']}",
            tofile=f"b/{original['path']}",
        )
    )
    if len(diff) > PROJECT_MAX_DIFF_CHARACTERS:
        raise ValueError("This diff is too large to review in Tot. Use Remote VS Code instead.")
    return {"path": original["path"], "diff": diff, "sha256": original["sha256"]}


def save_project_file(relative_path: str, content: str, expected_sha256: str) -> dict[str, str]:
    preview = preview_project_file(relative_path, content, expected_sha256)
    if not preview["diff"]:
        raise ValueError("There are no changes to save.")
    root, file_path = _resolve_project_path(relative_path)
    file_mode = stat.S_IMODE(file_path.stat().st_mode)
    if not file_mode & stat.S_IWUSR:
        raise PermissionError("That project file is read-only on the PC.")

    content_bytes = content.encode("utf-8")
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=".ki-edit-",
            suffix=".tmp",
            dir=file_path.parent,
            delete=False,
        ) as temporary_file:
            temporary_file.write(content_bytes)
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
            temporary_path = Path(temporary_file.name)
        os.chmod(temporary_path, file_mode)
        current = read_project_file(relative_path)
        if current["sha256"] != expected_sha256:
            raise ProjectFileConflictError("This file changed on the PC. Reload it before reviewing or saving.")
        os.replace(temporary_path, file_path)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return {
        "path": file_path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(content_bytes).hexdigest(),
    }
