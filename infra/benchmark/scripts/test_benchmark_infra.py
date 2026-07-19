"""Deterministic tests for the Phase-3 benchmark infrastructure modules.

SCOPE OF PROOF: these tests exercise script logic against fakes and temporary
directories. They prove refusal behaviour, state transitions and determinism.
They prove NOTHING about Harbor, real GCS, real credentials, the official ATIF
validator, or leaderboard acceptance. No test here invokes gsutil, harbor, an
LLM provider, or the network.
"""

import importlib.util
import json
import sys
import threading
from pathlib import Path

import pytest


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[name] = module  # dataclasses need the module registered
    spec.loader.exec_module(module)
    return module


official = _load("official_profile", "official_profile.py")
supervised = _load("supervised_sync", "supervised_sync.py")
monitor = _load("benchmark_health_monitor", "benchmark_health_monitor.py")
concurrency = _load("concurrency_profile", "concurrency_profile.py")


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def make_profile(**overrides):
    base = dict(
        suite="terminal-bench-2-1",
        k=5,
        harbor_agent_timeout_sec=official.OFFICIAL_HARBOR_AGENT_TIMEOUT_SEC,
        upload_enabled=True,
        frozen_versions={key: "frozen" for key in official.REQUIRED_FROZEN_VERSIONS},
    )
    base.update(overrides)
    return official.OfficialProfile(**base)


def write_trial(job_dir: Path, task: str, trial: str, reward: float, trajectory=True):
    trial_dir = job_dir / f"{task}__{trial}"
    trial_dir.mkdir(parents=True, exist_ok=True)
    (trial_dir / "result.json").write_text(
        json.dumps({"verifier_result": {"rewards": {"reward": reward}}}), encoding="utf-8"
    )
    if trajectory:
        agent_dir = trial_dir / "agent"
        agent_dir.mkdir(exist_ok=True)
        (agent_dir / "trajectory.json").write_text('{"steps": []}', encoding="utf-8")
    return trial_dir


def make_job(tmp_path: Path, tasks=2, k=5, reward=1.0, trajectory=True) -> Path:
    job_dir = tmp_path / "job"
    job_dir.mkdir()
    for t in range(tasks):
        for attempt in range(k):
            write_trial(job_dir, f"task-{t}", f"trial{attempt}", reward, trajectory)
    return job_dir


# ---------------------------------------------------------------------------
# 1. official profile — declaration refusals
# ---------------------------------------------------------------------------


def test_valid_official_profile_is_accepted():
    official.validate_profile(make_profile())


@pytest.mark.parametrize("k", [0, 1, 2, 3, 4])
def test_wrong_k_is_refused(k):
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(make_profile(k=k))
    assert exc.value.code == official.REFUSE_K_TOO_LOW


def test_internal_k1_run_can_never_be_submitted():
    """The load-bearing negative guarantee: no override path exists."""
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(make_profile(k=1, internal_profile=True))
    assert exc.value.code == official.REFUSE_INTERNAL_PROFILE

    # Even a fully-valid official declaration is refused if it came from the
    # internal runner.
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(make_profile(internal_profile=True))
    assert exc.value.code == official.REFUSE_INTERNAL_PROFILE


def test_timeout_drift_is_refused():
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(make_profile(harbor_agent_timeout_sec=900))
    assert exc.value.code == official.REFUSE_TIMEOUT_DRIFT


def test_timeout_override_needs_an_explicit_rule_reference():
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(
            make_profile(harbor_agent_timeout_sec=900, timeout_override_allowed=True)
        )
    assert exc.value.code == official.REFUSE_TIMEOUT_DRIFT

    official.validate_profile(
        make_profile(
            harbor_agent_timeout_sec=900,
            timeout_override_allowed=True,
            timeout_override_rule_reference="TB2.1 rules §4.2 long-horizon exemption",
        )
    )


def test_upload_disabled_is_refused():
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(make_profile(upload_enabled=False))
    assert exc.value.code == official.REFUSE_UPLOAD_DISABLED


@pytest.mark.parametrize("missing_key", official.REQUIRED_FROZEN_VERSIONS)
def test_every_frozen_version_is_required(missing_key):
    versions = {key: "frozen" for key in official.REQUIRED_FROZEN_VERSIONS}
    versions[missing_key] = "  "
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(make_profile(frozen_versions=versions))
    assert exc.value.code == official.REFUSE_MISSING_FROZEN_VERSION
    assert missing_key in exc.value.details


def test_profile_from_env_refuses_a_bare_environment():
    profile = official.profile_from_env({})
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.validate_profile(profile)
    # An unconfigured environment must not accidentally look official.
    assert exc.value.code == official.REFUSE_K_TOO_LOW


# ---------------------------------------------------------------------------
# 2. official profile — artifact refusals
# ---------------------------------------------------------------------------


def test_complete_job_passes_every_structural_gate(tmp_path):
    job_dir = make_job(tmp_path, tasks=3, k=5)
    summary = official.assert_submittable(job_dir, make_profile(expected_task_count=3))
    assert summary == {
        "tasks": 3,
        "trials": 15,
        "passingTrials": 15,
        "k": 5,
        "suite": "terminal-bench-2-1",
    }


def test_incomplete_attempts_are_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=2, k=5)
    # Simulate a campaign killed mid-flight: one task only got 3 of 5 attempts.
    for attempt in (3, 4):
        target = job_dir / f"task-1__trial{attempt}" / "result.json"
        target.unlink()
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.assert_submittable(job_dir, make_profile())
    assert exc.value.code == official.REFUSE_INCOMPLETE_ATTEMPTS
    assert any("task-1" in detail for detail in exc.value.details)


def test_task_count_mismatch_against_frozen_dataset_is_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=2, k=5)
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.assert_submittable(job_dir, make_profile(expected_task_count=89))
    assert exc.value.code == official.REFUSE_INCOMPLETE_ATTEMPTS


def test_missing_trajectory_on_a_passing_trial_is_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=1, k=5)
    (job_dir / "task-0__trial2" / "agent" / "trajectory.json").unlink()
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.assert_submittable(job_dir, make_profile())
    assert exc.value.code == official.REFUSE_MISSING_TRAJECTORY
    assert exc.value.details == ["task-0__trial2"]


def test_failing_trial_without_trajectory_is_not_refused(tmp_path):
    """Only passing trials need trajectories for leaderboard validation."""
    job_dir = tmp_path / "job"
    job_dir.mkdir()
    for attempt in range(5):
        write_trial(job_dir, "task-0", f"trial{attempt}", 0.0, trajectory=False)
    official.assert_submittable(job_dir, make_profile())


def test_empty_job_dir_is_refused(tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.assert_submittable(empty, make_profile())
    assert exc.value.code == official.REFUSE_NO_RESULTS


# ---------------------------------------------------------------------------
# 3. immutable manifest + checksums
# ---------------------------------------------------------------------------


def test_manifest_seals_and_verifies(tmp_path):
    job_dir = make_job(tmp_path, tasks=2, k=5)
    profile = make_profile()
    manifest = official.build_manifest(job_dir, profile)
    path = official.write_manifest(job_dir, manifest)
    assert path.is_file()
    assert official.verify_manifest(job_dir) == manifest["fileCount"]


def test_manifest_is_immutable(tmp_path):
    job_dir = make_job(tmp_path, tasks=1, k=5)
    profile = make_profile()
    official.write_manifest(job_dir, official.build_manifest(job_dir, profile))
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.write_manifest(job_dir, official.build_manifest(job_dir, profile))
    assert exc.value.code == official.REFUSE_MANIFEST_IMMUTABLE


def test_corrupt_checksum_is_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=1, k=5)
    official.write_manifest(job_dir, official.build_manifest(job_dir, make_profile()))
    # Post-hoc edit of a sealed result.
    target = job_dir / "task-0__trial0" / "result.json"
    target.chmod(0o644)
    target.write_text(json.dumps({"verifier_result": {"rewards": {"reward": 999}}}))
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.verify_manifest(job_dir)
    assert exc.value.code == official.REFUSE_CORRUPT_CHECKSUM
    assert any("checksum mismatch" in d for d in exc.value.details)


def test_deleted_file_after_sealing_is_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=1, k=5)
    official.write_manifest(job_dir, official.build_manifest(job_dir, make_profile()))
    (job_dir / "task-0__trial0" / "result.json").unlink()
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.verify_manifest(job_dir)
    assert exc.value.code == official.REFUSE_CORRUPT_CHECKSUM
    assert any("missing:" in d for d in exc.value.details)


def test_file_added_after_sealing_is_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=1, k=5)
    official.write_manifest(job_dir, official.build_manifest(job_dir, make_profile()))
    (job_dir / "task-0__trial0" / "smuggled.json").write_text("{}", encoding="utf-8")
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.verify_manifest(job_dir)
    assert exc.value.code == official.REFUSE_CORRUPT_CHECKSUM
    assert any("unrecorded file added" in d for d in exc.value.details)


def test_tampered_manifest_self_digest_is_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=1, k=5)
    manifest = official.build_manifest(job_dir, make_profile())
    manifest["k"] = 1  # rewrite the recorded campaign shape
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.verify_manifest(job_dir, manifest)
    assert exc.value.code == official.REFUSE_CORRUPT_CHECKSUM


def test_verify_without_a_manifest_is_refused(tmp_path):
    job_dir = make_job(tmp_path, tasks=1, k=5)
    with pytest.raises(official.OfficialProfileRefusal) as exc:
        official.verify_manifest(job_dir)
    assert exc.value.code == official.REFUSE_MANIFEST_MISSING


# ---------------------------------------------------------------------------
# 4. supervised GCS sync
# ---------------------------------------------------------------------------


class FakeRunner:
    """Scripted gsutil replacement. Never touches the network."""

    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.calls = []

    def __call__(self, argv, timeout):
        self.calls.append(list(argv))
        ok = self.outcomes.pop(0) if self.outcomes else True
        return supervised.SyncResult(
            ok=ok,
            returncode=0 if ok else 1,
            started_at=0.0,
            duration_sec=0.0,
            stderr_tail="" if ok else "transient 503 from storage backend",
        )


def make_sync(tmp_path, outcomes, **kwargs):
    runner = FakeRunner(outcomes)
    sync = supervised.SupervisedSync(
        source=tmp_path / "src",
        dest="gs://fake-bucket/prefix",
        state_path=tmp_path / "state.json",
        interval_sec=1.0,
        max_backoff_sec=8.0,
        runner=runner,
        clock=lambda: 1000.0,
        waiter=lambda _event, _delay: False,  # no real sleeping in tests
        **kwargs,
    )
    return sync, runner


def test_sync_command_never_contains_a_delete_flag(tmp_path):
    sync, _ = make_sync(tmp_path, [True])
    assert "-d" not in sync._argv
    assert "--delete" not in sync._argv
    assert sync._argv[:4] == ["gsutil", "-m", "rsync", "-r"]


@pytest.mark.parametrize("flag", ["-d", "--delete", "-D", "--delete-from-source"])
def test_delete_flags_are_refused(flag):
    with pytest.raises(supervised.UnsafeSyncCommand):
        supervised.assert_no_delete(["gsutil", "rsync", flag, "src", "gs://b"])


def test_successful_sync_records_last_success(tmp_path):
    sync, runner = make_sync(tmp_path, [True])
    result = sync.sync_once()
    assert result.ok
    assert sync.state["lastSuccessAt"] == 1000.0
    assert sync.state["lastError"] is None
    assert sync.state["consecutiveFailures"] == 0
    assert len(runner.calls) == 1


def test_failed_sync_records_last_error_without_raising(tmp_path):
    sync, _ = make_sync(tmp_path, [False])
    result = sync.sync_once()
    assert not result.ok
    assert sync.state["lastErrorAt"] == 1000.0
    assert sync.state["lastError"]["returncode"] == 1
    assert sync.state["consecutiveFailures"] == 1
    assert sync.state["lastSuccessAt"] is None


def test_gcs_retry_recovers_from_transient_failure(tmp_path):
    """Two transient failures then success; state converges to healthy."""
    sync, runner = make_sync(tmp_path, [False, False, True])
    sync.sync_once()
    assert sync.backoff_for(sync.state["consecutiveFailures"]) == 1.0
    sync.sync_once()
    assert sync.state["consecutiveFailures"] == 2
    assert sync.backoff_for(2) == 2.0
    sync.sync_once()
    assert sync.state["consecutiveFailures"] == 0
    assert sync.state["lastSuccessAt"] == 1000.0
    assert sync.state["failures"] == 2
    assert sync.state["successes"] == 1
    assert len(runner.calls) == 3


def test_backoff_is_exponential_and_capped(tmp_path):
    sync, _ = make_sync(tmp_path, [])
    assert sync.backoff_for(0) == 1.0
    assert sync.backoff_for(1) == 1.0
    assert sync.backoff_for(2) == 2.0
    assert sync.backoff_for(3) == 4.0
    assert sync.backoff_for(4) == 8.0
    assert sync.backoff_for(99) == 8.0  # capped, never unbounded


def test_final_sync_runs_on_termination(tmp_path):
    sync, runner = make_sync(tmp_path, [True, True, True])
    stop = threading.Event()
    state = sync.run_forever(stop, max_iterations=2)
    # 2 loop iterations + 1 final sync
    assert len(runner.calls) == 3
    assert state["finalSyncDone"] is True


def test_stop_during_backoff_still_runs_the_final_sync(tmp_path):
    """A SIGTERM mid-backoff must not skip the last artifact flush."""
    runner = FakeRunner([False, True])
    sync = supervised.SupervisedSync(
        source=tmp_path / "src",
        dest="gs://fake-bucket/prefix",
        state_path=tmp_path / "state.json",
        interval_sec=1.0,
        runner=runner,
        clock=lambda: 1000.0,
        waiter=lambda _event, _delay: True,  # stop signalled during the backoff
    )
    state = sync.run_forever(threading.Event(), max_iterations=10)
    assert len(runner.calls) == 2  # one failed loop pass, then the final sync
    assert state["finalSyncDone"] is True
    assert state["lastSuccessAt"] == 1000.0


def test_sync_is_idempotent_across_a_restart(tmp_path):
    """State survives process death and the counters keep accumulating."""
    sync1, _ = make_sync(tmp_path, [True, False])
    sync1.sync_once()
    sync1.sync_once()
    assert sync1.state["attempts"] == 2

    # Fresh object, same state file — simulates a restarted supervisor.
    sync2, _ = make_sync(tmp_path, [True])
    assert sync2.state["attempts"] == 2
    assert sync2.state["consecutiveFailures"] == 1
    sync2.sync_once()
    assert sync2.state["attempts"] == 3
    assert sync2.state["consecutiveFailures"] == 0


def test_corrupt_state_file_does_not_block_sync(tmp_path):
    (tmp_path / "state.json").write_text("{not json", encoding="utf-8")
    sync, _ = make_sync(tmp_path, [True])
    assert sync.state["attempts"] == 0
    assert sync.sync_once().ok


# ---------------------------------------------------------------------------
# 5. read-only health monitor
# ---------------------------------------------------------------------------


def test_monitor_source_is_structurally_read_only():
    """Fails if the monitor ever gains a way to alter a campaign."""
    monitor.assert_read_only_source()


def test_monitor_guard_detects_an_introduced_mutation(tmp_path):
    """The guard must actually catch a write path, not just always pass."""
    bad = tmp_path / "bad_monitor.py"
    bad.write_text("import subprocess\ndef go():\n    subprocess.run(['rm','-rf','/'])\n")
    with pytest.raises(monitor.MonitorIntegrityError):
        monitor.assert_read_only_source(bad)

    worse = tmp_path / "worse_monitor.py"
    worse.write_text("def go(p):\n    open(p, 'w').write('x')\n")
    with pytest.raises(monitor.MonitorIntegrityError):
        monitor.assert_read_only_source(worse)


def test_monitor_reports_healthy_campaign(tmp_path):
    job_dir = make_job(tmp_path, tasks=3, k=5)
    report = monitor.build_report(job_dir, expected_tasks=3, k=5)
    assert report["schemaVersion"] == monitor.REPORT_SCHEMA_VERSION
    assert report["readOnly"] is True
    assert report["tasksSeen"] == 3
    assert report["terminalAttempts"] == 15
    assert report["passingAttempts"] == 15
    assert report["tasksWithWrongAttemptCount"] == []
    assert report["passingAttemptsMissingTrajectory"] == []


def test_monitor_flags_incomplete_and_missing_trajectory(tmp_path):
    job_dir = make_job(tmp_path, tasks=2, k=5)
    (job_dir / "task-1__trial4" / "result.json").unlink()
    (job_dir / "task-0__trial1" / "agent" / "trajectory.json").unlink()
    report = monitor.build_report(job_dir, k=5)
    assert report["tasksWithWrongAttemptCount"] == ["task-1:4"]
    assert report["passingAttemptsMissingTrajectory"] == ["task-0__trial1"]


def test_monitor_ignores_duplicated_events(tmp_path):
    """A replayed TASK_SCORED must not double-count or rewrite a terminal fact."""
    job_dir = make_job(tmp_path, tasks=1, k=1, reward=1.0)
    events = tmp_path / "events.ndjson"
    events.write_text(
        "\n".join(
            [
                json.dumps(
                    {
                        "type": "TASK_SCORED",
                        "task": "task-0",
                        "attempt": "trial0",
                        "reward": 0,
                        "hasTrajectory": True,
                    }
                ),
                json.dumps(
                    {
                        "type": "TASK_SCORED",
                        "task": "task-0",
                        "attempt": "trial0",
                        "reward": 0,
                        "hasTrajectory": True,
                    }
                ),
            ]
        ),
        encoding="utf-8",
    )
    report = monitor.build_report(job_dir, k=1, event_log_path=events)
    assert report["terminalAttempts"] == 1
    assert report["duplicateAttemptsIgnored"] == 2
    # First-wins: the on-disk reward of 1.0 is not overwritten by the replay's 0.
    assert report["passingAttempts"] == 1


def test_monitor_report_is_identical_after_restart_replay(tmp_path):
    """Deterministic reconstruction from durable artifacts, twice."""
    job_dir = make_job(tmp_path, tasks=3, k=5)
    state = tmp_path / "state.json"
    sync, _ = make_sync(tmp_path, [True])
    sync.sync_once()

    first = monitor.build_report(job_dir, expected_tasks=3, k=5, sync_state_path=state)
    second = monitor.build_report(job_dir, expected_tasks=3, k=5, sync_state_path=state)
    assert first == second
    assert first["sync"]["available"] is True
    assert first["sync"]["lastSuccessAt"] == 1000.0


def test_monitor_handles_absent_job_and_state(tmp_path):
    report = monitor.build_report(tmp_path / "nope", k=5, sync_state_path=tmp_path / "nostate")
    assert report["terminalAttempts"] == 0
    assert report["sync"]["available"] is False


def test_monitor_records_unreadable_trials(tmp_path):
    job_dir = tmp_path / "job"
    trial = job_dir / "task-0__trial0"
    trial.mkdir(parents=True)
    (trial / "result.json").write_text("{corrupt", encoding="utf-8")
    report = monitor.build_report(job_dir, k=1)
    assert report["unreadableTrials"] == ["task-0__trial0"]
    assert report["terminalAttempts"] == 0


# ---------------------------------------------------------------------------
# 6. concurrency honesty
# ---------------------------------------------------------------------------


def test_default_concurrency_is_unchanged():
    assert concurrency.DEFAULT_CONCURRENCY == 4


def test_terminal_bench_concurrency_is_observed_not_proven():
    record = concurrency.resolve("terminal-bench-2-1")
    assert record.concurrency == 4
    assert record.evidence == concurrency.EVIDENCE_OBSERVED
    assert record.proven is False
    assert "UNPROVEN" in record.provenance_line()


def test_unmeasured_suites_are_labelled_unproven():
    for suite in ("swebench-lite", "swebench-pro", "programbench"):
        record = concurrency.resolve(suite)
        assert record.evidence == concurrency.EVIDENCE_UNMEASURED
        assert record.proven is False


def test_unknown_suite_inherits_default_without_implying_proof():
    record = concurrency.resolve("brand-new-suite")
    assert record.concurrency == concurrency.DEFAULT_CONCURRENCY
    assert record.evidence == concurrency.EVIDENCE_UNMEASURED
    assert record.proven is False


def test_operator_override_is_reported_as_unproven():
    assert "OPERATOR_OVERRIDE" in concurrency.describe("terminal-bench-2-1", 16)
    assert "UNPROVEN" in concurrency.describe("terminal-bench-2-1", 16)


def test_no_suite_currently_claims_a_measured_ceiling():
    """Guards against someone promoting a value without recording evidence."""
    for name, record in concurrency.RECORDS.items():
        if record.proven:
            assert record.machine_shape != "unknown", name
            assert record.recorded_on, name
