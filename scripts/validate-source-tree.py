#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_DIRS = {"build", ".gradle", "__pycache__", "run", "run-data"}
FORBIDDEN_NAMES = {"problems-report.html", "realtime-sync-matrix.json"}
FORBIDDEN_SUFFIXES = {".class", ".pyc", ".pyo"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Reject generated artifacts from a RealtimeSync source tree or ZIP.")
    parser.add_argument("--archive", type=Path, help="Validate a ZIP instead of the current source tree.")
    return parser.parse_args()


def validate_name(name: str) -> list[str]:
    normalized = name.replace("\\", "/").lstrip("./")
    path = PurePosixPath(normalized)
    parts = set(path.parts)
    errors: list[str] = []
    if parts & FORBIDDEN_DIRS:
        errors.append(f"generated directory: {normalized}")
    if path.name in FORBIDDEN_NAMES:
        errors.append(f"generated file: {normalized}")
    if path.suffix.lower() in FORBIDDEN_SUFFIXES:
        errors.append(f"compiled/cache file: {normalized}")
    if path.suffix.lower() == ".jar" and normalized != "gradle/wrapper/gradle-wrapper.jar":
        errors.append(f"compiled jar: {normalized}")
    if path.name.endswith(".tmp") or path.name.endswith(".temp"):
        errors.append(f"temporary file: {normalized}")
    return errors


def git_source_names() -> list[str] | None:
    """Return tracked and non-ignored source files when Git metadata is available.

    CI tools may create ignored runtime files such as __pycache__ after checkout.
    Those files are not part of the source tree or release archive and must not
    make source validation order-dependent. Accidentally committed cache files
    are still returned by --cached and therefore remain rejected.
    """
    if not (ROOT / ".git").exists():
        return None
    result = subprocess.run(
        [
            "git",
            "-C",
            str(ROOT),
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
        ],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        return None
    return [
        entry.decode("utf-8", errors="surrogateescape")
        for entry in result.stdout.split(b"\0")
        if entry
    ]


def tree_names() -> list[str]:
    git_names = git_source_names()
    if git_names is not None:
        return git_names

    names: list[str] = []
    for path in ROOT.rglob("*"):
        if ".git" in path.parts:
            continue
        if path.is_file():
            names.append(path.relative_to(ROOT).as_posix())
    return names


def archive_names(path: Path) -> list[str]:
    if not path.is_file():
        raise SystemExit(f"ERROR: archive does not exist: {path}")
    with zipfile.ZipFile(path) as archive:
        return [name for name in archive.namelist() if not name.endswith("/")]


def main() -> int:
    args = parse_args()
    names = archive_names(args.archive) if args.archive else tree_names()
    errors = [error for name in names for error in validate_name(name)]
    if errors:
        for error in errors[:100]:
            print(f"ERROR: {error}")
        if len(errors) > 100:
            print(f"ERROR: ... and {len(errors) - 100} more")
        return 1
    scope = "archive" if args.archive else "tracked/non-ignored source tree"
    print(f"Source artifact validation passed for {len(names)} file(s) in {scope}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
