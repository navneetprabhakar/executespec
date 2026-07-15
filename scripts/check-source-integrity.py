#!/usr/bin/env python3
"""Fail closed when tracked source files contain NUL or binary corruption.

Generated assets, build output, and dependency trees are intentionally outside the scan.  The
check is meant to catch accidental binary/NUL insertion in files that are reviewed, compiled, or
loaded as prompts/configuration (the WorkerAgent corruption class in particular).
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


TEXT_EXTENSIONS = {
    ".java",
    ".kt",
    ".md",
    ".json",
    ".jsonl",
    ".properties",
    ".sql",
    ".sh",
    ".yml",
    ".yaml",
    ".xml",
    ".html",
    ".css",
    ".js",
    ".mjs",
    ".ts",
    ".tsx",
    ".jsx",
}
TEXT_FILENAMES = {"Dockerfile", "Makefile", "pom.xml"}
IGNORED_PARTS = {".git", "target", "node_modules", "build", "dist", "out"}


def candidate(path: Path) -> bool:
    return path.name in TEXT_FILENAMES or path.suffix.lower() in TEXT_EXTENSIONS


def scan(path: Path) -> tuple[bool, str | None]:
    try:
        data = path.read_bytes()
    except OSError as exc:
        return False, f"unreadable: {exc}"
    if b"\x00" in data:
        return False, "NUL byte"
    # A source/configuration file may contain UTF-8, tabs, and control whitespace.  A substantial
    # run of other controls is a stronger binary-corruption signal than a simple non-ASCII check.
    controls = sum(1 for byte in data if byte < 32 and byte not in (9, 10, 12, 13))
    if controls and controls * 100 > max(1, len(data)):
        return False, f"binary control bytes ({controls})"
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as exc:
        return False, f"invalid UTF-8 ({exc.start})"
    return True, None


def files(root: Path):
    for current, dirs, names in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in IGNORED_PARTS and not d.startswith("."))
        for name in sorted(names):
            path = Path(current) / name
            if candidate(path):
                yield path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.root.resolve()
    failures = []
    checked = 0
    for path in files(root):
        checked += 1
        ok, reason = scan(path)
        if not ok:
            failures.append(f"{path.relative_to(root)}: {reason}")
    if failures:
        print("Source integrity check failed:", file=sys.stderr)
        print("\n".join(f"- {failure}" for failure in failures), file=sys.stderr)
        return 1
    print(f"Source integrity OK ({checked} text source/config files checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
