#!/usr/bin/env python3
"""Safely materialize a BUILD evaluator ZIP into a disposable workspace."""

from __future__ import annotations

import argparse
import os
import pathlib
import stat
import sys
import zipfile


def extract(archive: pathlib.Path, destination: pathlib.Path) -> None:
    root = destination.resolve()
    root.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(archive) as source:
        for info in source.infolist():
            name = info.filename.replace("\\", "/")
            target = (root / name).resolve()
            if os.path.commonpath((str(root), str(target))) != str(root):
                raise ValueError(f"unsafe archive path: {info.filename}")

            mode = (info.external_attr >> 16) & 0o170000
            if mode == stat.S_IFLNK:
                raise ValueError(f"symlink archive entry is not allowed: {info.filename}")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue

            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(info) as input_stream, target.open("wb") as output_stream:
                while chunk := input_stream.read(1024 * 1024):
                    output_stream.write(chunk)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=pathlib.Path)
    parser.add_argument("destination", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        extract(args.archive, args.destination)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
