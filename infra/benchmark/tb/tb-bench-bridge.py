#!/usr/bin/env python3
"""Bridge a local/VM Harbor Terminal-Bench job dir → the ExecuteSpec benchmark dashboard.

Runs ALONGSIDE a `harbor run`: POSTs a benchmark_run `start`, polls the job dir for each
trial, POSTing `TASK_STARTED` as soon as Harbor creates the trial directory and one
`TASK_SCORED` per *trial* when its `result.json` is written.
(= per attempt — so k>1 aggregates correctly), and `RUN_DONE` when all trials are in or no new
trial appears for --idle-min (Harbor done/stuck). Each TASK_SCORED carries the trial's
reward + esRunId + cost + input/output tokens (from `agent_result` in result.json), plus a
best-effort outcome/reason so the dashboard distinguishes verifier failures from infra failures.
While a trial is active, the bridge also rate-limits task-scoped `RUN_PROGRESS` heartbeats so the
dashboard can distinguish a long-running attempt from a dead telemetry publisher.

Auth: BR_TOKEN, or BR_ADMIN_EMAIL + BR_ADMIN_PW. Base: BR_ES_BASE.
"""
import argparse
import glob
import json
import os
import re
import sys
import time
import urllib.error
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


def _authed_req(base, path, auth, method="GET", body=None, timeout=30):
    """Issue one request and refresh an expired login token exactly once.

    Long benchmark campaigns outlive the one-hour access token. A bridge must not silently stop
    projecting progress merely because its telemetry credential expired. Static BR_TOKEN-only
    deployments remain fail-closed because they have no credential with which to refresh.
    """
    try:
        return _req(base, path, auth.get("token"), method, body, timeout)
    except urllib.error.HTTPError as exc:
        if exc.code != 401 or not auth.get("email") or not auth.get("password"):
            raise
        refreshed = _login(base, auth["email"], auth["password"])
        if not refreshed:
            raise
        auth["token"] = refreshed
        print("bridge: access token refreshed after HTTP 401", flush=True)
        return _req(base, path, refreshed, method, body, timeout)


def _first_text(*vals):
    for v in vals:
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None


def _classify_trial(result, reward, exception_text=None):
    """Return (outcome, reason) independent of reward math."""
    # Harbor writes AgentTimeoutError (and some cancellation/setup traces) to a
    # sibling exception.txt while leaving result.json with null agent/verifier
    # fields.  Inspect that file before trusting reward: a timed-out trial can
    # otherwise carry a misleading verifier reward=1 and be posted as PASSED.
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
        exception_text,
    )
    hay = " ".join(x for x in (status, reason, exception_text) if x).lower()
    if "timeout" in hay or "timed out" in hay or "cancellederror" in hay:
        return "TIMEOUT", reason or status or "Harbor trial timed out or was cancelled"
    if reward > 0:
        return "PASSED", None
    setup_failure = _setup_failure_reason(result, exception_text)
    if setup_failure:
        return "INFRA_SETUP_FAILED", setup_failure
    if any(w in hay for w in ("container", "docker", "harbor", "bridge", "tool")):
        return "INFRA_FAILED", reason or status
    if any(w in hay for w in ("verifier", "verify", "reward")):
        return "VERIFIER_FAILED", reason or status
    if any(w in hay for w in ("agent", "worker", "llm", "model")):
        return "AGENT_FAILED", reason or status
    return "FAILED", reason or status


def _setup_failure_reason(result, exception_text=None):
    """Detect Harbor failures before the ExecuteSpec agent/verifier ran."""
    if result.get("agent_result") is not None or result.get("verifier_result") is not None:
        return None
    exc = (
        result.get("exception_info")
        or result.get("exception")
        or result.get("error")
        or exception_text
    )
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
        exception_file = os.path.join(os.path.dirname(f), "exception.txt")
        exception_text = None
        try:
            with open(exception_file, encoding="utf-8") as exception_handle:
                exception_text = exception_handle.read(12000)
        except OSError:
            pass
        outcome, reason = _classify_trial(d, rew, exception_text)
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


def _scan_active(job_dir):
    """Return Harbor trial directories which started but have no result yet."""
    out = {}
    try:
        entries = os.scandir(job_dir)
    except OSError:
        return out
    with entries:
        for entry in entries:
            if not entry.is_dir() or not re.search(r"__[A-Za-z0-9]+$", entry.name):
                continue
            if os.path.exists(os.path.join(entry.path, "result.json")):
                continue
            task = re.sub(r"__[A-Za-z0-9]+$", "", entry.name)
            if task:
                out[entry.name] = {"task": task}
    return out


def _harbor_job_finished(job_dir):
    """Return true only when Harbor's job-level result declares a terminal timestamp."""
    try:
        with open(os.path.join(job_dir, "result.json"), encoding="utf-8") as handle:
            return bool(json.load(handle).get("finished_at"))
    except (OSError, ValueError, TypeError):
        return False


def _existing_attempts(base, auth, rid):
    """Return task -> recorded attempt count for an existing benchmark_run."""
    try:
        detail = _authed_req(base, f"/api/v2/test/benchmark/{rid}", auth, "GET")
    except Exception as e:  # noqa: BLE001
        print(f"bridge: existing run lookup failed for {rid}: {e}", flush=True)
        return {}
    attempts = {}
    for row in detail.get("tasks") or []:
        task = row.get("taskName")
        att = row.get("attempt") or 1
        if task:
            attempts[task] = max(attempts.get(task, 0), int(att))
    return attempts


def _heartbeat_active_attempts(
    base,
    auth,
    rid,
    active_trials,
    started,
    posted,
    attempt_by_trial,
    last_heartbeat_by_trial,
    now,
    heartbeat_seconds,
):
    """Refresh already-announced active attempts without allocating new attempts."""
    sent = 0
    for trial, info in sorted(active_trials.items()):
        if trial not in started or trial in posted:
            continue
        attempt = attempt_by_trial.get(trial)
        if attempt is None:
            continue
        last = last_heartbeat_by_trial.get(trial, 0)
        if now - last < heartbeat_seconds:
            continue
        try:
            _authed_req(
                base,
                f"/api/v2/test/benchmark/{rid}/event",
                auth,
                "POST",
                {
                    "type": "RUN_PROGRESS",
                    "taskName": info["task"],
                    "attempt": attempt,
                    "message": "Harbor trial still active",
                },
            )
            last_heartbeat_by_trial[trial] = now
            sent += 1
        except Exception as e:  # noqa: BLE001
            # Do not consume the rate-limit window on failure; retry on the next bridge poll.
            print("bridge: RUN_PROGRESS heartbeat failed", trial, e, flush=True)
    return sent


def _fail_run(base, auth, rid, message, fail_marker=None, payload=None):
    body = {"type": "RUN_FAILED", "message": message}
    if payload is not None:
        body["payload"] = json.dumps(payload)
    _authed_req(base, f"/api/v2/test/benchmark/{rid}/event", auth, "POST", body)
    if fail_marker:
        with open(fail_marker, "w", encoding="utf-8") as fh:
            fh.write(message + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--job-dir", required=True)
    ap.add_argument("--suite", default="TERMINAL_BENCH")
    ap.add_argument("--dataset", default="terminal-bench-2-1")
    ap.add_argument("--provider", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--run-id", help="Existing ExecuteSpec benchmark_run id to resume")
    ap.add_argument(
        "--resume-existing",
        action="store_true",
        help="When --run-id is set, treat already-recorded attempts as posted",
    )
    ap.add_argument("--k", type=int, default=1)
    ap.add_argument("--total", type=int, required=True)  # number of TASKS (not trials)
    ap.add_argument("--poll", type=int, default=15)
    ap.add_argument("--heartbeat-seconds", type=int, default=60)
    ap.add_argument("--max-min", type=int, default=600)
    ap.add_argument("--idle-min", type=int, default=25)  # finalize if no new trial for this long
    ap.add_argument("--fail-marker")
    a = ap.parse_args()
    if a.heartbeat_seconds < 1:
        ap.error("--heartbeat-seconds must be positive")

    base = os.environ["BR_ES_BASE"]
    email = os.environ.get("BR_ADMIN_EMAIL")
    password = os.environ.get("BR_ADMIN_PW")
    token = os.environ.get("BR_TOKEN") or _login(base, email, password)
    if not token:
        print("bridge: login failed")
        return 1
    auth = {"token": token, "email": email, "password": password}

    if a.run_id:
        rid = a.run_id
        print("bridge: resuming benchmark_run id =", rid, flush=True)
    else:
        run = _authed_req(
            base,
            "/api/v2/test/benchmark/start",
            auth,
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
    started = set()  # trial dirs already announced to the dashboard
    attempt_by_trial = {}  # preserve the same attempt number for start -> score
    last_heartbeat_by_trial = {}  # trial -> last successful RUN_PROGRESS timestamp
    attempts = {}  # task -> attempt counter
    if a.run_id and a.resume_existing:
        attempts = _existing_attempts(base, auth, rid)
        existing_by_task = dict(attempts)
        for trial, info in sorted(_scan(a.job_dir).items()):
            seen = existing_by_task.get(info["task"], 0)
            if seen <= 0:
                continue
            posted.add(trial)
            existing_by_task[info["task"]] = seen - 1
        print(
            f"bridge: resume-existing marked {len(posted)} existing trials posted",
            flush=True,
        )
    deadline = time.time() + a.max_min * 60
    last_change = time.time()
    idle_limit = a.idle_min * 60
    while time.time() < deadline:
        # Harbor creates a trial directory before result.json. Announce live workers
        # so the dashboard's active-attempt projection reflects actual execution.
        active_trials = _scan_active(a.job_dir)
        for trial, info in sorted(active_trials.items()):
            if trial in started or trial in posted:
                continue
            task = info["task"]
            attempt = attempts.get(task, 0) + 1
            try:
                _authed_req(
                    base,
                    f"/api/v2/test/benchmark/{rid}/event",
                    auth,
                    "POST",
                    {"type": "TASK_STARTED", "taskName": task, "attempt": attempt},
                )
                started.add(trial)
                attempt_by_trial[trial] = attempt
                attempts[task] = attempt
                last_change = time.time()
                last_heartbeat_by_trial[trial] = last_change
                print(f"bridge: started {task} att={attempt}", flush=True)
            except Exception as e:  # noqa: BLE001
                print("bridge: TASK_STARTED post failed", trial, e, flush=True)
        _heartbeat_active_attempts(
            base,
            auth,
            rid,
            active_trials,
            started,
            posted,
            attempt_by_trial,
            last_heartbeat_by_trial,
            time.time(),
            a.heartbeat_seconds,
        )
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
                    auth,
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
            attempt = attempt_by_trial.get(trial)
            if attempt is None:
                attempt = attempts.get(task, 0) + 1
            body = {
                "type": "TASK_SCORED",
                "taskName": task,
                "attempt": attempt,
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
                _authed_req(base, f"/api/v2/test/benchmark/{rid}/event", auth, "POST", body)
                posted.add(trial)
                last_heartbeat_by_trial.pop(trial, None)
                attempt_by_trial[trial] = attempt
                attempts[task] = max(attempts.get(task, 0), attempt)
                last_change = time.time()
                resolved = len(
                    {trials[t]["task"] for t in posted if trials[t]["reward"] > 0}
                )
                print(
                    f"bridge: {task} att={attempt} reward={info['reward']} "
                    f"in={info['in_tok']} out={info['out_tok']}  "
                    f"({len(posted)}/{expected_trials} trials, {resolved} tasks resolved)",
                    flush=True,
                )
            except Exception as e:  # noqa: BLE001
                print("bridge: post failed", trial, e, flush=True)
        if len(posted) >= expected_trials:
            break
        if posted and _harbor_job_finished(a.job_dir) and (time.time() - last_change) > idle_limit:
            print(f"bridge: no new trial for {a.idle_min}m — finalizing", flush=True)
            break
        time.sleep(a.poll)

    final = _scan(a.job_dir)
    resolved = len({final[t]["task"] for t in posted if t in final and final[t]["reward"] > 0})
    complete = len(posted) >= expected_trials
    try:
        if complete:
            _authed_req(
                base,
                f"/api/v2/test/benchmark/{rid}/event",
                auth,
                "POST",
                {"type": "RUN_DONE"},
            )
        else:
            message = (
                f"INCOMPLETE BENCHMARK RUN: posted {len(posted)}/{expected_trials} trials "
                f"before the bridge deadline"
            )
            _fail_run(base, auth, rid, message, a.fail_marker)
            print(f"bridge: {message}", flush=True)
    except Exception as e:  # noqa: BLE001
        print("bridge: terminal status post failed", e, flush=True)
    print(
        f"bridge: done — posted {len(posted)}/{expected_trials} trials, "
        f"{resolved} tasks resolved. run={rid}",
        flush=True,
    )
    return 0 if complete else 2


if __name__ == "__main__":
    sys.exit(main())
