#!/usr/bin/env python3
"""Bridge a local/VM Harbor Terminal-Bench job dir → the ExecuteSpec benchmark dashboard.

Runs ALONGSIDE a `harbor run`: POSTs a benchmark_run `start`, polls the job dir for each
trial's `result.json` (written when a trial finishes), POSTs one `TASK_SCORED` per *trial*
(= per attempt — so k>1 aggregates correctly), and `RUN_DONE` when all trials are in or Harbor's
root `result.json` exists. Each TASK_SCORED carries the trial's
reward + esRunId + cost + input/output tokens (from `agent_result` in result.json), so the
dashboard shows resolved% + cost + aggregate tokens live.

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
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            t = resp.read().decode()
            return json.loads(t) if t.strip() else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        print(f"bridge: HTTP {e.code} {method} {path}: {body[:500]}", flush=True)
        raise


def _login(base, email, pw):
    d = _req(base, "/api/auth/login", None, "POST", {"email": email, "password": pw})
    return d.get("accessToken") or d.get("token")


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
            # Binarize: ANY positive reward counts as a pass. int(float(x)) would TRUNCATE a
            # fractional reward (0.5 -> 0) and undercount resolved tasks on the dashboard.
            rew = 1 if float(rew) > 0 else 0
        except Exception:  # noqa: BLE001
            rew = 0
        meta = ar.get("metadata") or {}
        out[trial] = {
            "task": task,
            "reward": rew,
            "esRunId": meta.get("es_run_id"),
            "cost": ar.get("cost_usd"),
            "in_tok": ar.get("n_input_tokens"),
            "out_tok": ar.get("n_output_tokens"),
        }
    return out


def _root_result_exists(job_dir):
    return os.path.exists(os.path.join(job_dir, "result.json"))


def _harbor_pid(job_dir):
    pids = os.path.join(
        os.path.dirname(os.path.abspath(job_dir)),
        os.path.basename(os.path.abspath(job_dir)) + "-pids.txt",
    )
    if not os.path.exists(pids):
        return None
    try:
        raw = open(pids, errors="replace").read()
    except Exception:  # noqa: BLE001
        return None
    m = re.search(r"\bharbor_pid=([0-9]+)\b", raw)
    if not m:
        return None
    return int(m.group(1))


def _pid_alive(pid):
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _harbor_alive(job_dir):
    return _pid_alive(_harbor_pid(job_dir))


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
    ap.add_argument(
        "--idle-min",
        type=int,
        default=25,
        help="warn after this many idle minutes; RUN_DONE waits for root result.json or max-min",
    )
    ap.add_argument("--run-id", default=None, help="attach to an existing benchmark_run (recovery) instead of starting a new one")
    a = ap.parse_args()

    base = os.environ["BR_ES_BASE"]
    _email = os.environ.get("BR_ADMIN_EMAIL")
    _pw = os.environ.get("BR_ADMIN_PW")
    token = os.environ.get("BR_TOKEN") or _login(base, _email, _pw)
    _last_login = time.time()
    if not token:
        print("bridge: login failed")
        return 1

    if a.run_id:
        rid = a.run_id
    else:
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
                "harborJobName": os.path.basename(os.path.abspath(a.job_dir)),
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
        # Refresh the JWT before its ~1h TTL expires — the bridge runs for hours,
        # and a stale token 401s every post (which previously froze the dashboard).
        if _email and _pw and time.time() - _last_login > 1800:
            try:
                token = _login(base, _email, _pw)
                _last_login = time.time()
            except Exception:  # noqa: BLE001
                pass
        trials = _scan(a.job_dir)
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
            root_exists = _root_result_exists(a.job_dir)
            harbor_alive = _harbor_alive(a.job_dir)
            if root_exists and not harbor_alive:
                print(
                    f"bridge: root result exists and Harbor is stopped after "
                    f"{a.idle_min}m idle — finalizing",
                    flush=True,
                )
                break
            if root_exists and harbor_alive:
                print(
                    f"bridge: root result exists after {a.idle_min}m idle, but Harbor "
                    "is still alive; continuing so RUN_DONE does not race a long trial",
                    flush=True,
                )
                last_change = time.time()
                time.sleep(a.poll)
                continue
            print(
                f"bridge: no new trial for {a.idle_min}m, but root result is absent; "
                "continuing so RUN_DONE does not race Harbor",
                flush=True,
            )
            last_change = time.time()
        time.sleep(a.poll)

    final = _scan(a.job_dir)
    for trial, info in sorted(final.items()):
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
        try:
            _req(base, f"/api/v2/test/benchmark/{rid}/event", token, "POST", body)
            posted.add(trial)
            print(
                f"bridge: final-post {task} att={attempts[task]} reward={info['reward']} "
                f"in={info['in_tok']} out={info['out_tok']}",
                flush=True,
            )
        except Exception as e:  # noqa: BLE001
            print("bridge: final post failed", trial, e, flush=True)
    resolved = len({final[t]["task"] for t in posted if t in final and final[t]["reward"] > 0})
    if len(posted) < expected_trials and _harbor_alive(a.job_dir):
        print(
            f"bridge: max wait reached with Harbor still alive and only "
            f"{len(posted)}/{expected_trials} trials; NOT posting RUN_DONE",
            flush=True,
        )
        return 2
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
