#!/usr/bin/env python3
"""Pipelined, bounded official SWE-bench scorer.

The generation manifest is tailed and each completed patch is scored in its own
output directory.  Scoring is intentionally bounded: each worker owns one
cleanroom Docker container, so increasing this beyond the VM's memory/disk
budget can make the benchmark less reliable rather than faster.

Usage:
  SCORE_WORKERS=2 python3 score-watcher.py <manifest.jsonl> <out-dir>

The state file is append-only and idempotent across restarts. Rewards are posted
only after the official scorer returns a result; scorer failures emit progress
events and never become false zero rewards.
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import pathlib
import subprocess
import sys
import threading
import time
import urllib.request

HOME = pathlib.Path.home()
BENCH = HOME / "swebench-bench"
HARNESS = BENCH / "SWE-bench_Pro-os"
PY = str(HARNESS / ".venv" / "bin" / "python")
RAW = os.environ.get("SWE_RAW_SAMPLE", str(BENCH / "pro" / "swe_bench_pro_full.jsonl"))
BASE = os.environ.get("ES_BASE", "http://localhost:8080").rstrip("/")
EXPECTED_TOTAL = int(os.environ.get("EXPECTED_TOTAL", "20"))
SCORE_WORKERS = max(1, int(os.environ.get("SCORE_WORKERS", "2")))
POLL_SECONDS = max(5, int(os.environ.get("POLL_SECONDS", "30")))
SCORE_TIMEOUT_SECONDS = max(60, int(os.environ.get("SCORE_TIMEOUT_SECONDS", "3600")))
state_lock = threading.Lock()


def login() -> str:
    override_email = os.environ.get("ES_ADMIN_EMAIL")
    override_password = os.environ.get("ES_ADMIN_PW")
    if override_email and override_password:
        email, password = override_email, override_password
    else:
        env: dict[str, str] = {}
        for line in (BENCH / ".espec-admin.env").read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('\"').strip("'")
        email, password = env["ES_ADMIN_EMAIL"], env["ES_ADMIN_PW"]
    req = urllib.request.Request(
        BASE + "/api/auth/login",
        data=json.dumps({"email": email, "password": password}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)["accessToken"]


def api(token: str, method: str, path: str, body: dict | None = None) -> dict:
    request = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method=method,
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def find_bench_run(token: str) -> str | None:
    match = os.environ.get("DATASET_MATCH", "pass1").split()
    try:
        runs = api(token, "GET", "/api/v2/test/benchmark")
        runs = runs.get("content") or runs.get("runs") or runs if isinstance(runs, dict) else runs
        for run in runs:
            if all(part in (run.get("dataset") or "") for part in match):
                return run.get("id")
    except Exception as exc:
        print(f"[watcher] benchmark lookup failed: {exc}", flush=True)
    return None


def score_one(row: dict, out_dir: pathlib.Path) -> tuple[dict | None, str | None]:
    instance_id = row["instance_id"]
    patch_file = row.get("proPatchJson") or row.get("submissionPatch")
    if not patch_file or not os.path.isfile(patch_file):
        return None, "no patch artifact"
    instance_out = out_dir / f"score-{instance_id[:60]}"
    instance_out.mkdir(parents=True, exist_ok=True)
    command = [
        PY,
        str(HARNESS / "swe_bench_pro_eval.py"),
        "--raw_sample_path", RAW,
        "--patch_path", patch_file,
        "--output_dir", str(instance_out),
        "--dockerhub_username", "jefzda",
        "--scripts_dir", str(HARNESS / "run_scripts"),
        "--use_local_docker",
        "--docker_platform", "linux/amd64",
        "--num_workers", "1",
    ]
    started = time.time()
    try:
        proc = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=SCORE_TIMEOUT_SECONDS,
            cwd=str(HARNESS),
        )
    except subprocess.TimeoutExpired:
        return None, f"scoring timeout ({SCORE_TIMEOUT_SECONDS}s)"
    result_file = instance_out / "eval_results.json"
    if not result_file.is_file():
        tail = ((proc.stdout or "") + (proc.stderr or ""))[-600:]
        return None, f"no eval_results (exit {proc.returncode}): {tail}"
    results = json.loads(result_file.read_text())
    return {"resolved": bool(results.get(instance_id, False)), "seconds": round(time.time() - started, 1)}, None


def load_rows(manifest: pathlib.Path) -> list[dict]:
    rows: list[dict] = []
    if not manifest.exists():
        return rows
    for line in manifest.read_text().splitlines():
        try:
            if line.strip():
                rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def append_state(path: pathlib.Path, entry: dict) -> None:
    with state_lock:
        with path.open("a") as stream:
            stream.write(json.dumps(entry, sort_keys=True) + "\n")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: score-watcher.py <manifest.jsonl> <out-dir>")
    manifest = pathlib.Path(sys.argv[1]).expanduser()
    out_dir = pathlib.Path(sys.argv[2]).expanduser()
    out_dir.mkdir(parents=True, exist_ok=True)
    scored_path = out_dir / "scored.jsonl"
    scored: dict[str, dict] = {}
    if scored_path.exists():
        for line in scored_path.read_text().splitlines():
            try:
                entry = json.loads(line)
                scored[entry["instance_id"]] = entry
            except (json.JSONDecodeError, KeyError):
                continue
    token = login()
    benchmark_id = find_bench_run(token)
    print(f"[watcher] benchmark={benchmark_id} workers={SCORE_WORKERS} already_scored={len(scored)}", flush=True)

    while True:
        rows = load_rows(manifest)
        pending = [
            row for row in rows
            if row.get("instance_id") not in scored and row.get("status", 200) == 200
        ]
        if pending:
            # A task is inserted into scored before its event is posted, so a retry
            # cannot launch a second Docker scorer for the same instance.
            with concurrent.futures.ThreadPoolExecutor(max_workers=SCORE_WORKERS) as pool:
                futures = {pool.submit(score_one, row, out_dir): row for row in pending}
                for future in concurrent.futures.as_completed(futures):
                    row = futures[future]
                    instance_id = row["instance_id"]
                    try:
                        result, error = future.result()
                    except Exception as exc:  # isolate one scorer failure
                        result, error = None, f"{type(exc).__name__}: {exc}"
                    entry = {"instance_id": instance_id, "esRunId": row.get("runId")}
                    if result:
                        entry.update(result)
                        print(f"[watcher] {instance_id}: {'RESOLVED' if result['resolved'] else 'unresolved'} in {result['seconds']}s", flush=True)
                    else:
                        entry["error"] = error
                        print(f"[watcher] {instance_id}: SCORE-ERROR {error[:200]}", flush=True)
                    scored[instance_id] = entry
                    append_state(scored_path, entry)
                    try:
                        if result and benchmark_id:
                            api(token, "POST", f"/api/v2/test/benchmark/{benchmark_id}/event", {
                                "type": "TASK_SCORED", "taskName": instance_id, "attempt": 1,
                                "reward": 1 if result["resolved"] else 0, "esRunId": row.get("runId"),
                                "message": f"official scorer: {'RESOLVED' if result['resolved'] else 'unresolved'} ({result['seconds']}s)",
                            })
                        elif benchmark_id:
                            api(token, "POST", f"/api/v2/test/benchmark/{benchmark_id}/event", {
                                "type": "RUN_PROGRESS", "taskName": instance_id,
                                "message": f"SCORING ERROR (no reward posted): {error[:300]}",
                            })
                    except Exception as exc:
                        print(f"[watcher] tracking post failed: {exc}", flush=True)
        resolved = sum(1 for entry in scored.values() if entry.get("resolved"))
        errors = sum(1 for entry in scored.values() if entry.get("error"))
        print(f"[watcher] scored {len(scored)}/{EXPECTED_TOTAL} | RESOLVED {resolved} | errors {errors}", flush=True)
        if len(scored) >= EXPECTED_TOTAL:
            if benchmark_id:
                api(token, "POST", f"/api/v2/test/benchmark/{benchmark_id}/event", {
                    "type": "RUN_DONE", "message": f"official scoring complete: {resolved}/{EXPECTED_TOTAL} resolved ({errors} scoring errors)",
                })
            return
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
