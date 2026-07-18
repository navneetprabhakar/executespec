import argparse
import importlib.util
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
    )


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


def test_tracking_start_requires_server_id(monkeypatch):
    monkeypatch.setattr(k_eval, "post_authenticated", lambda *args, **kwargs: (200, {}))
    with pytest.raises(RuntimeError, match="no benchmark id"):
        k_eval.tracking_start(
            {"t": "token"}, threading.Lock(), "openrouter", "glm-5.2", 1, 10, "lite10")


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


def test_campaign_budget_resume_fails_on_unresolved_reservation(tmp_path):
    path = tmp_path / "budget.json"
    budget = k_eval.CampaignBudget(path, "2", "20", "25", 10)
    budget.reserve("a")
    resumed = k_eval.CampaignBudget(path, "2", "20", "25", 10)
    with pytest.raises(RuntimeError, match="unresolved reservation"):
        resumed.reserve("a")


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
