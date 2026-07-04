#!/usr/bin/env python3
"""B.2 RCA: would captureDiff's exclusion globs silently drop GOLD-patch hunks?

BenchmarkGenerationService.captureDiff excludes a Python/Lite-shaped path list from the
submitted diff (tests/**, testing/**, packaging files, repro*, ...). On multi-language
SWE-bench Pro repos, gold fixes can legitimately touch e.g. Go `internal/testing/`
helper packages or JS build config — any such hunk in OUR patch is silently stripped
and scores as a model failure. This audits the dataset's gold `patch` (and optionally
`test_patch`) file paths against the EXACT exclusion list, per instance.

Usage:
  ./rca-exclusion-glob-audit.py /var/swebench/swebench_pro_sample20.json
  ./rca-exclusion-glob-audit.py swebench_pro_full.json --json

Zero-dollar, offline; only needs the dataset JSON (array of instances with 'patch').
See finding #4 in docs/plans/swe-bench-pro-harness-adversarial-assessment-2026-07-04.md.
"""

import argparse
import fnmatch
import json
import re
import sys

# Mirror of captureDiff's exclusion list (BenchmarkGenerationService.java captureDiff).
# Non-glob entries behave as git pathspec prefix matches; glob entries use glob(5) with
# '**' crossing directories. Keep in lockstep with the Java when either changes.
# NOTE: as of 2026-07-04 these mirror the NARROWED Pro list in
# BenchmarkGenerationService.diffExcludes(pro=true) — the audit of all 731 gold patches drove the
# narrowing (32 instances had gold hunks stripped by the old broad list). Keep in lockstep.
PREFIX_EXCLUDES = [
    ".agent-context",
    "__pycache__",
    ".pytest_cache",
    "*.egg-info",  # git treats bare * here as glob within any depth component
]
GLOB_EXCLUDES = [
    "appendonlydir/**",
    "**/test_*.py",
    "**/*_test.py",
    "**/tests/**",
    "**/conftest.py",
    "**/repro*",
    "**/*_repro*",
    "tmp/**",
    "**/tmp_*",
    "**/scratch*",
]

DIFF_PATH_RE = re.compile(r"^diff --git a/(.+?) b/(.+)$", re.M)


def _glob_match(path, pattern):
    """Approximate git ':(exclude,glob)' semantics with fnmatch on full path + basename tricks."""
    # fnmatch's '*' crosses '/', which over-matches vs glob(5); handle '**' by translation.
    # Translate: '**/' -> '(any dirs)', '**' at end -> anything.
    if pattern.startswith("**/"):
        rest = pattern[3:]
        if rest.endswith("/**"):
            mid = rest[:-3]
            return f"/{mid}/" in f"/{path}"  # a dir named <mid> anywhere
        # match basename or any suffix path component sequence
        return fnmatch.fnmatch(path.split("/")[-1], rest) or any(
            fnmatch.fnmatch("/".join(path.split("/")[i:]), rest) for i in range(len(path.split("/")))
        )
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3] + "/") or path == pattern[:-3]
    return fnmatch.fnmatch(path, pattern)


def excluded_by(path):
    for p in PREFIX_EXCLUDES:
        if "*" in p:
            if any(fnmatch.fnmatch(comp, p) for comp in path.split("/")):
                return f"(exclude){p}"
        elif path == p or path.startswith(p + "/"):
            return f"(exclude){p}"
    for g in GLOB_EXCLUDES:
        if _glob_match(path, g):
            return f"(exclude,glob){g}"
    return None


def paths_of(diff_text):
    out = set()
    for m in DIFF_PATH_RE.finditer(diff_text or ""):
        out.add(m.group(2))  # b/ side (post-image; covers adds+renames)
        out.add(m.group(1))
    return sorted(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset", help="SWE-bench (Pro) instances JSON array")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    with open(args.dataset) as f:
        instances = json.load(f)

    report, hit_instances = [], 0
    for inst in instances:
        iid = inst.get("instance_id", "?")
        gold_paths = paths_of(inst.get("patch", ""))
        hits = [(p, excluded_by(p)) for p in gold_paths]
        hits = [(p, why) for p, why in hits if why]
        if hits:
            hit_instances += 1
        report.append(
            {
                "instance_id": iid,
                "language": inst.get("repo_language", ""),
                "gold_files": len(gold_paths),
                "dropped": [{"path": p, "rule": why} for p, why in hits],
            }
        )

    if args.json:
        print(json.dumps(report, indent=2))
        return

    print(f"instances: {len(instances)}; instances with gold hunks DROPPED by our globs: {hit_instances}\n")
    for r in report:
        if r["dropped"]:
            print(f"!! {r['instance_id']} ({r['language']}) — {len(r['dropped'])}/{r['gold_files']} gold files dropped:")
            for d in r["dropped"]:
                print(f"     {d['path']}   <- {d['rule']}")
    if hit_instances == 0:
        print("no gold-patch paths match the exclusion globs — finding #4 is clear on this dataset")
    else:
        print(
            f"\n=> {hit_instances} instance(s) CANNOT be solved by our harness as configured:"
            " the correct hunks are stripped from the submitted diff. Narrow the globs"
            " (language-aware) in BenchmarkGenerationService.captureDiff."
        )
    sys.exit(0)


if __name__ == "__main__":
    main()
