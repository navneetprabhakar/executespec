"""Read-only benchmark health and evidence monitor.

STRUCTURALLY NON-MUTATING BY DESIGN
-----------------------------------
This module has no write path. It contains no `open(..., "w")`, no `subprocess`,
no `shutil`, no `os.remove`/`rename`/`makedirs`, no HTTP client, and no ability
to signal a process. It therefore cannot stop, restart, throttle, resume or
otherwise alter a running campaign — the worst it can do is report something
inaccurate. `assert_read_only_source()` enforces that property against this
file's own bytes, and a unit test calls it, so a future edit that introduces a
mutation primitive fails the test suite rather than silently gaining authority
over a live campaign.

Existing watchers (`score-watcher.py`, `tb-tracker.py`) are suite-specific and
some of them can act. This is the one suite-agnostic reporter that cannot.

HONESTY: this reports what is on disk. It is not a verifier, it does not score,
and a green report here is not evidence that a campaign is healthy — only that
nothing it knows how to look at appears broken.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

REPORT_SCHEMA_VERSION = 1

_TRIAL_DIR_RE = re.compile(r"^(?P<task>.+?)__(?P<trial>[^/]+)$")

# Modules that grant process control, network egress, or filesystem mutation.
_FORBIDDEN_IMPORTS = {
    "subprocess",
    "shutil",
    "signal",
    "socket",
    "requests",
    "urllib.request",
    "http.client",
    "multiprocessing",
}
# Attribute/function names that mutate the filesystem or a process.
_FORBIDDEN_CALLS = {
    "remove",
    "unlink",
    "rename",
    "replace",
    "rmdir",
    "makedirs",
    "mkdir",
    "rmtree",
    "kill",
    "write_text",
    "write_bytes",
    "writelines",
    "truncate",
    "chmod",
    "touch",
    "symlink_to",
    "urlopen",
    "system",
    "popen",
}
_WRITE_MODE_CHARS = set("wax+")


class MonitorIntegrityError(Exception):
    """Raised when this module has acquired a mutation capability."""


def assert_read_only_source(source_path: Optional[Path] = None) -> None:
    """Fail loudly if this file has gained any mutation primitive.

    Uses the AST rather than substring matching, so it inspects what the code
    actually does and cannot be tripped by its own prose or by the names listed
    above appearing in a comment.
    """
    import ast

    path = Path(source_path) if source_path else Path(__file__)
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    found: List[str] = []

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.split(".")[0] in _FORBIDDEN_IMPORTS:
                    found.append(f"import {alias.name}")
        elif isinstance(node, ast.ImportFrom):
            root = (node.module or "").split(".")[0]
            if root in _FORBIDDEN_IMPORTS:
                found.append(f"from {node.module} import ...")
        elif isinstance(node, ast.Call):
            func = node.func
            name = None
            if isinstance(func, ast.Attribute):
                name = func.attr
            elif isinstance(func, ast.Name):
                name = func.id
            if name in _FORBIDDEN_CALLS:
                found.append(f"call to {name}()")
            # open() is permitted only in an explicit read mode.
            if name == "open":
                mode = ""
                if len(node.args) > 1 and isinstance(node.args[1], ast.Constant):
                    mode = str(node.args[1].value)
                for kw in node.keywords:
                    if kw.arg == "mode" and isinstance(kw.value, ast.Constant):
                        mode = str(kw.value.value)
                if set(mode) & _WRITE_MODE_CHARS:
                    found.append(f"open(mode={mode!r})")

    if found:
        raise MonitorIntegrityError(
            "benchmark_health_monitor must remain read-only; found mutation "
            f"primitives: {sorted(set(found))}"
        )


@dataclass(frozen=True)
class AttemptEvidence:
    task: str
    attempt: str
    reward: float
    has_trajectory: bool
    terminal: bool


def _reward_of(result: Dict) -> float:
    verifier = result.get("verifier_result") or {}
    rewards = verifier.get("rewards") or {}
    reward = rewards.get("reward") if isinstance(rewards, dict) else None
    if reward is None:
        reward = verifier.get("reward", result.get("reward", 0))
    try:
        return float(reward or 0)
    except (TypeError, ValueError):
        return 0.0


def read_attempts(job_dir: Path) -> Tuple[List[AttemptEvidence], List[str]]:
    """Read every terminal attempt from disk. Returns (attempts, unreadable)."""
    job_dir = Path(job_dir)
    attempts: List[AttemptEvidence] = []
    unreadable: List[str] = []
    if not job_dir.is_dir():
        return attempts, unreadable
    for result_file in sorted(job_dir.rglob("result.json")):
        trial_dir = result_file.parent
        if trial_dir == job_dir:
            continue
        match = _TRIAL_DIR_RE.match(trial_dir.name)
        task = match.group("task") if match else trial_dir.name
        attempt = match.group("trial") if match else trial_dir.name
        try:
            data = json.loads(result_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            unreadable.append(str(trial_dir.name))
            continue
        attempts.append(
            AttemptEvidence(
                task=task,
                attempt=attempt,
                reward=_reward_of(data),
                has_trajectory=(trial_dir / "agent" / "trajectory.json").is_file(),
                terminal=True,
            )
        )
    return attempts, unreadable


def dedupe_attempts(
    attempts: Sequence[AttemptEvidence],
) -> Tuple[List[AttemptEvidence], int]:
    """Collapse duplicates on (task, attempt), keeping the FIRST terminal fact.

    First-wins, not last-wins: a terminal attempt is immutable evidence, so a
    later duplicate is a replay artifact and must not be able to rewrite a
    recorded reward. Returns (unique attempts, duplicates ignored).
    """
    seen: Dict[Tuple[str, str], AttemptEvidence] = {}
    duplicates = 0
    for attempt in attempts:
        key = (attempt.task, attempt.attempt)
        if key in seen:
            duplicates += 1
            continue
        seen[key] = attempt
    ordered = [seen[key] for key in sorted(seen)]
    return ordered, duplicates


def read_ndjson_attempts(path: Path) -> List[AttemptEvidence]:
    """Read attempt evidence from an append-only NDJSON event log.

    Malformed lines are skipped rather than raising: a monitor that dies on one
    bad line is worse than one that reports a partial view.
    """
    path = Path(path)
    out: List[AttemptEvidence] = []
    if not path.is_file():
        return out
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if event.get("type") != "TASK_SCORED":
                continue
            out.append(
                AttemptEvidence(
                    task=str(event.get("task", "")),
                    attempt=str(event.get("attempt", "")),
                    reward=float(event.get("reward", 0) or 0),
                    has_trajectory=bool(event.get("hasTrajectory", False)),
                    terminal=True,
                )
            )
    return out


def read_sync_state(state_path: Optional[Path]) -> Dict:
    if not state_path:
        return {"available": False, "reason": "no state path supplied"}
    path = Path(state_path)
    if not path.is_file():
        return {"available": False, "reason": f"no sync state file at {path}"}
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return {"available": False, "reason": f"unreadable sync state: {exc}"}
    return {
        "available": True,
        "lastSuccessAt": state.get("lastSuccessAt"),
        "lastErrorAt": state.get("lastErrorAt"),
        "lastError": state.get("lastError"),
        "consecutiveFailures": state.get("consecutiveFailures", 0),
        "attempts": state.get("attempts", 0),
        "finalSyncDone": state.get("finalSyncDone", False),
    }


def build_report(
    job_dir: Path,
    expected_tasks: Optional[int] = None,
    k: Optional[int] = None,
    sync_state_path: Optional[Path] = None,
    event_log_path: Optional[Path] = None,
) -> Dict:
    """Produce a versioned, deterministic health/evidence report.

    Deterministic on purpose: replaying the same durable inputs after a restart
    produces a byte-identical report, so the report itself can be diffed as
    evidence.
    """
    disk_attempts, unreadable = read_attempts(Path(job_dir))
    if event_log_path:
        disk_attempts = list(disk_attempts) + read_ndjson_attempts(Path(event_log_path))
    attempts, duplicates = dedupe_attempts(disk_attempts)

    per_task: Dict[str, int] = {}
    for attempt in attempts:
        per_task[attempt.task] = per_task.get(attempt.task, 0) + 1

    passing = [a for a in attempts if a.reward > 0]
    missing_trajectory = sorted(
        f"{a.task}__{a.attempt}" for a in passing if not a.has_trajectory
    )
    incomplete = (
        sorted(f"{task}:{count}" for task, count in per_task.items() if count != k)
        if k
        else []
    )

    return {
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "readOnly": True,
        "jobDir": str(job_dir),
        "tasksSeen": len(per_task),
        "expectedTasks": expected_tasks,
        "k": k,
        "terminalAttempts": len(attempts),
        "duplicateAttemptsIgnored": duplicates,
        "unreadableTrials": sorted(unreadable),
        "passingAttempts": len(passing),
        "passingAttemptsMissingTrajectory": missing_trajectory,
        "tasksWithWrongAttemptCount": incomplete,
        "sync": read_sync_state(sync_state_path),
        "disclaimer": (
            "Read-only observation of on-disk artifacts. Not a score and not a "
            "verdict. The official verifier owns benchmark results."
        ),
    }


def main(argv: Optional[Sequence[str]] = None) -> int:  # pragma: no cover - CLI shim
    import argparse

    assert_read_only_source()
    parser = argparse.ArgumentParser(description="Read-only benchmark health report.")
    parser.add_argument("--job-dir", required=True)
    parser.add_argument("--k", type=int, default=None)
    parser.add_argument("--expected-tasks", type=int, default=None)
    parser.add_argument("--sync-state", default=None)
    parser.add_argument("--event-log", default=None)
    args = parser.parse_args(argv)

    report = build_report(
        job_dir=Path(args.job_dir),
        expected_tasks=args.expected_tasks,
        k=args.k,
        sync_state_path=Path(args.sync_state) if args.sync_state else None,
        event_log_path=Path(args.event_log) if args.event_log else None,
    )
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":  # pragma: no cover - CLI shim
    raise SystemExit(main())
