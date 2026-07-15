#!/usr/bin/env python3
"""Reconcile a Harbor Terminal-Bench job without trusting its aggregate mean.

Harbor can persist a positive verifier reward for a trial that timed out while
the adapter was waiting on a tool.  This report treats sibling exception.txt
as authoritative for timeout classification and requires an ExecuteSpec run
id for a valid model pass.  It is intentionally read-only and emits JSON for
release evidence or human review.
"""

import argparse
import glob
import hashlib
import json
import pathlib
from collections import Counter


def _read(path):
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _trial(path):
    result = json.loads(path.read_text(encoding="utf-8"))
    sibling_exception = _read(path.parent / "exception.txt")
    exception_info = result.get("exception_info") or {}
    haystack = (json.dumps(exception_info) + "\n" + sibling_exception).lower()
    reward = float(
        ((result.get("verifier_result") or {}).get("rewards") or {}).get("reward", 0)
        or 0
    )
    metadata = ((result.get("agent_result") or {}).get("metadata") or {})
    es_run_id = metadata.get("es_run_id")
    if any(marker in haystack for marker in ("agenttimeouterror", "timed out", "cancellederror")):
        outcome = "TIMEOUT"
    elif reward > 0 and es_run_id:
        outcome = "VALID_PASS"
    elif reward > 0:
        outcome = "REWARD_WITHOUT_EXECUTESPEC_RUN"
    elif result.get("verifier_result") is not None:
        outcome = "VERIFIER_FAIL"
    else:
        outcome = "UNCLASSIFIED"
    return {
        "task": result.get("task_name"),
        "trial": result.get("trial_name"),
        "reward": reward,
        "outcome": outcome,
        "esRunId": es_run_id,
        "resultSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("job_dir", type=pathlib.Path)
    args = parser.parse_args()
    trials = [_trial(pathlib.Path(path)) for path in glob.glob(str(args.job_dir / "*" / "result.json"))]
    counts = Counter(t["outcome"] for t in trials)
    valid_denominator = counts["VALID_PASS"] + counts["VERIFIER_FAIL"]
    report = {
        "jobDir": str(args.job_dir),
        "trialFiles": len(trials),
        "counts": dict(sorted(counts.items())),
        "validPasses": counts["VALID_PASS"],
        "validNonTimeoutDenominator": valid_denominator,
        "conditionalNonTimeoutRate": (
            counts["VALID_PASS"] / valid_denominator if valid_denominator else None
        ),
        "trials": sorted(trials, key=lambda t: (t.get("task") or "", t.get("trial") or "")),
    }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
