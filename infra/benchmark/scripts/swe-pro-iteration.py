#!/usr/bin/env python3
"""Run one SWE-bench Pro iteration: generate -> score -> publish.

Designed for the benchmark VM. It deliberately keeps generation, official scoring, and in-app
tracking tied to the same deterministic instance set so reliability math cannot drift from the
verifier denominator.

Example:
  python3 swe-pro-iteration.py \
    --provider deepseek --model deepseek-v4-flash-native \
    --tag iter1-flash-s100 --limit 100 --seed 42 \
    --concurrency 8 --score-workers 8
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import random
import subprocess
import threading
import time
import urllib.error
import urllib.request


BASE = os.environ.get("ES_BASE", "http://localhost:8080").rstrip("/")
BENCH_DIR = Path(os.environ.get("BENCH_DIR", str(Path.home() / "swebench-bench")))
DEFAULT_INSTANCES = "/var/swebench/swebench_pro_full.json"
DEFAULT_RAW = str(BENCH_DIR / "swe_bench_pro_full.jsonl")
DEFAULT_HARNESS = str(BENCH_DIR / "SWE-bench_Pro-os")
LOCK = threading.Lock()


def load_env() -> tuple[str, str]:
    env_file = BENCH_DIR / ".espec-admin.env"
    env: dict[str, str] = {}
    for line in env_file.read_text().splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    email = env.get("ES_ADMIN_EMAIL") or env.get("ESPEC_ADMIN_EMAIL")
    pw = env.get("ES_ADMIN_PW") or env.get("ESPEC_ADMIN_PW")
    if not email or not pw:
        raise SystemExit(f"admin creds not found in {env_file}")
    return email, pw


def call(path: str, body: dict, token: str | None = None, timeout: int = 60) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            **({"Authorization": "Bearer " + token} if token else {}),
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")


def login() -> str:
    email, pw = load_env()
    token = call("/api/auth/login", {"email": email, "password": pw}).get("accessToken")
    if not token:
        raise SystemExit("admin login did not return accessToken")
    return token


def load_instances(path: str, limit: int, seed: int, set_file: str | None) -> list[str]:
    if set_file:
        return [line.strip() for line in Path(set_file).read_text().splitlines() if line.strip()]
    rows = json.load(open(path))
    ids = [r["instance_id"] for r in rows]
    rng = random.Random(seed)
    rng.shuffle(ids)
    return ids[:limit]


def read_manifest(path: Path) -> dict[str, dict]:
    rows: dict[str, dict] = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                rows[row["instance"]] = row
            except Exception:
                continue
    return rows


def generate_one(instance: str, provider: str, model: str, token: str) -> dict:
    started = time.time()
    row = {"provider": provider, "model": model, "instance": instance}
    try:
        data = call(
            "/api/admin/benchmark/generate",
            {"instanceId": instance, "aiProvider": provider, "modelName": model},
            token=token,
            timeout=7200,
        )
        row.update(
            http=200,
            runId=data.get("runId"),
            outcome=data.get("outcome"),
            iterations=data.get("iterations"),
            toolCalls=data.get("toolCalls"),
            patchBytes=data.get("patchBytes"),
            costUsd=data.get("costUsd"),
            submissionPatch=data.get("submissionPatch"),
            proPatchJson=data.get("proPatchJson"),
            predsJson=data.get("predsJson"),
        )
    except urllib.error.HTTPError as e:
        row.update(http=e.code, error=e.read().decode(errors="ignore")[:500])
    except Exception as e:
        row.update(error=f"{type(e).__name__}: {e}"[:500])
    row["durationSec"] = int(time.time() - started)
    return row


def generate_all(instances: list[str], provider: str, model: str, tag: str, concurrency: int) -> Path:
    manifest = BENCH_DIR / f"{tag}-{model}.manifest.jsonl"
    done = read_manifest(manifest)
    todo = [i for i in instances if i not in done]
    token = login()
    print(f"[generate] model={model} selected={len(instances)} done={len(done)} todo={len(todo)} concurrency={concurrency}", flush=True)

    def write_row(row: dict) -> None:
        with LOCK:
            with manifest.open("a") as f:
                f.write(json.dumps(row, sort_keys=True) + "\n")
        print(
            f"  {row['instance'][:58]} -> {row.get('outcome')} "
            f"patch={row.get('patchBytes')} iters={row.get('iterations')} "
            f"tools={row.get('toolCalls')} ${row.get('costUsd')} "
            f"{row.get('durationSec')}s {str(row.get('error', ''))[:80]}",
            flush=True,
        )

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(generate_one, inst, provider, model, token) for inst in todo]
        for fut in as_completed(futures):
            write_row(fut.result())
    return manifest


def build_patches(manifest: Path, instances: list[str], model: str, tag: str) -> tuple[Path, set[str]]:
    by_instance = read_manifest(manifest)
    patches = []
    included: set[str] = set()
    for inst in instances:
        row = by_instance.get(inst)
        if not row:
            continue
        patch_path = row.get("proPatchJson")
        if not patch_path or not Path(patch_path).exists():
            continue
        arr = json.load(open(patch_path))
        entry = arr[0] if isinstance(arr, list) and arr else None
        if not entry:
            continue
        entry = dict(entry)
        entry["instance_id"] = inst
        entry["prefix"] = model
        patches.append(entry)
        included.add(inst)
    out = BENCH_DIR / f"patches-{tag}-{model}.json"
    json.dump(patches, open(out, "w"))
    print(f"[patches] {out} entries={len(patches)} selected={len(instances)}")
    return out, included


def score(patches: Path, tag: str, model: str, raw_sample_path: str, harness_dir: str, workers: int) -> Path:
    out_dir = BENCH_DIR / "eval-out" / f"{tag}-{model}"
    if out_dir.exists():
        subprocess.run(["rm", "-rf", str(out_dir)], check=True)
    cmd = [
        "sudo",
        str(Path(harness_dir) / ".venv/bin/python"),
        "swe_bench_pro_eval.py",
        "--raw_sample_path",
        raw_sample_path,
        "--patch_path",
        str(patches),
        "--output_dir",
        str(out_dir),
        "--scripts_dir",
        "run_scripts",
        "--num_workers",
        str(workers),
        "--dockerhub_username",
        "jefzda",
        "--use_local_docker",
    ]
    log = BENCH_DIR / f"eval-{tag}-{model}.log"
    print(f"[score] workers={workers} log={log}")
    with log.open("w") as f:
        subprocess.run(cmd, cwd=harness_dir, stdout=f, stderr=subprocess.STDOUT, check=True)
    result = out_dir / "eval_results.json"
    if not result.exists():
        raise SystemExit(f"missing eval result: {result}")
    return result


def publish(
    instances: list[str],
    manifest: Path,
    result_file: Path,
    patch_instances: set[str],
    provider: str,
    model: str,
    tag: str,
) -> str:
    token = login()
    by_instance = read_manifest(manifest)
    results = json.load(open(result_file))
    run = call(
        "/api/v2/test/benchmark/start",
        {
            "suite": "SWE_BENCH",
            "dataset": tag,
            "provider": provider,
            "model": model,
            "k": 1,
            "totalTasks": len(instances),
            "harborJobName": f"{tag}-{model}",
        },
        token,
    )
    run_id = run["id"]
    for inst in instances:
        row = by_instance.get(inst, {})
        scored = inst in results
        reward = 1 if results.get(inst) is True else 0
        if not row:
            outcome, reason = "NOT_GENERATED", "missing manifest row"
        elif inst not in patch_instances:
            outcome, reason = "NO_PATCH", row.get("outcome") or row.get("error")
        elif not scored:
            outcome, reason = "NOT_SCORED", row.get("outcome")
        else:
            outcome, reason = ("RESOLVED" if reward else "UNRESOLVED"), row.get("outcome")
        call(
            f"/api/v2/test/benchmark/{run_id}/event",
            {
                "type": "TASK_SCORED",
                "taskName": inst,
                "attempt": 1,
                "reward": reward,
                "esRunId": row.get("runId"),
                "costUsd": row.get("costUsd"),
                "outcome": outcome,
                "reason": reason,
            },
            token,
        )
    call(
        f"/api/v2/test/benchmark/{run_id}/event",
        {"type": "RUN_DONE", "message": f"{tag} scored"},
        token,
    )
    print(f"[publish] {model} -> {run_id} resolved={sum(1 for v in results.values() if v)}/{len(instances)}")
    return run_id


def write_summary(tag: str, model: str, instances: list[str], manifest: Path, result_file: Path, run_id: str) -> None:
    rows = read_manifest(manifest)
    results = json.load(open(result_file))
    outcomes: dict[str, int] = {}
    for row in rows.values():
        key = row.get("outcome") or ("HTTP_" + str(row.get("http")) if row.get("http") else "ERROR")
        outcomes[key] = outcomes.get(key, 0) + 1
    summary = {
        "tag": tag,
        "model": model,
        "selected": len(instances),
        "manifestRows": len(rows),
        "scoredRows": len(results),
        "resolved": sum(1 for v in results.values() if v),
        "outcomes": outcomes,
        "benchmarkRunId": run_id,
        "manifest": str(manifest),
        "evalResults": str(result_file),
    }
    out = BENCH_DIR / f"summary-{tag}-{model}.json"
    json.dump(summary, open(out, "w"), indent=2, sort_keys=True)
    print(f"[summary] {out}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--limit", type=int, default=100)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--set-file")
    ap.add_argument("--instances-file", default=DEFAULT_INSTANCES)
    ap.add_argument("--raw-sample-path", default=DEFAULT_RAW)
    ap.add_argument("--harness-dir", default=DEFAULT_HARNESS)
    ap.add_argument("--concurrency", type=int, default=8)
    ap.add_argument("--score-workers", type=int, default=8)
    args = ap.parse_args()

    instances = load_instances(args.instances_file, args.limit, args.seed, args.set_file)
    set_path = BENCH_DIR / f"{args.tag}.instances.txt"
    set_path.write_text("\n".join(instances) + "\n")
    print(f"[set] {set_path} count={len(instances)}")

    manifest = generate_all(instances, args.provider, args.model, args.tag, args.concurrency)
    patches, patch_instances = build_patches(manifest, instances, args.model, args.tag)
    result = score(patches, args.tag, args.model, args.raw_sample_path, args.harness_dir, args.score_workers)
    run_id = publish(instances, manifest, result, patch_instances, args.provider, args.model, args.tag)
    write_summary(args.tag, args.model, instances, manifest, result, run_id)


if __name__ == "__main__":
    main()
