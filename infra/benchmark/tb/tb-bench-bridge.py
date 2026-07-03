#!/usr/bin/env python3
"""Bridge a local/VM Harbor Terminal-Bench job dir → the ExecuteSpec benchmark dashboard.

Runs ALONGSIDE a `harbor run`: POSTs a benchmark_run `start`, polls the job dir for each
trial's `result.json` (written when a trial finishes), POSTs one `TASK_SCORED` per *trial*
(= per attempt — so k>1 aggregates correctly), and `RUN_DONE` when all trials are in or no new
trial appears for --idle-min (Harbor done/stuck). Each TASK_SCORED carries the trial's
reward + esRunId + cost + input/output tokens (from `agent_result` in result.json), plus a
best-effort outcome/reason so the dashboard distinguishes verifier failures from infra failures.

Auth: BR_TOKEN, or BR_ADMIN_EMAIL + BR_ADMIN_PW. Base: BR_ES_BASE.
"""
import argparse
import glob
import json
import os
import re
import sys
import time
import urllib.request


def _req(base, path, token, method="GET", body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        t = resp.read().decode()
        return json.loads(t) if t.strip() else {}


def _login(base, email, pw):
    d = _req(base, "/api/auth/login", None, "POST", {"email": email, "password": pw})
    return d.get("accessToken") or d.get("token")


def _first_text(*vals):
    for v in vals:
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None


def _classify_trial(result, reward):
    """Return (outcome, reason) independent of reward math."""
    if reward > 0:
        return "PASSED", None
    setup_failure = _setup_failure_reason(result)
    if setup_failure:
        return "INFRA_SETUP_FAILED", setup_failure
    ar = result.get("agent_result") or {}
    vr = result.get("verifier_result") or {}
    status = _first_text(
        result.get("status"),
        result.get("outcome"),
        ar.get("status"),
        ar.get("outcome"),
        vr.get("status"),
        vr.get("outcome"),
    )
    reason = _first_text(
        result.get("error"),
        result.get("exception"),
        result.get("failure_reason"),
        ar.get("error"),
        ar.get("exception"),
        ar.get("failure_reason"),
        ar.get("message"),
        vr.get("error"),
        vr.get("exception"),
        vr.get("failure_reason"),
        vr.get("message"),
    )
    hay = " ".join(x for x in (status, reason) if x).lower()
    if "timeout" in hay or "timed out" in hay:
        return "TIMEOUT", reason or status
    if any(w in hay for w in ("container", "docker", "harbor", "bridge", "tool")):
        return "INFRA_FAILED", reason or status
    if any(w in hay for w in ("verifier", "verify", "reward")):
        return "VERIFIER_FAILED", reason or status
    if any(w in hay for w in ("agent", "worker", "llm", "model")):
        return "AGENT_FAILED", reason or status
    return "FAILED", reason or status


def _setup_failure_reason(result):
    """Detect Harbor failures before the ExecuteSpec agent/verifier ran."""
    if result.get("agent_result") is not None or result.get("verifier_result") is not None:
        return None
    exc = result.get("exception_info") or result.get("exception") or result.get("error")
    env = result.get("environment_setup") or {}
    execution = result.get("agent_execution")
    verifier = result.get("verifier")
    if not exc and not env:
        return None
    text = json.dumps(
        {
            "exception_info": exc,
            "environment_setup": env,
            "agent_execution": execution,
            "verifier": verifier,
        },
        default=str,
    )
    hay = text.lower()
    if any(
        marker in hay
        for marker in (
            "docker",
            "compose",
            "container",
            "image",
            "registry",
            "environment",
            "setup",
            "harbor",
        )
    ):
        compact = " ".join(text.split())
        return compact[:1200]
    return None


def _scan(job_dir):
    """{trial_dir: {task, reward, esRunId, cost, in_tok, out_tok}} from every
    <task>__<hash>/result.json. One entry per trial (= per attempt), so k>1 is preserved."""
    out = {}
    for f in glob.glob(os.path.join(job_dir, "*", "result.json")):
        trial = os.path.basename(os.path.dirname(f))  # <task>__<hash> — unique per attempt
        try:
            d = json.load(open(f))
        except Exception:  # noqa: BLE001
            continue
        task = re.sub(r"__[A-Za-z0-9]+$", "", trial)  # strip the trial hash
        ar = d.get("agent_result") or {}
        vr = d.get("verifier_result") or {}
        rew = (vr.get("rewards") or {}).get("reward", 0)
        try:
            rew = int(float(rew))
        except Exception:  # noqa: BLE001
            rew = 0
        meta = ar.get("metadata") or {}
        outcome, reason = _classify_trial(d, rew)
        out[trial] = {
            "task": task,
            "reward": rew,
            "esRunId": meta.get("es_run_id"),
            "cost": ar.get("cost_usd"),
            "in_tok": ar.get("n_input_tokens"),
            "out_tok": ar.get("n_output_tokens"),
            "outcome": outcome,
            "reason": reason,
            "fatal": outcome == "INFRA_SETUP_FAILED",
        }
    return out


def _fail_run(base, token, rid, message, fail_marker=None, payload=None):
    body = {"type": "RUN_FAILED", "message": message}
    if payload is not None:
        body["payload"] = json.dumps(payload)
    _req(base, f"/api/v2/test/benchmark/{rid}/event", token, "POST", body)
    if fail_marker:
        with open(fail_marker, "w", encoding="utf-8") as fh:
            fh.write(message + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--job-dir", required=True)
    ap.add_argument("--suite", default="TERMINAL_BENCH")
    ap.add_argument("--dataset", default="terminal-bench-2")
    ap.add_argument("--provider", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--k", type=int, default=1)
    ap.add_argument("--total", type=int, required=True)  # number of TASKS (not trials)
    ap.add_argument("--poll", type=int, default=15)
    ap.add_argument("--max-min", type=int, default=600)
    ap.add_argument("--idle-min", type=int, default=25)  # finalize if no new trial for this long
    ap.add_argument("--fail-marker")
    a = ap.parse_args()

    base = os.environ["BR_ES_BASE"]
    token = os.environ.get("BR_TOKEN") or _login(
        base, os.environ["BR_ADMIN_EMAIL"], os.environ["BR_ADMIN_PW"]
    )
    if not token:
        print("bridge: login failed")
        return 1

    run = _req(
        base,
        "/api/v2/test/benchmark/start",
        token,
        "POST",
        {
            "suite": a.suite,
            "dataset": a.dataset,
            "provider": a.provider,
            "model": a.model,
            "k": a.k,
            "totalTasks": a.total,
        },
    )
    rid = run["id"]
    print("bridge: benchmark_run id =", rid, flush=True)
    print("bridge: dashboard =", base.rstrip("/") + "/benchmark", flush=True)

    expected_trials = max(a.total, a.total * a.k)
    posted = set()  # trial dirs already posted
    attempts = {}  # task -> attempt counter
    deadline = time.time() + a.max_min * 60
    last_change = time.time()
    idle_limit = a.idle_min * 60
    while time.time() < deadline:
        trials = _scan(a.job_dir)
        fatal_trial = next(
            ((trial, info) for trial, info in sorted(trials.items()) if info.get("fatal")),
            None,
        )
        if fatal_trial:
            trial, info = fatal_trial
            msg = (
                "INVALID BENCHMARK RUN: Harbor failed during environment setup before "
                f"ExecuteSpec agent execution for task={info['task']} trial={trial}; "
                "do not use this score for model comparison."
            )
            try:
                _fail_run(
                    base,
                    token,
                    rid,
                    msg,
                    a.fail_marker,
                    {"trial": trial, "task": info["task"], "reason": info.get("reason")},
                )
            except Exception as e:  # noqa: BLE001
                print("bridge: RUN_FAILED post failed", e, flush=True)
                if a.fail_marker:
                    with open(a.fail_marker, "w", encoding="utf-8") as fh:
                        fh.write(msg + "\n")
            print(f"bridge: fatal setup failure — {msg}", flush=True)
            return 2
        for trial, info in sorted(trials.items()):
            if trial in posted:
                continue
            task = info["task"]
            attempts[task] = attempts.get(task, 0) + 1
            body = {
                "type": "TASK_SCORED",
                "taskName": task,
                "attempt": attempts[task],
                "reward": info["reward"],
            }
            if info["esRunId"]:
                body["esRunId"] = info["esRunId"]
            if info["cost"] is not None:
                body["costUsd"] = info["cost"]
            if info["in_tok"] is not None:
                body["inputTokens"] = int(info["in_tok"])
            if info["out_tok"] is not None:
                body["outputTokens"] = int(info["out_tok"])
            if info.get("outcome"):
                body["outcome"] = info["outcome"]
            if info.get("reason"):
                body["reason"] = info["reason"]
            try:
                _req(base, f"/api/v2/test/benchmark/{rid}/event", token, "POST", body)
                posted.add(trial)
                last_change = time.time()
                resolved = len(
                    {trials[t]["task"] for t in posted if trials[t]["reward"] > 0}
                )
                print(
                    f"bridge: {task} att={attempts[task]} reward={info['reward']} "
                    f"in={info['in_tok']} out={info['out_tok']}  "
                    f"({len(posted)}/{expected_trials} trials, {resolved} tasks resolved)",
                    flush=True,
                )
            except Exception as e:  # noqa: BLE001
                print("bridge: post failed", trial, e, flush=True)
        if len(posted) >= expected_trials:
            break
        if posted and (time.time() - last_change) > idle_limit:
            print(f"bridge: no new trial for {a.idle_min}m — finalizing", flush=True)
            break
        time.sleep(a.poll)

    final = _scan(a.job_dir)
    resolved = len({final[t]["task"] for t in posted if t in final and final[t]["reward"] > 0})
    try:
        _req(base, f"/api/v2/test/benchmark/{rid}/event", token, "POST", {"type": "RUN_DONE"})
    except Exception as e:  # noqa: BLE001
        print("bridge: RUN_DONE post failed", e, flush=True)
    print(
        f"bridge: done — posted {len(posted)}/{expected_trials} trials, "
        f"{resolved} tasks resolved. run={rid}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
