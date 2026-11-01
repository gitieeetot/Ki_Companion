from __future__ import annotations

import subprocess
from pathlib import Path


_GIT_TIMEOUT_SECONDS = 60
_MAX_GIT_OUTPUT = 24_000


def resolve_repository(directory: str | Path) -> Path:
    """Resolve an OpenCode working directory to its containing Git repository."""
    working_directory = Path(directory)
    if not working_directory.is_absolute():
        raise ValueError("This OpenCode session does not have a verified local folder.")
    try:
        working_directory = working_directory.resolve(strict=True)
    except OSError as error:
        raise ValueError("The OpenCode project folder is no longer available.") from error
    if not working_directory.is_dir():
        raise ValueError("The OpenCode project folder is not a directory.")

    result = _run_git(working_directory, ["rev-parse", "--show-toplevel"], timeout=8)
    repository = Path(result.stdout.strip()).resolve(strict=True)
    try:
        working_directory.relative_to(repository)
    except ValueError as error:
        raise ValueError("The OpenCode folder is outside its reported Git repository.") from error
    return repository


def run_git_operation(
    repository: str | Path,
    operation: str,
    commit_message: str = "",
) -> dict[str, str]:
    """Run one fixed, repository-scoped Git operation."""
    root = Path(repository).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("The verified Git repository is no longer available.")

    if operation == "status":
        status = _run_git(root, ["status", "--short", "--branch"]).stdout.strip()
        return {"status": status, "output": ""}
    if operation == "diff":
        output = _run_git(
            root,
            ["diff", "--no-ext-diff", "--no-textconv", "--unified=2", "--"],
        ).stdout
        staged = _run_git(
            root,
            ["diff", "--cached", "--no-ext-diff", "--no-textconv", "--unified=2", "--"],
        ).stdout
        combined = "\n".join(part for part in (output, staged) if part).strip()
        return {
            "status": _git_status(root),
            "output": _limit_output(combined or "No tracked changes to show."),
        }
    if operation == "stage_all":
        output = _run_git(root, ["add", "--all"]).stdout.strip()
        return {"status": _git_status(root), "output": output or "All changes staged."}
    if operation == "commit":
        message = commit_message.strip()
        if not message or len(message) > 200:
            raise ValueError("Enter a commit message between 1 and 200 characters.")
        result = _run_git(root, ["commit", "-m", message])
        return {"status": _git_status(root), "output": result.stdout.strip()}
    if operation == "pull":
        if _run_git(root, ["status", "--porcelain"]).stdout.strip():
            raise ValueError("Pull is blocked while this repository has local changes. Commit or discard them first.")
        result = _run_git(root, ["pull", "--ff-only"])
        return {"status": _git_status(root), "output": result.stdout.strip() or "Repository is up to date."}
    if operation == "push":
        result = _run_git(root, ["push"])
        return {"status": _git_status(root), "output": result.stdout.strip() or "Push completed."}
    raise ValueError("Choose a supported Git operation.")


def _git_status(repository: Path) -> str:
    return _run_git(repository, ["status", "--short", "--branch"]).stdout.strip()


def _run_git(
    repository: Path,
    arguments: list[str],
    timeout: int = _GIT_TIMEOUT_SECONDS,
) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            ["git", *arguments],
            cwd=str(repository),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
            shell=False,
        )
    except FileNotFoundError as error:
        raise OSError("Git was not found. Install Git for Windows and make sure it is available on PATH.") from error
    except subprocess.TimeoutExpired as error:
        raise TimeoutError("Git did not respond within 60 seconds. Check the connection and try again.") from error
    except OSError as error:
        raise OSError(f"Could not run Git: {error}") from error
    if result.returncode:
        message = (result.stderr or result.stdout).strip()
        raise ValueError(message or f"Git exited with code {result.returncode}.")
    return result


def _limit_output(value: str) -> str:
    if len(value) <= _MAX_GIT_OUTPUT:
        return value
    return value[:_MAX_GIT_OUTPUT] + "\n\n[Diff truncated at 24,000 characters.]"
