import argparse
import importlib.util
import io
import json
from pathlib import Path
import threading
import urllib.error

import pytest


SCRIPT = Path(__file__).with_name("k-eval.py")
SPEC = importlib.util.spec_from_file_location("k_eval", SCRIPT)
k_eval = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(k_eval)


def test_publish_requires_every_manifest_instance():
    with pytest.raises(SystemExit, match="incomplete result coverage"):
        k_eval.validate_publish_coverage({"a": 1}, ["a", "b"], None)


def test_publish_rejects_result_not_in_manifest():
    with pytest.raises(SystemExit, match="incomplete result coverage"):
        k_eval.validate_publish_coverage({"a": 1, "unexpected": 0}, ["a"], None)


def test_publish_accepts_exact_manifest_coverage():
    k_eval.validate_publish_coverage({"a": 1, "b": 0}, ["a", "b"], None)


def test_publish_manifest_must_match_explicit_expected_total():
    with pytest.raises(SystemExit, match="manifest terminal count"):
        k_eval.validate_publish_coverage({"a": 1, "b": 0}, ["a", "b"], 10)


def test_publish_without_manifest_requires_expected_total():
    with pytest.raises(SystemExit, match="requires --manifest or --expected-total"):
        k_eval.validate_publish_coverage({"a": 1}, [], None)
    k_eval.validate_publish_coverage({"a": 1, "b": 0}, [], 2)


def generate_args(tmp_path, dataset, expected_total):
    return argparse.Namespace(
        dataset=str(dataset),
        limit=0,
        expected_total=expected_total,
        out=str(tmp_path / "out"),
        provider="openrouter",
        model="glm-5.2",
        k=1,
        concurrency=2,
        per_task_cap_usd=k_eval.Decimal("2"),
        campaign_cap_usd=k_eval.Decimal("20"),
        opening_balance_usd=k_eval.Decimal("25"),
        task_timeout_seconds=3600,
        shutdown_reserve_seconds=120,
        max_new_tasks=None,
    )


def test_benchmark_deadline_fields_share_absolute_epoch_and_reserve():
    assert k_eval.benchmark_deadline_fields(3600, 120, now_seconds=1000.25) == {
        "absoluteDeadlineEpochMs": 4_600_250,
        "shutdownReserveMs": 120_000,
    }


def test_generate_rejects_timeout_without_cleanup_reserve_before_login(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "a"}]))
    args = generate_args(tmp_path, dataset, 1)
    args.task_timeout_seconds = 120
    args.shutdown_reserve_seconds = 120
    monkeypatch.setattr(
        k_eval, "login", lambda: pytest.fail("login must not occur before timeout validation"))
    with pytest.raises(SystemExit, match="must exceed"):
        k_eval.cmd_generate(args)


def test_generate_rejects_non_positive_max_new_tasks_before_login(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "a"}]))
    args = generate_args(tmp_path, dataset, 1)
    args.max_new_tasks = 0
    monkeypatch.setattr(
        k_eval, "login", lambda: pytest.fail("login must not occur before wave-cap validation"))
    with pytest.raises(SystemExit, match="must be positive"):
        k_eval.cmd_generate(args)


def test_generate_rejects_wrong_cardinality_before_login(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "a"}]))
    monkeypatch.setattr(
        k_eval, "login", lambda: pytest.fail("login must not occur before cardinality validation"))
    with pytest.raises(SystemExit, match="cardinality 1"):
        k_eval.cmd_generate(generate_args(tmp_path, dataset, 10))


def test_generate_rejects_duplicate_dataset_before_login(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "a"}, {"instance_id": "a"}]))
    monkeypatch.setattr(
        k_eval, "login", lambda: pytest.fail("login must not occur before duplicate validation"))
    with pytest.raises(SystemExit, match="duplicate instance_id"):
        k_eval.cmd_generate(generate_args(tmp_path, dataset, 2))


def test_generate_rejects_k_pass_campaign_oversubscription_before_login(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": f"task-{index}"} for index in range(10)]))
    args = generate_args(tmp_path, dataset, 10)
    args.k = 2
    monkeypatch.setattr(
        k_eval, "login", lambda: pytest.fail("login must not occur before budget validation"))
    with pytest.raises(SystemExit, match="campaign budget refused before tracking"):
        k_eval.cmd_generate(args)


def write_jsonl(path, rows):
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def terminal_row(instance_id="a", cost="0.25"):
    return {
        "instance_id": instance_id,
        "status": 200,
        "outcome": "COMPLETED",
        "runId": f"run-{instance_id}",
        "costUsd": cost,
        "submissionPatch": f"{instance_id}.patch",
        "predsJson": f"{instance_id}.preds.json",
        "proPatchJson": f"{instance_id}.pro.json",
    }


def test_resume_counts_only_valid_success_rows(tmp_path):
    manifest = tmp_path / "manifest.jsonl"
    write_jsonl(
        manifest,
        [
            {"instance_id": "failed", "status": -1, "error": "timeout"},
            {"instance_id": "unauthorized", "status": 401, "error": "expired"},
            terminal_row("complete"),
        ],
    )

    assert set(k_eval.load_generation_manifest(manifest)) == {"complete"}


def test_resume_rejects_duplicate_terminal_rows(tmp_path):
    manifest = tmp_path / "manifest.jsonl"
    write_jsonl(manifest, [terminal_row(), terminal_row()])
    with pytest.raises(SystemExit, match="duplicate terminal"):
        k_eval.load_generation_manifest(manifest)


def test_resume_rejects_malformed_terminal_row(tmp_path):
    manifest = tmp_path / "manifest.jsonl"
    row = terminal_row()
    del row["runId"]
    write_jsonl(manifest, [row])
    with pytest.raises(SystemExit, match="malformed terminal"):
        k_eval.load_generation_manifest(manifest)


def test_authenticated_post_refreshes_401_once(monkeypatch):
    calls = []

    def fake_post(path, body, token, timeout):
        calls.append(token)
        if token == "expired":
            raise urllib.error.HTTPError(path, 401, "expired", {}, None)
        return 200, {"ok": True}

    monkeypatch.setattr(k_eval, "post", fake_post)
    monkeypatch.setattr(k_eval, "login", lambda: "fresh")
    token_box = {"t": "expired"}

    assert k_eval.post_authenticated("/x", {}, token_box, threading.Lock()) == (200, {"ok": True})
    assert calls == ["expired", "fresh"]
    assert token_box["t"] == "fresh"


def test_authenticated_post_fails_after_refreshed_401(monkeypatch):
    monkeypatch.setattr(
        k_eval,
        "post",
        lambda path, body, token, timeout: (_ for _ in ()).throw(
            urllib.error.HTTPError(path, 401, "expired", {}, None)),
    )
    monkeypatch.setattr(k_eval, "login", lambda: "still-expired")
    with pytest.raises(urllib.error.HTTPError):
        k_eval.post_authenticated("/x", {}, {"t": "expired"}, threading.Lock())


def test_authenticated_get_refreshes_401_once(monkeypatch):
    calls = []

    def fake_get(path, token, timeout):
        calls.append(token)
        if token == "expired":
            raise urllib.error.HTTPError(path, 401, "expired", {}, None)
        return 200, []

    monkeypatch.setattr(k_eval, "get", fake_get)
    monkeypatch.setattr(k_eval, "login", lambda: "fresh")
    token_box = {"t": "expired"}
    assert k_eval.get_authenticated(
        "/api/runs/run-1/event-log", token_box, threading.Lock()) == (200, [])
    assert calls == ["expired", "fresh"]


def test_tracking_start_requires_server_id(monkeypatch):
    monkeypatch.setattr(k_eval, "post_authenticated", lambda *args, **kwargs: (200, {}))
    with pytest.raises(RuntimeError, match="no benchmark id"):
        k_eval.tracking_start(
            {"t": "token"}, threading.Lock(), "openrouter", "glm-5.2", 1, 10, "lite10")


def test_tracking_start_publishes_explicit_submission_contract(monkeypatch):
    captured = {}

    def fake_post(path, body, *args, **kwargs):
        captured.update(body)
        return 200, {"id": "benchmark-id"}

    monkeypatch.setattr(k_eval, "post_authenticated", fake_post)
    assert k_eval.tracking_start(
        {"t": "token"}, threading.Lock(), "deepseek", "deepseek-v4-pro-native",
        1, 731, "pro731", True) == "benchmark-id"
    assert captured["submissionRequired"] is True
    assert captured["k"] == 1


def test_tracking_event_failure_is_not_swallowed(monkeypatch):
    monkeypatch.setattr(
        k_eval,
        "post_authenticated",
        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("tracker unavailable")),
    )
    with pytest.raises(RuntimeError, match="tracker unavailable"):
        k_eval.tracking_event(
            {"t": "token"}, threading.Lock(), "benchmark-id", {"type": "RUN_PROGRESS"})


def test_campaign_budget_reserves_and_settles_without_oversubscription(tmp_path):
    budget = k_eval.CampaignBudget(tmp_path / "budget.json", "2", "4", "5", 2)
    assert budget.reserve("a")
    assert budget.reserve("b")
    with pytest.raises(RuntimeError, match="campaign cap exhausted"):
        budget.reserve("c")
    budget.settle("a", "0.25")
    budget.release("b")
    assert budget.reserve("c")


def test_campaign_budget_rejects_cap_larger_than_reserved_campaign(tmp_path):
    with pytest.raises(ValueError, match="cannot reserve"):
        k_eval.CampaignBudget(tmp_path / "budget.json", "2", "19.99", "25", 10)


def test_campaign_budget_rejects_task_cost_above_hard_cap(tmp_path):
    budget = k_eval.CampaignBudget(tmp_path / "budget.json", "2", "20", "25", 10)
    budget.reserve("a")
    with pytest.raises(RuntimeError, match="exceeded hard cap"):
        budget.settle("a", "2.000001")


def test_campaign_only_mode_allows_task_above_legacy_amount_within_campaign(tmp_path):
    budget = k_eval.CampaignBudget(tmp_path / "budget.json", None, "20", "25", 10)
    assert budget.reserve("a")
    budget.settle("a", "2.005480")
    state = json.loads((tmp_path / "budget.json").read_text())
    assert state["reservationMode"] == "CAMPAIGN_REMAINING_SERIAL"
    assert state["tasks"]["a"]["costUsd"] == "2.005480"


def test_campaign_budget_resume_fails_on_unresolved_reservation(tmp_path):
    path = tmp_path / "budget.json"
    budget = k_eval.CampaignBudget(path, "2", "20", "25", 10)
    budget.reserve("a")
    resumed = k_eval.CampaignBudget(path, "2", "20", "25", 10)
    with pytest.raises(RuntimeError, match="unresolved reservation"):
        resumed.reserve("a")


def test_pre_provider_429_releases_but_ambiguous_failure_preserves_reservation(tmp_path):
    released = k_eval.CampaignBudget(tmp_path / "released.json", "1", "1", "1", 1)
    released.reserve("a")
    assert k_eval.apply_failed_request_reservation(
        released, "a", k_eval.PRE_PROVIDER_CONCURRENCY_429) == "RELEASED_PRE_PROVIDER"
    assert released.reserve("a")

    preserved = k_eval.CampaignBudget(tmp_path / "preserved.json", "1", "1", "1", 1)
    preserved.reserve("a")
    assert k_eval.apply_failed_request_reservation(
        preserved, "a", k_eval.AMBIGUOUS_PRESSURE) == "PRESERVED_INDETERMINATE"
    with pytest.raises(RuntimeError, match="unresolved reservation"):
        preserved.reserve("a")


def test_adaptive_waves_step_down_without_duplicate_success_launch():
    calls = {}
    lock = threading.Lock()

    def run_one(instance_id):
        with lock:
            calls[instance_id] = calls.get(instance_id, 0) + 1
            attempt = calls[instance_id]
        if instance_id == "b" and attempt == 1:
            return {
                "instance_id": instance_id,
                "dispatchClassification": k_eval.PRE_PROVIDER_CONCURRENCY_429,
            }
        return {"instance_id": instance_id, "status": 200}

    result = k_eval.dispatch_adaptive_waves(["a", "b", "c", "d", "e"], 4, run_one)

    assert result["aborted"] is False
    assert result["transitions"] == [{"from": 4, "to": 2}]
    assert calls == {"a": 1, "b": 2, "c": 1, "d": 1, "e": 1}


def test_ambiguous_pressure_stops_before_next_wave_and_does_not_retry():
    calls = []

    def run_one(instance_id):
        calls.append(instance_id)
        classification = k_eval.AMBIGUOUS_PRESSURE if instance_id == "a" else None
        return {"instance_id": instance_id, "dispatchClassification": classification}

    result = k_eval.dispatch_adaptive_waves(["a", "b", "c", "d"], 2, run_one)

    assert result["aborted"] is True
    assert result["finalConcurrency"] == 1
    assert set(calls) == {"a", "b"}
    assert calls.count("a") == 1
    assert set(result["remaining"]) == {"c", "d"}


def test_campaign_budget_two_runner_objects_share_latest_reservations(tmp_path):
    path = tmp_path / "budget.json"
    first = k_eval.CampaignBudget(path, "2", "4", "5", 2)
    second = k_eval.CampaignBudget(path, "2", "4", "5", 2)
    first.reserve("pass1:a")
    second.reserve("pass1:b")
    with pytest.raises(RuntimeError, match="campaign cap exhausted"):
        first.reserve("pass1:c")


def test_campaign_budget_settlement_is_immutable(tmp_path):
    budget = k_eval.CampaignBudget(tmp_path / "budget.json", "2", "2", "5", 1)
    budget.reserve("pass1:a")
    budget.settle("pass1:a", "0.25")
    budget.settle("pass1:a", "0.25")
    with pytest.raises(RuntimeError, match="already settled"):
        budget.settle("pass1:a", "0.24")


def test_lite_manifest_accepts_null_pro_patch_artifact(tmp_path):
    manifest = tmp_path / "manifest.jsonl"
    row = terminal_row()
    row["proPatchJson"] = None
    write_jsonl(manifest, [row])
    assert set(k_eval.load_generation_manifest(manifest)) == {"a"}


def test_trajectory_artifact_is_relative_and_hashed(tmp_path):
    events = [{"type": "TOOL_CALL", "payload": "readFile"}]
    replay = {
        "available": True, "truncated": False, "events": events, "lastEventId": "7",
    }
    location, digest = k_eval.trajectory_artifact(
        tmp_path, 1, "django__django-1", "run-1", replay)
    assert location.startswith("pass1.trajectories/")
    assert len(digest) == 64
    artifact = tmp_path / location
    assert k_eval.hashlib.sha256(artifact.read_bytes()).hexdigest() == digest
    content = json.loads(artifact.read_text())
    assert content["runId"] == "run-1"
    assert content["events"] == events
    assert "/Users/" not in location


def test_trajectory_artifact_rejects_non_array_event_log(tmp_path):
    with pytest.raises(ValueError, match="JSON object"):
        k_eval.trajectory_artifact(tmp_path, 1, "a", "run-1", [])
    with pytest.raises(ValueError, match="unavailable"):
        k_eval.trajectory_artifact(
            tmp_path, 1, "a", "run-1",
            {"available": False, "reason": "EXPIRED", "events": []})
    with pytest.raises(ValueError, match="empty"):
        k_eval.trajectory_artifact(
            tmp_path, 1, "a", "run-1",
            {"available": True, "truncated": False, "events": []})
    with pytest.raises(ValueError, match="truncated"):
        k_eval.trajectory_artifact(
            tmp_path, 1, "a", "run-1",
            {"available": True, "truncated": True, "events": [{"type": "LOG"}]})


def test_trajectory_artifact_recursively_redacts_credentials_and_paths(tmp_path):
    replay = {
        "available": True,
        "truncated": False,
        "lastEventId": "9",
        "events": [{
            "authorization": "Bearer header-secret-value",
            "nested": {
                "apiKey": "sk-abcdefghijklmnopqrstuvwxyz",
                "password": "REDACTED_FOR_PUBLIC_RELEASE",
                "message": (
                    "use Bearer abc.def.ghi and es_pat_abcdefghijklmnopqrstuvwxyz "
                    "with api_key=plain-text-provider-key and password=hunter2 "
                    "and PAT=bitbucket-plain-pat from "
                    "/Users/operator/Desktop/key_rotation.txt"
                ),
            },
        }],
    }
    location, _digest = k_eval.trajectory_artifact(
        tmp_path, 1, "a", "run-1", replay)
    serialized = (tmp_path / location).read_text()
    assert "header-secret-value" not in serialized
    assert "REDACTED_FOR_PUBLIC_RELEASE" not in serialized
    assert "plain-text-provider-key" not in serialized
    assert "hunter2" not in serialized
    assert "bitbucket-plain-pat" not in serialized
    assert "es_pat_" not in serialized
    assert "key_rotation.txt" not in serialized
    assert "[REDACTED]" in serialized
    assert "[REDACTED_PATH]" in serialized


def test_durable_trajectory_artifact_verifies_complete_checksum_contract(tmp_path, monkeypatch):
    encoded = b'{"type":"LOG","payload":"safe"}\n'
    digest = k_eval.hashlib.sha256(encoded).hexdigest()
    requested = []

    def fake_download(path, *_args, **_kwargs):
        requested.append(path)
        return (
            200,
            encoded,
            {
                "x-trajectory-truncated": "false",
                "x-trajectory-sha256": digest,
                "x-trajectory-event-count": "1",
            },
        )

    monkeypatch.setattr(
        k_eval,
        "get_bytes_authenticated",
        fake_download,
    )
    location, actual = k_eval.durable_trajectory_artifact(
        tmp_path, 1, "django__django-1", "run-1", {"t": "token"}, threading.Lock())
    assert location.endswith(".ndjson")
    assert actual == digest
    assert (tmp_path / location).read_bytes() == encoded
    assert requested == ["/api/v2/test/benchmark/runs/run-1/trajectory"]


@pytest.mark.parametrize(
    "headers,body,error",
    [
        ({"x-trajectory-truncated": "true", "x-trajectory-sha256": "a" * 64,
          "x-trajectory-event-count": "1"}, b"{}\n", "truncated"),
        ({"x-trajectory-truncated": "false", "x-trajectory-sha256": "0" * 64,
          "x-trajectory-event-count": "1"}, b"{}\n", "checksum"),
        ({"x-trajectory-truncated": "false", "x-trajectory-sha256":
          k_eval.hashlib.sha256(b"").hexdigest(), "x-trajectory-event-count": "0"},
         b"", "empty"),
    ],
)
def test_durable_trajectory_artifact_fails_closed(tmp_path, monkeypatch, headers, body, error):
    monkeypatch.setattr(
        k_eval,
        "get_bytes_authenticated",
        lambda *args, **kwargs: (200, body, headers),
    )
    with pytest.raises(ValueError, match=error):
        k_eval.durable_trajectory_artifact(
            tmp_path, 1, "a", "run-1", {"t": "token"}, threading.Lock())


def test_eta_uses_parallel_waves_and_is_unknown_before_first_completion():
    assert k_eval.estimate_eta_seconds([], 10, 4) is None
    assert k_eval.estimate_eta_seconds([10, 20], 5, 2) == 45
    assert k_eval.estimate_eta_seconds([10], 0, 4) == 0


def test_generate_emits_durable_started_progress_and_trajectory(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "django__django-1"}]))
    args = generate_args(tmp_path, dataset, 1)
    args.concurrency = 1
    args.per_task_cap_usd = k_eval.Decimal("2")
    args.campaign_cap_usd = k_eval.Decimal("2")
    args.opening_balance_usd = k_eval.Decimal("2")
    events = []
    starts = []
    monkeypatch.setattr(k_eval, "login", lambda: "token")
    monkeypatch.setattr(
        k_eval,
        "tracking_start",
        lambda *values: starts.append(values) or "benchmark-id",
    )
    monkeypatch.setattr(
        k_eval,
        "tracking_event",
        lambda _token, _lock, _bench, body: events.append(body.copy()),
    )
    monkeypatch.setattr(
        k_eval,
        "post_authenticated",
        lambda *args, **kwargs: (
            200,
            {
                "outcome": "COMPLETED",
                "patchBytes": 42,
                "runId": "run-1",
                "iterations": 7,
                "proPatchJson": None,
                "predsJson": "/var/private/preds.json",
                "submissionPatch": "/var/private/submission.patch",
                "costUsd": "0.25",
                "inputTokens": 100,
                "outputTokens": 20,
            },
        ),
    )
    trajectory = b'{"type":"RUN_COMPLETED"}\n'
    monkeypatch.setattr(
        k_eval,
        "get_bytes_authenticated",
        lambda *args, **kwargs: (
            200,
            trajectory,
            {"x-trajectory-truncated": "false",
             "x-trajectory-sha256": k_eval.hashlib.sha256(trajectory).hexdigest(),
             "x-trajectory-event-count": "1"},
        ),
    )

    k_eval.cmd_generate(args)

    tracking = json.loads((tmp_path / "out" / "pass1.tracking.json").read_text())
    manifest = k_eval.load_generation_manifest(tmp_path / "out" / "pass1.manifest.jsonl")
    row = manifest["django__django-1"]
    assert tracking["submissionRequired"] is False
    assert starts[0][-1] is False
    assert any(event["type"] == "TASK_STARTED" and event["attempt"] == 1 for event in events)
    progress = next(event for event in events if event["type"] == "RUN_PROGRESS"
                    and event.get("taskName") == "django__django-1")
    assert progress["attempt"] == 1
    assert progress["etaSeconds"] == 0
    assert (tmp_path / "out" / row["trajectoryLocation"]).is_file()
    assert len(row["trajectorySha256"]) == 64


def configure_generate_without_network(monkeypatch, events):
    monkeypatch.setattr(k_eval, "login", lambda: "token")
    monkeypatch.setattr(k_eval, "tracking_start", lambda *args: "benchmark-id")
    monkeypatch.setattr(
        k_eval,
        "tracking_event",
        lambda _token, _lock, _bench, body: events.append(body.copy()),
    )
    monkeypatch.setattr(
        k_eval,
        "durable_trajectory_artifact",
        lambda _out, _attempt, instance_id, *_args: (
            f"trajectories/{instance_id}.ndjson", "a" * 64),
    )


def successful_generation(instance_id):
    return {
        "outcome": "COMPLETED",
        "patchBytes": 42,
        "runId": f"run-{instance_id}",
        "iterations": 7,
        "proPatchJson": None,
        "predsJson": f"/private/{instance_id}.preds.json",
        "submissionPatch": f"/private/{instance_id}.patch",
        "costUsd": "0.25",
        "inputTokens": 100,
        "outputTokens": 20,
    }


def test_cmd_generate_releases_exact_semaphore_429_before_aborting(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "a"}]))
    args = generate_args(tmp_path, dataset, 1)
    args.concurrency = 1
    args.per_task_cap_usd = k_eval.Decimal("1")
    args.campaign_cap_usd = k_eval.Decimal("1")
    args.opening_balance_usd = k_eval.Decimal("1")
    events = []
    configure_generate_without_network(monkeypatch, events)

    def semaphore_429(*_args, **_kwargs):
        body = json.dumps({"message": "benchmark generation concurrency exhausted for a"})
        raise urllib.error.HTTPError("/generate", 429, "busy", {}, io.BytesIO(body.encode()))

    monkeypatch.setattr(k_eval, "post_authenticated", semaphore_429)

    with pytest.raises(SystemExit, match="adaptive generation stopped fail-closed"):
        k_eval.cmd_generate(args)

    ledger = json.loads((tmp_path / "out" / "campaign.budget.json").read_text())
    attempt = json.loads((tmp_path / "out" / "pass1.attempts.jsonl").read_text())
    assert ledger["tasks"] == {}
    assert attempt["dispatchClassification"] == k_eval.PRE_PROVIDER_CONCURRENCY_429
    assert attempt["reservationState"] == "RELEASED_PRE_PROVIDER"
    assert events[-1]["type"] == "RUN_FAILED"


def test_cmd_generate_preserves_ambiguous_connect_reservation_and_stops(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "a"}, {"instance_id": "b"}]))
    args = generate_args(tmp_path, dataset, 2)
    args.concurrency = 1
    args.per_task_cap_usd = k_eval.Decimal("1")
    args.campaign_cap_usd = k_eval.Decimal("2")
    args.opening_balance_usd = k_eval.Decimal("2")
    events = []
    configure_generate_without_network(monkeypatch, events)
    calls = []

    def ambiguous_connect(_path, body, *_args, **_kwargs):
        calls.append(body["instanceId"])
        raise urllib.error.URLError("connection reset after request dispatch")

    monkeypatch.setattr(k_eval, "post_authenticated", ambiguous_connect)

    with pytest.raises(SystemExit, match="reconcile preserved reservations"):
        k_eval.cmd_generate(args)

    ledger = json.loads((tmp_path / "out" / "campaign.budget.json").read_text())
    assert calls == ["a"]
    assert ledger["tasks"]["pass1:a"]["status"] == "RESERVED"
    assert "pass1:b" not in ledger["tasks"]


def test_cmd_generate_steps_down_and_retries_only_proven_pre_provider_task(
        tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    instances = ["a", "b", "c", "d", "e"]
    dataset.write_text(json.dumps([{"instance_id": value} for value in instances]))
    args = generate_args(tmp_path, dataset, len(instances))
    args.concurrency = 4
    args.per_task_cap_usd = k_eval.Decimal("1")
    args.campaign_cap_usd = k_eval.Decimal("5")
    args.opening_balance_usd = k_eval.Decimal("5")
    events = []
    configure_generate_without_network(monkeypatch, events)
    calls = {}
    lock = threading.Lock()

    def generate(_path, body, *_args, **_kwargs):
        instance_id = body["instanceId"]
        with lock:
            calls[instance_id] = calls.get(instance_id, 0) + 1
            attempt = calls[instance_id]
        if instance_id == "b" and attempt == 1:
            payload = json.dumps(
                {"message": "benchmark generation concurrency exhausted for b"}).encode()
            raise urllib.error.HTTPError(
                "/generate", 429, "busy", {}, io.BytesIO(payload))
        return 200, successful_generation(instance_id)

    monkeypatch.setattr(k_eval, "post_authenticated", generate)

    k_eval.cmd_generate(args)

    manifest = k_eval.load_generation_manifest(tmp_path / "out" / "pass1.manifest.jsonl")
    assert set(manifest) == set(instances)
    assert calls == {"a": 1, "b": 2, "c": 1, "d": 1, "e": 1}
    assert len((tmp_path / "out" / "pass1.attempts.jsonl").read_text().splitlines()) == 6


def test_max_new_tasks_resumes_one_campaign_without_duplicate_launch(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    instances = ["a", "b", "c", "d"]
    dataset.write_text(json.dumps([{"instance_id": value} for value in instances]))
    args = generate_args(tmp_path, dataset, len(instances))
    args.per_task_cap_usd = k_eval.Decimal("1")
    args.campaign_cap_usd = k_eval.Decimal("4")
    args.opening_balance_usd = k_eval.Decimal("4")
    events = []
    configure_generate_without_network(monkeypatch, events)
    calls = []
    monkeypatch.setattr(
        k_eval,
        "post_authenticated",
        lambda _path, body, *_args, **_kwargs: (
            calls.append(body["instanceId"]) or 200,
            successful_generation(body["instanceId"]),
        ),
    )

    args.concurrency = 1
    args.max_new_tasks = 1
    k_eval.cmd_generate(args)
    assert set(k_eval.load_generation_manifest(
        tmp_path / "out" / "pass1.manifest.jsonl")) == {"a"}
    assert events[-1]["type"] == "RUN_PROGRESS"

    args.concurrency = 2
    args.max_new_tasks = 2
    k_eval.cmd_generate(args)
    assert set(k_eval.load_generation_manifest(
        tmp_path / "out" / "pass1.manifest.jsonl")) == {"a", "b", "c"}
    assert events[-1]["type"] == "RUN_PROGRESS"

    args.concurrency = 4
    args.max_new_tasks = 1
    k_eval.cmd_generate(args)
    manifest = k_eval.load_generation_manifest(tmp_path / "out" / "pass1.manifest.jsonl")
    ledger = json.loads((tmp_path / "out" / "campaign.budget.json").read_text())
    assert set(manifest) == set(instances)
    assert calls == instances
    assert len(calls) == len(set(calls))
    assert all(value["status"] == "SETTLED" for value in ledger["tasks"].values())


def test_submission_required_rejects_k_greater_than_one_before_login(
        tmp_path, monkeypatch):
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{"instance_id": "a"}]))
    args = generate_args(tmp_path, dataset, 1)
    args.k = 2
    args.submission_required = True
    monkeypatch.setattr(
        k_eval, "login", lambda: pytest.fail("login must not occur before submission validation"))
    with pytest.raises(SystemExit, match="pass@1"):
        k_eval.cmd_generate(args)


def test_publish_carries_scorer_classification_and_trajectory(monkeypatch, tmp_path):
    tracking = tmp_path / "pass1.tracking.json"
    tracking.write_text(json.dumps({
        "benchmarkId": "benchmark-id", "attempt": 1, "totalTasks": 2,
        "submissionRequired": True,
    }))
    manifest = tmp_path / "pass1.manifest.jsonl"
    first = terminal_row("passed") | {
        "trajectoryLocation": "pass1.trajectories/passed.json",
        "trajectorySha256": "a" * 64,
    }
    second = terminal_row("timed-out") | {
        "trajectoryLocation": "pass1.trajectories/timed-out.json",
        "trajectorySha256": "b" * 64,
    }
    write_jsonl(manifest, [first, second])
    results = tmp_path / "results.json"
    results.write_text(json.dumps({
        "submitted_ids": ["passed", "timed-out"],
        "resolved_ids": ["passed"],
        "unresolved_ids": ["timed-out"],
        "empty_patch_ids": [],
        "error_ids": [],
    }))
    posted = []
    monkeypatch.setattr(k_eval, "login", lambda: "token")
    monkeypatch.setattr(
        k_eval, "post_authenticated",
        lambda path, body, *args, **kwargs: posted.append((path, body.copy())) or (200, {}),
    )
    args = argparse.Namespace(
        tracking_file=str(tracking), benchmark_id=None, attempt=0,
        results=str(results), manifest=str(manifest), expected_total=None,
    )

    k_eval.cmd_publish(args)

    scored = [body for _path, body in posted if body["type"] == "TASK_SCORED"]
    assert len(scored) == 2
    assert next(row for row in scored if row["taskName"] == "passed")[
        "failureClassification"] == "NONE"
    failed = next(row for row in scored if row["taskName"] == "timed-out")
    assert failed["failureClassification"] == "AGENT_OR_TEST_FAILURE"
    assert failed["trajectoryLocation"] == "pass1.trajectories/timed-out.json"
    assert failed["trajectorySha256"] == "b" * 64


def recovery_fixture(tmp_path):
    out = tmp_path / "campaign"
    out.mkdir()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    output_dir = tmp_path / "generation-output"
    output_dir.mkdir()
    instance_id = "astropy__astropy-12907"
    run_id = "c5278309-a985-46c5-bd85-3525ef0d1db7"
    base_commit = "d" * 40
    image = "swebench/test@sha256:" + "f" * 64
    dataset = tmp_path / "dataset.json"
    dataset.write_text(json.dumps([{
        "instance_id": instance_id,
        "base_commit": base_commit,
    }]))
    trajectory = out / "pass1.trajectories" / "run.ndjson"
    trajectory.parent.mkdir()
    trajectory.write_bytes(b'{"type":"TOOL_CALL"}\n{"type":"RUN_COMPLETED"}\n')
    trajectory_sha = k_eval.sha256_file(trajectory)
    patch = b"diff --git a/example.py b/example.py\n+fixed = True\n"
    patch_sha = k_eval.sha256_bytes(patch)
    workspace_sha = "1" * 64
    failed_tracker = "6fa6f3ad-08a9-452d-a744-595572b242d8"
    tracking = {
        "benchmarkId": failed_tracker,
        "provider": "openrouter",
        "model": "glm-5.2",
        "attempt": 1,
        "totalTasks": 1,
        "submissionRequired": False,
    }
    tracking_file = out / "pass1.tracking.json"
    tracking_file.write_text(json.dumps(tracking))
    write_jsonl(out / "pass1.attempts.jsonl", [{
        "instance_id": instance_id,
        "pass": 1,
        "status": 500,
        "dispatchClassification": k_eval.ABORT_AMBIGUOUS,
        "reservationState": "PRESERVED_INDETERMINATE",
        "error": "HTTP 500 benchmark patch capture failed: dubious ownership",
    }])
    budget = k_eval.CampaignBudget(out / "campaign.budget.json", "2", "2", "2", 1)
    budget.reserve("pass1:" + instance_id)
    evidence = {
        "schemaVersion": 1,
        "instanceId": instance_id,
        "runId": run_id,
        "provider": "openrouter",
        "model": "glm-5.2",
        "datasetSha256": k_eval.sha256_file(dataset),
        "baseCommit": base_commit,
        "image": image,
        "workspace": str(workspace.resolve()),
        "outputDir": str(output_dir.resolve()),
        "pipeline": {
            "status": "FAILED",
            "originalHttpStatus": 500,
            "statusReason": k_eval.CAPTURE_FAILURE_PREFIX + ": dubious ownership",
        },
        "engine": {"outcome": "COMPLETED", "iterations": 14, "toolCalls": 14},
        "usage": {
            "calls": 15,
            "intents": 15,
            "terminalUsage": 15,
            "outboxCompleted": 15,
            "unmatchedIntents": 0,
            "inputTokens": 179765,
            "outputTokens": 2306,
            "cacheReadTokens": 150272,
            "cacheWriteTokens": 0,
            "reasoningTokens": 533,
            "costUsd": "0.090212",
        },
        "trajectory": {"sha256": trajectory_sha, "eventCount": 2},
        "failedBenchmarkId": failed_tracker,
    }
    evidence_file = tmp_path / "server-evidence.json"
    evidence_file.write_text(json.dumps(evidence))
    args = argparse.Namespace(
        dataset=str(dataset), expected_total=1, out=str(out), pass_number=1,
        instance_id=instance_id, run_id=run_id, provider="openrouter", model="glm-5.2",
        workspace=str(workspace), output_dir=str(output_dir), image=image, workdir="/testbed",
        pro=False, server_evidence=str(evidence_file), trajectory=str(trajectory),
        trajectory_sha256=trajectory_sha, workspace_evidence_sha256=workspace_sha,
        expected_patch_sha256=patch_sha, expected_patch_bytes=len(patch),
        per_task_cap_usd=k_eval.Decimal("2"), campaign_cap_usd=k_eval.Decimal("2"),
        opening_balance_usd=k_eval.Decimal("2"),
        new_tracker_id="recovered-tracker-0001",
    )
    workspace_state = {
        "head": base_commit,
        "patch": patch,
        "patchBytes": len(patch),
        "patchSha256": patch_sha,
        "workspaceEvidenceSha256": workspace_sha,
    }
    return args, evidence, evidence_file, patch, workspace_state, tracking_file


def forbid_recovery_network(monkeypatch):
    for name in ("login", "post", "post_authenticated"):
        monkeypatch.setattr(
            k_eval, name,
            lambda *args, _name=name, **kwargs: pytest.fail(
                f"provider-free recovery invoked {_name}"))


def test_recover_capture_is_provider_free_atomic_and_idempotent(tmp_path, monkeypatch):
    args, _evidence, _evidence_file, patch, workspace_state, tracking_file = \
        recovery_fixture(tmp_path)
    original_tracking = tracking_file.read_bytes()
    captures = []
    forbid_recovery_network(monkeypatch)
    monkeypatch.setattr(
        k_eval, "inspect_recovery_workspace", lambda *unused: workspace_state.copy())
    monkeypatch.setattr(
        k_eval, "capture_recovered_diff",
        lambda *unused: captures.append("captured") or patch)

    k_eval.cmd_recover_capture(args)
    k_eval.cmd_recover_capture(args)

    out = Path(args.out)
    manifest = k_eval.load_generation_manifest(out / "pass1.manifest.jsonl")
    row = manifest[args.instance_id]
    assert row["status"] == k_eval.RECOVERED_CAPTURE_STATUS
    assert row["status"] != 200
    assert row["providerDispatch"] is False
    assert row["usageCalls"] == row["usageIntents"] == row["terminalUsageRows"] == 15
    assert row["outboxCompletedRows"] == 15
    assert row["unmatchedUsageIntents"] == 0
    assert row["cacheReadTokens"] == 150272
    assert row["reasoningTokens"] == 533
    assert Path(row["submissionPatch"]).read_bytes() == patch
    assert row["predsSha256"] == k_eval.sha256_file(Path(row["predsJson"]))
    assert json.loads(Path(row["predsJson"]).read_text())[args.instance_id][
        "model_patch"] == patch.decode()
    assert captures == ["captured"]
    ledger = json.loads((out / "campaign.budget.json").read_text())
    assert ledger["tasks"]["pass1:" + args.instance_id]["status"] == "SETTLED"
    audits = [json.loads(line) for line in (out / "pass1.attempts.jsonl").read_text().splitlines()]
    assert len([row for row in audits if row.get("type") ==
                "CAPTURE_RECOVERED_PROVIDER_FREE"]) == 1
    assert tracking_file.read_bytes() == original_tracking
    lineage = json.loads((out / "pass1.recovery-tracking.json").read_text())
    assert lineage["benchmarkId"] == args.new_tracker_id
    assert lineage["previousFailedBenchmarkId"] == "6fa6f3ad-08a9-452d-a744-595572b242d8"
    assert not list(out.rglob("*.tmp"))


def test_recovery_workspace_inspection_uses_isolated_protected_git_config(tmp_path):
    workspace = tmp_path / "repo"
    workspace.mkdir()
    k_eval.run_local(["git", "init", "-q"], cwd=workspace)
    k_eval.run_local(["git", "config", "user.email", "test@example.com"], cwd=workspace)
    k_eval.run_local(["git", "config", "user.name", "Test"], cwd=workspace)
    source = workspace / "example.py"
    source.write_text("value = 1\n")
    k_eval.run_local(["git", "add", "example.py"], cwd=workspace)
    k_eval.run_local(["git", "commit", "-q", "-m", "base"], cwd=workspace)
    base = k_eval.run_local(["git", "rev-parse", "HEAD"], cwd=workspace).decode().strip()
    source.write_text("value = 2\n")

    inspected = k_eval.inspect_recovery_workspace(workspace, base, False)

    assert inspected["head"] == base
    assert inspected["patchBytes"] > 0
    assert b"-value = 1" in inspected["patch"]
    assert b"+value = 2" in inspected["patch"]
    assert len(inspected["workspaceEvidenceSha256"]) == 64


def test_recovery_capture_never_pulls_image(tmp_path, monkeypatch):
    patch = b"diff --git a/example.py b/example.py\n"
    state = {
        "patch": patch,
        "workspaceEvidenceSha256": "a" * 64,
    }
    docker_calls = []

    def local(args, cwd=None, env=None):
        if args[0] == "docker":
            docker_calls.append(args)
            return patch
        return b""

    monkeypatch.setattr(k_eval, "run_local", local)
    monkeypatch.setattr(
        k_eval, "inspect_recovery_workspace", lambda *unused: state.copy())

    captured = k_eval.capture_recovered_diff(
        tmp_path, tmp_path, "swebench/test@sha256:" + "f" * 64,
        "/testbed", "d" * 40, False)

    assert captured == patch
    assert len(docker_calls) == 1
    assert "--pull=never" in docker_calls[0]
    assert docker_calls[0][docker_calls[0].index("--network") + 1] == "none"


def test_generate_prefers_validated_recovery_tracker_without_reusing_failed_tracker(
        tmp_path, monkeypatch):
    args, _evidence, _evidence_file, patch, workspace_state, tracking_file = \
        recovery_fixture(tmp_path)
    failed_tracking = tracking_file.read_bytes()
    forbid_recovery_network(monkeypatch)
    monkeypatch.setattr(
        k_eval, "inspect_recovery_workspace", lambda *unused: workspace_state.copy())
    monkeypatch.setattr(k_eval, "capture_recovered_diff", lambda *unused: patch)
    k_eval.cmd_recover_capture(args)

    events = []
    monkeypatch.setattr(k_eval, "login", lambda: "token")
    monkeypatch.setattr(
        k_eval, "tracking_event",
        lambda _token, _lock, benchmark_id, body: events.append((benchmark_id, body.copy())))
    monkeypatch.setattr(
        k_eval, "post_authenticated",
        lambda *unused, **kwargs: pytest.fail("completed recovery must not redispatch provider"))
    generate = generate_args(tmp_path, Path(args.dataset), 1)
    generate.out = args.out
    generate.provider = args.provider
    generate.model = args.model
    generate.concurrency = 1
    generate.per_task_cap_usd = args.per_task_cap_usd
    generate.campaign_cap_usd = args.campaign_cap_usd
    generate.opening_balance_usd = args.opening_balance_usd

    k_eval.cmd_generate(generate)

    assert events
    assert {benchmark_id for benchmark_id, _body in events} == {args.new_tracker_id}
    assert tracking_file.read_bytes() == failed_tracking


def test_generate_rejects_malformed_recovery_tracker_before_dispatch(tmp_path, monkeypatch):
    args, _evidence, _evidence_file, _patch, _workspace_state, _tracking = \
        recovery_fixture(tmp_path)
    recovery = Path(args.out) / "pass1.recovery-tracking.json"
    recovery.write_text(json.dumps({
        "benchmarkId": "new-tracker",
        "previousFailedBenchmarkId": "wrong-failed-tracker",
        "recoveredRunId": args.run_id,
        "recoveryEvidenceSha256": "a" * 64,
    }))
    monkeypatch.setattr(k_eval, "login", lambda: "token")
    monkeypatch.setattr(
        k_eval, "post_authenticated",
        lambda *unused, **kwargs: pytest.fail("malformed lineage must not dispatch provider"))
    generate = generate_args(tmp_path, Path(args.dataset), 1)
    generate.out = args.out
    generate.provider = args.provider
    generate.model = args.model
    generate.concurrency = 1
    generate.per_task_cap_usd = args.per_task_cap_usd
    generate.campaign_cap_usd = args.campaign_cap_usd
    generate.opening_balance_usd = args.opening_balance_usd

    with pytest.raises(SystemExit, match="tracking lineage validation failed"):
        k_eval.cmd_generate(generate)


@pytest.mark.parametrize(
    "mutate,error",
    [
        (lambda args, evidence: evidence["pipeline"].update({"status": "COMPLETE"}),
         "capture-failure pipeline proof"),
        (lambda args, evidence: evidence["usage"].update({"outboxCompleted": 14}),
         "usage intents/terminal rows are incomplete"),
        (lambda args, evidence: setattr(args, "trajectory_sha256", "0" * 64),
         "trajectory hash/count verification failed"),
        (lambda args, evidence: setattr(args, "workspace_evidence_sha256", "0" * 64),
         "workspace evidence SHA-256 mismatch"),
        (lambda args, evidence: setattr(args, "image", "swebench/test:latest"),
         "pinned by sha256 digest"),
    ],
)
def test_recover_capture_fails_closed_before_artifacts(
        tmp_path, monkeypatch, mutate, error):
    args, evidence, evidence_file, patch, workspace_state, _tracking = recovery_fixture(tmp_path)
    mutate(args, evidence)
    evidence_file.write_text(json.dumps(evidence))
    forbid_recovery_network(monkeypatch)
    monkeypatch.setattr(
        k_eval, "inspect_recovery_workspace", lambda *unused: workspace_state.copy())
    monkeypatch.setattr(
        k_eval, "capture_recovered_diff",
        lambda *unused: pytest.fail("capture must not occur after failed recovery proof"))

    with pytest.raises(SystemExit, match=error):
        k_eval.cmd_recover_capture(args)

    assert not (Path(args.output_dir) / "submission.patch").exists()
    assert not (Path(args.output_dir) / "preds.json").exists()
    ledger = json.loads((Path(args.out) / "campaign.budget.json").read_text())
    assert ledger["tasks"]["pass1:" + args.instance_id]["status"] == "RESERVED"


def test_recover_capture_requires_preserved_indeterminate_attempt(tmp_path, monkeypatch):
    args, _evidence, _evidence_file, _patch, workspace_state, _tracking = \
        recovery_fixture(tmp_path)
    attempts = Path(args.out) / "pass1.attempts.jsonl"
    row = json.loads(attempts.read_text())
    row["reservationState"] = "RELEASED_PRE_PROVIDER"
    write_jsonl(attempts, [row])
    forbid_recovery_network(monkeypatch)
    monkeypatch.setattr(
        k_eval, "inspect_recovery_workspace", lambda *unused: workspace_state.copy())

    with pytest.raises(SystemExit, match="exactly one preserved-indeterminate"):
        k_eval.cmd_recover_capture(args)


def test_recovered_manifest_requires_exact_provenance(tmp_path):
    manifest = tmp_path / "manifest.jsonl"
    row = terminal_row() | {"status": k_eval.RECOVERED_CAPTURE_STATUS}
    write_jsonl(manifest, [row])
    with pytest.raises(SystemExit, match="missing"):
        k_eval.load_generation_manifest(manifest)


def test_result_evidence_classifies_explicit_timeout_without_guessing(tmp_path):
    result = tmp_path / "results.json"
    result.write_text(json.dumps([
        {"instance_id": "timeout", "reward": 0, "outcome": "TIMEOUT",
         "reason": "agent timed out"},
        {"instance_id": "ambiguous", "reward": 0},
    ]))
    evidence = k_eval.load_result_evidence(result)
    assert evidence["timeout"]["failureClassification"] == "TIMEOUT"
    assert evidence["ambiguous"]["failureClassification"] == "UNKNOWN"


def test_official_result_rejects_duplicate_terminal_ids(tmp_path):
    result = tmp_path / "results.json"
    result.write_text(json.dumps({"submitted_ids": ["a", "a"], "resolved_ids": ["a"]}))
    with pytest.raises(SystemExit, match="duplicate submitted_ids"):
        k_eval.load_results(result)


def test_official_result_requires_exact_terminal_classification(tmp_path):
    result = tmp_path / "results.json"
    result.write_text(json.dumps({"submitted_ids": ["a", "b"], "resolved_ids": ["a"]}))
    with pytest.raises(SystemExit, match="classification mismatch"):
        k_eval.load_results(result)


def test_official_result_rejects_duplicate_category_rows(tmp_path):
    result = tmp_path / "results.json"
    result.write_text(
        json.dumps({"submitted_ids": ["a"], "resolved_ids": ["a", "a"]}))
    with pytest.raises(SystemExit, match="duplicate ids"):
        k_eval.load_results(result)
