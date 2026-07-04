#!/usr/bin/env python3
"""SWE-bench Pro sample-20 driver — runs the blind generate endpoint per instance.

Usage:
  python3 run-swe-pro-sample20.py --provider deepseek --model deepseek-v4-pro-native [--limit N] [--start I]

Reads admin creds from ~/swebench-bench/.espec-admin.env (either naming scheme).
Writes manifest JSONL to ~/swebench-bench/swe-pro-sample20-<model>.manifest.jsonl
Serial by design; run one model lane at a time (server caps concurrent runs anyway).
"""
import argparse, json, os, pathlib, sys, time, urllib.request, urllib.error

BASE = os.environ.get("ES_BASE", "http://localhost:8080")
HOME = pathlib.Path.home()
SAMPLE = os.environ.get("SWE_SAMPLE_FILE", "/var/swebench/swebench_pro_sample20.json")


def load_env():
    envf = HOME / "swebench-bench" / ".espec-admin.env"
    env = {}
    for line in envf.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")
    email = env.get("ES_ADMIN_EMAIL") or env.get("ESPEC_ADMIN_EMAIL")
    pw = env.get("ES_ADMIN_PW") or env.get("ESPEC_ADMIN_PW")
    if not email or not pw:
        sys.exit("admin creds not found in .espec-admin.env")
    return email, pw


def post(path, body, token=None, timeout=3900):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json",
                 **({"Authorization": "Bearer " + token} if token else {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode() or "{}")


def login():
    email, pw = load_env()
    st, d = post("/api/auth/login", {"email": email, "password": pw}, timeout=60)
    tok = d.get("accessToken") or d.get("access_token") or (d.get("data") or {}).get("accessToken")
    if not tok:
        sys.exit(f"login failed: {st} {str(d)[:200]}")
    return tok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--limit", type=int, default=20)
    ap.add_argument("--start", type=int, default=0)
    a = ap.parse_args()

    instances = [r["instance_id"] for r in json.load(open(SAMPLE))]
    instances = instances[a.start:a.start + a.limit]
    mf = HOME / "swebench-bench" / f"swe-pro-sample20-{a.model}.manifest.jsonl"
    done = set()
    if mf.exists():
        for line in mf.read_text().splitlines():
            try:
                done.add(json.loads(line)["instance"])
            except Exception:
                pass

    token = login()
    print(f"[driver] {a.provider}:{a.model} instances={len(instances)} already_done={len(done & set(instances))}", flush=True)
    for i, inst in enumerate(instances):
        if inst in done:
            print(f"[{i+1}/{len(instances)}] SKIP (in manifest) {inst}", flush=True)
            continue
        t0 = time.time()
        rec = {"provider": a.provider, "model": a.model, "instance": inst}
        try:
            st, d = post("/api/admin/benchmark/generate",
                         {"instanceId": inst, "aiProvider": a.provider, "modelName": a.model},
                         token=token)
            rec.update(http=st, runId=d.get("runId"), outcome=d.get("outcome"),
                       patchBytes=d.get("patchBytes"), costUsd=d.get("costUsd"),
                       outputDir=d.get("outputDir") or d.get("outputPath"))
        except urllib.error.HTTPError as e:
            body = e.read().decode()[:300]
            rec.update(http=e.code, error=body)
            if e.code in (401, 403):
                token = login()
                rec["note"] = "token refreshed; instance NOT retried, rerun driver"
        except Exception as e:
            rec.update(error=f"{type(e).__name__}: {e}"[:300])
        rec["durationSec"] = int(time.time() - t0)
        with open(mf, "a") as f:
            f.write(json.dumps(rec) + "\n")
        print(f"[{i+1}/{len(instances)}] {inst} -> outcome={rec.get('outcome')} patchBytes={rec.get('patchBytes')} ${rec.get('costUsd')} {rec['durationSec']}s err={rec.get('error','')[:80]}", flush=True)
    print("[driver] done. manifest:", mf, flush=True)


if __name__ == "__main__":
    main()
