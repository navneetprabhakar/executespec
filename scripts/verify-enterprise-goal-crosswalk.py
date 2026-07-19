#!/usr/bin/env python3
"""Fail closed when the Enterprise goal register and live tracker drift.

This is intentionally dependency-free so every session can run it before a checkpoint.  The
immutable plan owns the original 113 gates; later founder-approved additions are explicit here and
in append-only plan amendments.  A requirement may remain incomplete, but it may not disappear.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT.parent / "executespec-docs" / "backend" / "plans" / "source-repository"
MASTER = DOCS / "enterprise-hardening-benchmark-master-plan-2026-07-17.md"
TRACKER = DOCS / "enterprise-hardening-benchmark-status-2026-07-17.md"
ROW = re.compile(r"^\| ([A-Z]+-\d+) \|", re.MULTILINE)


def numbered(prefix: str, end: int) -> set[str]:
    return {f"{prefix}-{index:02d}" for index in range(1, end + 1)}


EXPECTED = set().union(
    numbered("CTRL", 2),
    numbered("AUD", 7),
    numbered("BYOK", 10),
    numbered("PROJ", 18),
    numbered("CHAT", 11),
    numbered("USAGE", 10),
    numbered("SEC", 14),
    numbered("QA", 7),
    numbered("BENCH", 2),
    numbered("MODEL", 1),
    numbered("CANARY", 3),
    numbered("INFRA", 5),
    numbered("TRACK", 3),
    numbered("PAR", 3),
    numbered("TB", 4),
    numbered("DS", 6),
    numbered("GLM", 4),
    numbered("FIX", 1),
    numbered("REL", 4),
    numbered("COMM", 1),
)
AMENDMENT_ONLY = {"CTRL-02", "DS-06", "GLM-04"}
ORIGINAL = EXPECTED - AMENDMENT_ONLY


def rows(path: Path) -> tuple[str, list[str]]:
    text = path.read_text(encoding="utf-8")
    return text, ROW.findall(text)


def malformed_rows(text: str) -> list[str]:
    malformed: list[str] = []
    for line in text.splitlines():
        if ROW.match(line) and len(line.split("|")[1:-1]) != 13:
            malformed.append(line.split("|", 2)[1].strip())
    return malformed


def main() -> int:
    master_text, master_rows = rows(MASTER)
    tracker_text, tracker_rows = rows(TRACKER)
    errors: list[str] = []

    for label, values in (("master", master_rows), ("tracker", tracker_rows)):
        duplicates = sorted(key for key, count in Counter(values).items() if count != 1)
        if duplicates:
            errors.append(f"{label} duplicate requirement IDs: {duplicates}")

    master_ids, tracker_ids = set(master_rows), set(tracker_rows)
    if master_ids != ORIGINAL:
        errors.append(
            "immutable master drift: "
            f"missing={sorted(ORIGINAL - master_ids)} extra={sorted(master_ids - ORIGINAL)}"
        )
    if tracker_ids != EXPECTED:
        errors.append(
            "live tracker scope drift: "
            f"missing={sorted(EXPECTED - tracker_ids)} extra={sorted(tracker_ids - EXPECTED)}"
        )
    if not AMENDMENT_ONLY.issubset(tracker_ids):
        errors.append(f"missing amendment gates: {sorted(AMENDMENT_ONLY - tracker_ids)}")

    for label, text in (("master", master_text), ("tracker", tracker_text)):
        malformed = malformed_rows(text)
        if malformed:
            errors.append(f"{label} rows without all 13 evidence columns: {malformed}")

    if errors:
        print("Enterprise goal crosswalk: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        "Enterprise goal crosswalk: PASS "
        f"({len(master_ids)} immutable + {len(AMENDMENT_ONLY)} amended = {len(tracker_ids)} gates)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
