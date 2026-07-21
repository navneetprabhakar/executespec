"""Official Terminal-Bench leaderboard submission profile — refusal logic.

SCOPE AND HONESTY
-----------------
This module decides whether a completed Harbor job is *structurally eligible* to
be offered to the official verifier. It refuses; it does not bless. Passing
every check here means only that the artifacts are internally consistent and
that the run was configured as an official campaign. It is NOT:

  * proof that Harbor behaved correctly,
  * proof that the trajectories satisfy the official ATIF validator,
  * proof that the leaderboard will accept the submission, and
  * not a score. The official verifier owns the verdict, never this file and
    never the ExecuteSpec /benchmark dashboard.

The one guarantee it does provide is negative and load-bearing: an internal
K=1 run cannot be laundered into leaderboard evidence, because K<5 and an
internal profile marker are hard refusals with no override path.

`run-tb.sh` remains the internal K=1 runner and is untouched. The official path
is `run-tb-official.sh`, which delegates every gate to this module.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence

MANIFEST_SCHEMA_VERSION = 1
MANIFEST_FILENAME = "official-submission-manifest.json"

# Minimum attempts for an official Terminal-Bench leaderboard campaign.
MIN_OFFICIAL_K = 5

# Harbor's own default agent timeout. An official run must use it unless the
# leaderboard rules explicitly permit an override AND the operator records the
# rule reference — silent drift is a refusal.
OFFICIAL_HARBOR_AGENT_TIMEOUT_SEC = 1500

# Version keys that must be frozen and recorded before an official run.
REQUIRED_FROZEN_VERSIONS = (
    "harbor_version",
    "dataset_version",
    "agent_version",
    "config_sha256",
    "container_image_digest",
)

# Refusal codes. Stable strings so runbooks and tests can assert on them.
REFUSE_K_TOO_LOW = "REFUSE_K_TOO_LOW"
REFUSE_TIMEOUT_DRIFT = "REFUSE_TIMEOUT_DRIFT"
REFUSE_UPLOAD_DISABLED = "REFUSE_UPLOAD_DISABLED"
REFUSE_MISSING_FROZEN_VERSION = "REFUSE_MISSING_FROZEN_VERSION"
REFUSE_INTERNAL_PROFILE = "REFUSE_INTERNAL_PROFILE"
REFUSE_MISSING_TRAJECTORY = "REFUSE_MISSING_TRAJECTORY"
REFUSE_INCOMPLETE_ATTEMPTS = "REFUSE_INCOMPLETE_ATTEMPTS"
REFUSE_NO_RESULTS = "REFUSE_NO_RESULTS"
REFUSE_CORRUPT_CHECKSUM = "REFUSE_CORRUPT_CHECKSUM"
REFUSE_MANIFEST_IMMUTABLE = "REFUSE_MANIFEST_IMMUTABLE"
REFUSE_MANIFEST_MISSING = "REFUSE_MANIFEST_MISSING"


class OfficialProfileRefusal(Exception):
    """Raised whenever an official submission precondition is not met.

    Carries a stable `code` so callers can map to an exit status and tests can
    assert on the reason rather than on prose.
    """

    def __init__(self, code: str, message: str, details: Optional[Sequence[str]] = None):
        self.code = code
        self.details = list(details or [])
        super().__init__(f"{code}: {message}")


@dataclass(frozen=True)
class OfficialProfile:
    """The declared shape of an official campaign."""

    suite: str
    k: int
    harbor_agent_timeout_sec: int
    upload_enabled: bool
    frozen_versions: Dict[str, str] = field(default_factory=dict)
    expected_task_count: Optional[int] = None
    # Only set these two together, and only when the published leaderboard rules
    # allow a non-default timeout. `timeout_override_rule_reference` must name
    # the rule; a blank reference is treated as silent drift and refused.
    timeout_override_allowed: bool = False
    timeout_override_rule_reference: str = ""
    # Set by the internal runner path. Structurally blocks laundering.
    internal_profile: bool = False


def validate_profile(profile: OfficialProfile) -> None:
    """Refuse any profile that is not a valid official campaign declaration."""
    if profile.internal_profile:
        raise OfficialProfileRefusal(
            REFUSE_INTERNAL_PROFILE,
            "this job was produced by the internal runner and can never be submitted "
            "as leaderboard evidence",
        )

    if profile.k < MIN_OFFICIAL_K:
        raise OfficialProfileRefusal(
            REFUSE_K_TOO_LOW,
            f"official submission requires K>={MIN_OFFICIAL_K}, got K={profile.k}",
        )

    if profile.harbor_agent_timeout_sec != OFFICIAL_HARBOR_AGENT_TIMEOUT_SEC:
        if not profile.timeout_override_allowed:
            raise OfficialProfileRefusal(
                REFUSE_TIMEOUT_DRIFT,
                f"agent timeout {profile.harbor_agent_timeout_sec}s differs from the "
                f"official default {OFFICIAL_HARBOR_AGENT_TIMEOUT_SEC}s and no rule "
                "override was declared",
            )
        if not profile.timeout_override_rule_reference.strip():
            raise OfficialProfileRefusal(
                REFUSE_TIMEOUT_DRIFT,
                "timeout override declared without a rule reference; an unexplained "
                "override is indistinguishable from silent drift",
            )

    if not profile.upload_enabled:
        raise OfficialProfileRefusal(
            REFUSE_UPLOAD_DISABLED,
            "official profile must run with upload enabled (harbor run --upload); "
            "a local-only run is not a submission",
        )

    missing = [
        key
        for key in REQUIRED_FROZEN_VERSIONS
        if not str(profile.frozen_versions.get(key, "")).strip()
    ]
    if missing:
        raise OfficialProfileRefusal(
            REFUSE_MISSING_FROZEN_VERSION,
            "official profile requires every version to be frozen and recorded",
            details=missing,
        )


# ---------------------------------------------------------------------------
# Artifact inspection
# ---------------------------------------------------------------------------

# Harbor lays trials out as <job_dir>/<task-name>__<trial-id>/result.json
_TRIAL_DIR_RE = re.compile(r"^(?P<task>.+?)__(?P<trial>[^/]+)$")


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


@dataclass
class TrialRecord:
    task: str
    trial_dir: Path
    reward: float
    has_trajectory: bool


def collect_trials(job_dir: Path) -> List[TrialRecord]:
    """Read every trial under a Harbor job dir. Pure read; never writes."""
    job_dir = Path(job_dir)
    trials: List[TrialRecord] = []
    for result_file in sorted(job_dir.rglob("result.json")):
        trial_dir = result_file.parent
        if trial_dir == job_dir:
            continue
        match = _TRIAL_DIR_RE.match(trial_dir.name)
        task = match.group("task") if match else trial_dir.name
        try:
            data = json.loads(result_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise OfficialProfileRefusal(
                REFUSE_NO_RESULTS, f"cannot parse {result_file}: {exc}"
            ) from exc
        trials.append(
            TrialRecord(
                task=task,
                trial_dir=trial_dir,
                reward=_reward_of(data),
                has_trajectory=(trial_dir / "agent" / "trajectory.json").is_file(),
            )
        )
    return trials


def validate_attempt_completeness(
    trials: Sequence[TrialRecord], k: int, expected_task_count: Optional[int] = None
) -> Dict[str, int]:
    """Every task must have exactly K terminal attempts.

    A partially-finished campaign is refused rather than submitted as if the
    missing attempts had simply not resolved.
    """
    if not trials:
        raise OfficialProfileRefusal(
            REFUSE_NO_RESULTS, "no result.json files found under the job directory"
        )

    per_task: Dict[str, int] = {}
    for trial in trials:
        per_task[trial.task] = per_task.get(trial.task, 0) + 1

    wrong = sorted(
        f"{task}: {count} attempts (expected {k})"
        for task, count in per_task.items()
        if count != k
    )
    if wrong:
        raise OfficialProfileRefusal(
            REFUSE_INCOMPLETE_ATTEMPTS,
            f"{len(wrong)} task(s) do not have exactly K={k} terminal attempts",
            details=wrong,
        )

    if expected_task_count is not None and len(per_task) != expected_task_count:
        raise OfficialProfileRefusal(
            REFUSE_INCOMPLETE_ATTEMPTS,
            f"job contains {len(per_task)} tasks but the frozen dataset declares "
            f"{expected_task_count}",
        )
    return per_task


def validate_trajectories(trials: Sequence[TrialRecord]) -> int:
    """Every passing trial must carry a trajectory for ATIF validation.

    NOTE: presence is checked here. ATIF *schema* validation requires Harbor's
    own `TrajectoryValidator` and is performed by submit-tb.sh inside the Harbor
    venv — this module cannot and does not substitute for it.
    """
    missing = sorted(
        str(t.trial_dir.name) for t in trials if t.reward > 0 and not t.has_trajectory
    )
    if missing:
        raise OfficialProfileRefusal(
            REFUSE_MISSING_TRAJECTORY,
            f"{len(missing)} passing trial(s) have no agent/trajectory.json",
            details=missing,
        )
    return sum(1 for t in trials if t.reward > 0)


# ---------------------------------------------------------------------------
# Immutable submission manifest
# ---------------------------------------------------------------------------

_MANIFEST_EXCLUDE = {MANIFEST_FILENAME}


def sha256_file(path: Path, chunk: int = 1 << 20) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            block = handle.read(chunk)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def _manifest_files(job_dir: Path) -> Iterable[Path]:
    for path in sorted(job_dir.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        if path.name in _MANIFEST_EXCLUDE:
            continue
        yield path


def build_manifest(job_dir: Path, profile: OfficialProfile) -> Dict:
    """Build the immutable submission manifest for a validated job."""
    job_dir = Path(job_dir)
    entries = []
    for path in _manifest_files(job_dir):
        entries.append(
            {
                "path": str(path.relative_to(job_dir)),
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "suite": profile.suite,
        "k": profile.k,
        "harborAgentTimeoutSec": profile.harbor_agent_timeout_sec,
        "uploadEnabled": profile.upload_enabled,
        "frozenVersions": dict(profile.frozen_versions),
        "timeoutOverrideRuleReference": profile.timeout_override_rule_reference,
        "fileCount": len(entries),
        "files": entries,
    }
    manifest["manifestDigest"] = _manifest_digest(manifest)
    return manifest


def _manifest_digest(manifest: Dict) -> str:
    body = {key: value for key, value in manifest.items() if key != "manifestDigest"}
    encoded = json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def write_manifest(job_dir: Path, manifest: Dict) -> Path:
    """Write the manifest once. Refuses to overwrite an existing manifest."""
    target = Path(job_dir) / MANIFEST_FILENAME
    if target.exists():
        raise OfficialProfileRefusal(
            REFUSE_MANIFEST_IMMUTABLE,
            f"{target} already exists; a submission manifest is immutable. Delete the "
            "job and rerun rather than rewriting evidence.",
        )
    # O_EXCL closes the TOCTOU window between the exists() check and the write.
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, indent=2, sort_keys=True)
            handle.write("\n")
    except FileExistsError as exc:  # pragma: no cover - defensive
        raise OfficialProfileRefusal(
            REFUSE_MANIFEST_IMMUTABLE, f"{target} already exists"
        ) from exc
    return target


def verify_manifest(job_dir: Path, manifest: Optional[Dict] = None) -> int:
    """Re-hash every recorded file and refuse on any mismatch.

    Detects post-hoc edits, truncation, and files added or removed after the
    manifest was sealed.
    """
    job_dir = Path(job_dir)
    if manifest is None:
        target = job_dir / MANIFEST_FILENAME
        if not target.is_file():
            raise OfficialProfileRefusal(
                REFUSE_MANIFEST_MISSING, f"no submission manifest at {target}"
            )
        manifest = json.loads(target.read_text(encoding="utf-8"))

    recorded_digest = manifest.get("manifestDigest")
    if recorded_digest != _manifest_digest(manifest):
        raise OfficialProfileRefusal(
            REFUSE_CORRUPT_CHECKSUM,
            "manifest self-digest mismatch; the manifest itself was modified",
        )

    problems: List[str] = []
    recorded = {entry["path"]: entry for entry in manifest.get("files", [])}
    for rel, entry in sorted(recorded.items()):
        path = job_dir / rel
        if not path.is_file():
            problems.append(f"missing: {rel}")
            continue
        if sha256_file(path) != entry["sha256"]:
            problems.append(f"checksum mismatch: {rel}")

    on_disk = {str(p.relative_to(job_dir)) for p in _manifest_files(job_dir)}
    for rel in sorted(on_disk - set(recorded)):
        problems.append(f"unrecorded file added after sealing: {rel}")

    if problems:
        raise OfficialProfileRefusal(
            REFUSE_CORRUPT_CHECKSUM,
            f"{len(problems)} manifest integrity problem(s)",
            details=problems,
        )
    return len(recorded)


# ---------------------------------------------------------------------------
# Top-level gate
# ---------------------------------------------------------------------------


def assert_submittable(job_dir: Path, profile: OfficialProfile) -> Dict:
    """Run every structural gate. Raises OfficialProfileRefusal on the first failure.

    Returns a summary dict on success. Success here means "not refused", not
    "accepted by the leaderboard".
    """
    validate_profile(profile)
    trials = collect_trials(Path(job_dir))
    per_task = validate_attempt_completeness(trials, profile.k, profile.expected_task_count)
    passing = validate_trajectories(trials)
    return {
        "tasks": len(per_task),
        "trials": len(trials),
        "passingTrials": passing,
        "k": profile.k,
        "suite": profile.suite,
    }


# ---------------------------------------------------------------------------
# CLI — used by run-tb-official.sh. Exit 2 on every refusal.
# ---------------------------------------------------------------------------

EXIT_REFUSED = 2


def profile_from_env(env: Optional[Dict[str, str]] = None) -> OfficialProfile:
    env = dict(os.environ if env is None else env)

    def _int(name: str, default: int) -> int:
        raw = str(env.get(name, "")).strip()
        try:
            return int(raw) if raw else default
        except ValueError:
            return default

    frozen = {key: str(env.get(key.upper(), "")).strip() for key in REQUIRED_FROZEN_VERSIONS}
    expected = str(env.get("EXPECTED_TASK_COUNT", "")).strip()
    return OfficialProfile(
        suite=str(env.get("LEADERBOARD_SLUG", "terminal-bench-2-1")),
        k=_int("K", 0),
        harbor_agent_timeout_sec=_int(
            "HARBOR_AGENT_TIMEOUT_SEC", OFFICIAL_HARBOR_AGENT_TIMEOUT_SEC
        ),
        upload_enabled=str(env.get("HARBOR_UPLOAD", "")).strip().lower()
        in {"1", "true", "on", "yes"},
        frozen_versions=frozen,
        expected_task_count=int(expected) if expected else None,
        timeout_override_allowed=str(env.get("TIMEOUT_OVERRIDE_ALLOWED", ""))
        .strip()
        .lower()
        in {"1", "true", "on", "yes"},
        timeout_override_rule_reference=str(
            env.get("TIMEOUT_OVERRIDE_RULE_REFERENCE", "")
        ).strip(),
        internal_profile=str(env.get("INTERNAL_PROFILE", "")).strip().lower()
        in {"1", "true", "on", "yes"},
    )


def main(argv: Optional[Sequence[str]] = None) -> int:  # pragma: no cover - CLI shim
    import argparse

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "command", choices=["validate-profile", "gate", "seal", "verify"]
    )
    parser.add_argument("--job-dir", default="")
    args = parser.parse_args(argv)

    profile = profile_from_env()
    try:
        if args.command == "validate-profile":
            validate_profile(profile)
            print("official profile declaration accepted (not a score, not a verdict)")
            return 0

        job_dir = Path(args.job_dir)
        if args.command == "gate":
            summary = assert_submittable(job_dir, profile)
            print(json.dumps(summary, sort_keys=True))
            return 0
        if args.command == "seal":
            assert_submittable(job_dir, profile)
            target = write_manifest(job_dir, build_manifest(job_dir, profile))
            print(f"sealed immutable submission manifest: {target}")
            return 0
        if args.command == "verify":
            count = verify_manifest(job_dir)
            print(f"manifest verified: {count} files match recorded checksums")
            return 0
    except OfficialProfileRefusal as refusal:
        print(f"REFUSED — {refusal}", flush=True)
        for detail in refusal.details[:30]:
            print(f"  - {detail}")
        if len(refusal.details) > 30:
            print(f"  ... {len(refusal.details) - 30} more")
        return EXIT_REFUSED
    return 0


if __name__ == "__main__":  # pragma: no cover - CLI shim
    raise SystemExit(main())
