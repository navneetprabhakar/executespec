import importlib.util
import pathlib
import tempfile
import urllib.error


MODULE_PATH = pathlib.Path(__file__).with_name("tb-bench-bridge.py")
SPEC = importlib.util.spec_from_file_location("tb_bench_bridge", MODULE_PATH)
bridge = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bridge)


def test_exception_file_setup_failure_is_fatal():
    outcome, reason = bridge._classify_trial(
        {"agent_result": None, "verifier_result": None},
        0,
        "Docker registry setup failed before agent execution",
    )
    assert outcome == "INFRA_SETUP_FAILED"
    assert "Docker" in reason


def test_timeout_still_wins_over_setup_classification():
    outcome, _ = bridge._classify_trial(
        {"agent_result": None, "verifier_result": None},
        0,
        "AgentTimeoutError while waiting for container",
    )
    assert outcome == "TIMEOUT"


def test_scan_active_finds_started_trials_without_result_file():
    with tempfile.TemporaryDirectory() as job_dir:
        pathlib.Path(job_dir, "kv-store-grpc__abc123").mkdir()
        pathlib.Path(job_dir, "pypi-server__def456").mkdir()
        pathlib.Path(job_dir, "job-config").mkdir()
        finished = pathlib.Path(job_dir, "finished__ghi789")
        finished.mkdir()
        finished.joinpath("result.json").write_text("{}", encoding="utf-8")

        active = bridge._scan_active(job_dir)

        assert set(active) == {"kv-store-grpc__abc123", "pypi-server__def456"}
        assert active["kv-store-grpc__abc123"]["task"] == "kv-store-grpc"


def test_scan_active_ignores_non_trial_directories_and_completed_trials():
    with tempfile.TemporaryDirectory() as job_dir:
        pathlib.Path(job_dir, "job-metadata").mkdir()
        done = pathlib.Path(job_dir, "task__abc123")
        done.mkdir()
        done.joinpath("result.json").write_text("{}", encoding="utf-8")

        assert bridge._scan_active(job_dir) == {}


def test_authed_request_refreshes_once_after_expired_access_token(monkeypatch):
    calls = []

    def fake_req(base, path, token, method="GET", body=None, timeout=30):
        calls.append(token)
        if token == "expired":
            raise urllib.error.HTTPError(path, 401, "expired", {}, None)
        return {"ok": True}

    monkeypatch.setattr(bridge, "_req", fake_req)
    monkeypatch.setattr(bridge, "_login", lambda base, email, password: "fresh")
    auth = {"token": "expired", "email": "operator@example.test", "password": "secret"}

    assert bridge._authed_req("https://example.test", "/event", auth) == {"ok": True}
    assert calls == ["expired", "fresh"]
    assert auth["token"] == "fresh"


def test_authed_request_does_not_refresh_static_token_without_credentials(monkeypatch):
    def fake_req(base, path, token, method="GET", body=None, timeout=30):
        raise urllib.error.HTTPError(path, 401, "expired", {}, None)

    monkeypatch.setattr(bridge, "_req", fake_req)

    try:
        bridge._authed_req(
            "https://example.test", "/event", {"token": "static", "email": None, "password": None}
        )
    except urllib.error.HTTPError as exc:
        assert exc.code == 401
    else:
        raise AssertionError("static tokens must fail closed after HTTP 401")


def test_harbor_job_finished_requires_terminal_timestamp():
    with tempfile.TemporaryDirectory() as job_dir:
        result = pathlib.Path(job_dir, "result.json")
        result.write_text('{"finished_at": null}', encoding="utf-8")
        assert bridge._harbor_job_finished(job_dir) is False
        result.write_text('{"finished_at": "2026-07-19T02:00:00Z"}', encoding="utf-8")
        assert bridge._harbor_job_finished(job_dir) is True


def test_active_heartbeat_preserves_attempt_identity_and_rate_limit(monkeypatch):
    events = []

    def fake_authed_req(base, path, auth, method="GET", body=None, timeout=30):
        events.append(body)
        return {}

    monkeypatch.setattr(bridge, "_authed_req", fake_authed_req)
    active = {"kv-store-grpc__abc123": {"task": "kv-store-grpc"}}
    started = {"kv-store-grpc__abc123"}
    attempts = {"kv-store-grpc__abc123": 3}
    heartbeats = {"kv-store-grpc__abc123": 100}

    assert bridge._heartbeat_active_attempts(
        "https://example.test",
        {"token": "token"},
        "run-1",
        active,
        started,
        set(),
        attempts,
        heartbeats,
        159,
        60,
    ) == 0
    assert bridge._heartbeat_active_attempts(
        "https://example.test",
        {"token": "token"},
        "run-1",
        active,
        started,
        set(),
        attempts,
        heartbeats,
        160,
        60,
    ) == 1

    assert events == [
        {
            "type": "RUN_PROGRESS",
            "taskName": "kv-store-grpc",
            "attempt": 3,
            "message": "Harbor trial still active",
        }
    ]
    assert heartbeats["kv-store-grpc__abc123"] == 160


def test_failed_active_heartbeat_retries_without_consuming_window(monkeypatch):
    calls = []

    def fake_authed_req(base, path, auth, method="GET", body=None, timeout=30):
        calls.append(body)
        if len(calls) == 1:
            raise OSError("temporary telemetry failure")
        return {}

    monkeypatch.setattr(bridge, "_authed_req", fake_authed_req)
    active = {"pypi-server__def456": {"task": "pypi-server"}}
    heartbeats = {"pypi-server__def456": 100}
    args = (
        "https://example.test",
        {"token": "token"},
        "run-1",
        active,
        {"pypi-server__def456"},
        set(),
        {"pypi-server__def456": 2},
        heartbeats,
    )

    assert bridge._heartbeat_active_attempts(*args, 160, 60) == 0
    assert heartbeats["pypi-server__def456"] == 100
    assert bridge._heartbeat_active_attempts(*args, 161, 60) == 1
    assert heartbeats["pypi-server__def456"] == 161


def test_active_heartbeat_preserves_attempt_and_is_rate_limited(monkeypatch):
    calls = []

    def fake_authed_req(base, path, auth, method="GET", body=None, timeout=30):
        calls.append((path, body))
        return {"ok": True}

    monkeypatch.setattr(bridge, "_authed_req", fake_authed_req)
    last = {"task__abc123": 100.0}
    args = (
        "https://example.test",
        {"token": "token"},
        "run-1",
        {"task__abc123": {"task": "task"}},
        {"task__abc123"},
        set(),
        {"task__abc123": 3},
        last,
    )

    assert bridge._heartbeat_active_attempts(*args, 159.0, 60) == 0
    assert calls == []
    assert bridge._heartbeat_active_attempts(*args, 160.0, 60) == 1
    assert calls[0][1] == {
        "type": "RUN_PROGRESS",
        "taskName": "task",
        "attempt": 3,
        "message": "Harbor trial still active",
    }
    assert last["task__abc123"] == 160.0


def test_active_heartbeat_ignores_unannounced_posted_and_failed_events(monkeypatch):
    calls = []

    def failing_authed_req(base, path, auth, method="GET", body=None, timeout=30):
        calls.append(body)
        raise OSError("temporary telemetry failure")

    monkeypatch.setattr(bridge, "_authed_req", failing_authed_req)
    active = {
        "unannounced__a": {"task": "unannounced"},
        "posted__b": {"task": "posted"},
        "live__c": {"task": "live"},
    }
    last = {"live__c": 0.0}

    sent = bridge._heartbeat_active_attempts(
        "https://example.test",
        {"token": "token"},
        "run-1",
        active,
        {"posted__b", "live__c"},
        {"posted__b"},
        {"posted__b": 1, "live__c": 2},
        last,
        60.0,
        60,
    )

    assert sent == 0
    assert [call["taskName"] for call in calls] == ["live"]
    assert last["live__c"] == 0.0
