#!/usr/bin/env python3
"""Phase-0 instrument: k-pass eval with error bars + canary derivation.

The standing rule (HARNESS-RESOLUTION-LOG §operating principles): no harness/prompt/flag
change ships or reverts without k>=3 evidence on a fixed slice, reported as mean +/- spread.
This is the thin wrapper that makes that cheap.

Subcommands
-----------
generate   Run k generation passes over a dataset slice via POST /api/admin/benchmark/generate.
           Each pass writes its own manifest JSONL with EXACT artifact paths from the API
           response (proPatchJson/predsJson/submissionPatch — never glob output dirs; that was
           the 2026-07-03 attribution bug). Scoring stays external (Scale harness).

             ./k-eval.py generate --provider deepseek --model deepseek-v4-pro-native \
                 --dataset /var/swebench/swebench_pro_sample20.json --k 3 --out ~/keval/pro20

aggregate  Take k scored results (Scale eval_results.json, one per pass) and emit
           mean +/- spread, per-task pass counts, and the always-pass / always-fail /
           noisy-middle decomposition.

             ./k-eval.py aggregate pass1/eval_results.json pass2/... pass3/...

canary     From the same k results (or more runs across models), emit canary task lists:
           always-pass (regression tripwire — a flip here is signal even at k=1) and
           always-fail (the ceiling — do not tune against the noisy middle).

Env: ES_BASE (default http://localhost:8080); admin creds ~/swebench-bench/.espec-admin.env.
"""

import argparse
import json
import os
import pathlib
import statistics
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("ES_BASE", "http://localhost:8080")
HOME = pathlib.Path.home()


# ---------- shared ----------

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
        BASE + path,
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            **({"Authorization": "Bearer " + token} if token else {}),
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode() or "{}")


def login():
    email, pw = load_env()
    st, d = post("/api/auth/login", {"email": email, "password": pw}, timeout=60)
    tok = d.get("accessToken") or d.get("access_token") or (d.get("data") or {}).get("accessToken")
    if not tok:
        sys.exit(f"login failed: {st} {str(d)[:200]}")
    return tok


# ---------- generate ----------

def cmd_generate(a):
    instances = [r["instance_id"] for r in json.load(open(a.dataset))]
    if a.limit:
        instances = instances[: a.limit]
    out_root = pathlib.Path(a.out).expanduser()
    out_root.mkdir(parents=True, exist_ok=True)
    token = login()

    for k in range(1, a.k + 1):
        manifest = out_root / f"pass{k}.manifest.jsonl"
        done = set()
        if manifest.exists():  # resumable per pass
            for line in manifest.read_text().splitlines():
                try:
                    done.add(json.loads(line)["instance_id"])
                except Exception:
                    pass
        print(f"== pass {k}/{a.k}: {len(instances)} instances ({len(done)} already done)")
        with open(manifest, "a") as mf:
            for i, iid in enumerate(instances):
                if iid in done:
                    continue
                t0 = time.time()
                row = {"instance_id": iid, "pass": k, "model": a.model}
                try:
                    st, d = post(
                        "/api/admin/benchmark/generate",
                        {"instanceId": iid, "aiProvider": a.provider, "modelName": a.model},
                        token,
                    )
                    # EXACT artifact paths from the response — never glob (attribution bug).
                    row.update(
                        status=st,
                        outcome=d.get("outcome"),
                        patchBytes=d.get("patchBytes"),
                        runId=d.get("runId"),
                        iterations=d.get("iterations"),
                        proPatchJson=d.get("proPatchJson"),
                        predsJson=d.get("predsJson"),
                        submissionPatch=d.get("submissionPatch"),
                        costUsd=d.get("costUsd"),
                        seconds=round(time.time() - t0, 1),
                    )
                except urllib.error.HTTPError as e:
                    row.update(status=e.code, error=e.read().decode()[:300], seconds=round(time.time() - t0, 1))
                except Exception as e:  # noqa: BLE001 — record and continue; manifest is the audit trail
                    row.update(status=-1, error=str(e)[:300], seconds=round(time.time() - t0, 1))
                mf.write(json.dumps(row) + "\n")
                mf.flush()
                print(f"   [{i+1}/{len(instances)}] {iid}: {row.get('outcome') or row.get('error','?')} ({row['seconds']}s)")
                if row.get("status") == 401:
                    token = login()
        print(f"== pass {k} manifest: {manifest}")
    print("\ngenerate done. Score each pass with the Scale harness, then: k-eval.py aggregate <k eval_results.json>")


# ---------- result parsing (tolerant) ----------

def load_results(path):
    """Return {instance_id: 0/1} from a Scale eval_results.json (schema-tolerant)."""
    d = json.load(open(path))
    out = {}
    if isinstance(d, dict):
        if "resolved" in d and isinstance(d["resolved"], list):
            for iid in d["resolved"]:
                out[iid] = 1
            for iid in d.get("unresolved", []) + d.get("failed", []):
                out[iid] = 0
            return out
        for k, v in d.items():
            if isinstance(v, bool):
                out[k] = int(v)
            elif isinstance(v, (int, float)):
                out[k] = int(bool(v))
            elif isinstance(v, dict) and ("resolved" in v or "reward" in v):
                out[k] = int(bool(v.get("resolved", v.get("reward"))))
        if out:
            return out
    if isinstance(d, list):
        for row in d:
            iid = row.get("instance_id")
            if iid is not None:
                out[iid] = int(bool(row.get("resolved", row.get("reward", 0))))
        if out:
            return out
    sys.exit(f"unrecognized results schema in {path} — extend load_results()")


def cmd_aggregate(a):
    passes = [load_results(p) for p in a.results]
    ids = sorted(set().union(*passes))
    per_pass = [sum(p.get(i, 0) for i in ids) for p in passes]
    n = len(ids)
    rates = [100.0 * s / n for s in per_pass]
    mean = statistics.mean(rates)
    spread = (max(rates) - min(rates)) if len(rates) > 1 else 0.0
    stdev = statistics.stdev(rates) if len(rates) > 1 else 0.0

    counts = {i: sum(p.get(i, 0) for p in passes) for i in ids}
    k = len(passes)
    always_pass = sorted(i for i, c in counts.items() if c == k)
    always_fail = sorted(i for i, c in counts.items() if c == 0)
    noisy = sorted(i for i, c in counts.items() if 0 < c < k)

    print(f"k={k} passes, n={n} tasks")
    print(f"per-pass resolved: {per_pass}  ({[f'{r:.0f}%' for r in rates]})")
    print(f"MEAN {mean:.1f}%  spread {spread:.1f}pp  stdev {stdev:.1f}pp")
    print(f"\nalways-pass {len(always_pass)} | always-fail {len(always_fail)} | NOISY {len(noisy)}")
    if noisy:
        print("noisy tasks (do NOT tune against these):")
        for i in noisy:
            print(f"   {i} ({counts[i]}/{k})")
    if a.json:
        print(
            json.dumps(
                {
                    "k": k, "n": n, "per_pass": per_pass, "mean_pct": mean,
                    "spread_pp": spread, "stdev_pp": stdev,
                    "always_pass": always_pass, "always_fail": always_fail, "noisy": noisy,
                },
                indent=2,
            )
        )


def cmd_canary(a):
    passes = [load_results(p) for p in a.results]
    ids = sorted(set().union(*passes))
    k = len(passes)
    counts = {i: sum(p.get(i, 0) for p in passes) for i in ids}
    out = pathlib.Path(a.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    ap = [i for i in ids if counts[i] == k]
    af = [i for i in ids if counts[i] == 0]
    (out / "always-pass.txt").write_text("\n".join(ap) + "\n")
    (out / "always-fail.txt").write_text("\n".join(af) + "\n")
    (out / "README.md").write_text(
        "# Canary task sets (derived, not hand-picked)\n\n"
        f"Derived from {k} scored passes on {time.strftime('%Y-%m-%d')}.\n\n"
        "- `always-pass.txt` — regression tripwire: run at k=1 on EVERY harness change; any flip"
        " here is real signal.\n"
        "- `always-fail.txt` — the current ceiling: harness/capability work lives here; do NOT"
        " tune against tasks outside these two lists (the noisy middle is coin-flips).\n\n"
        "Regenerate after any change that graduates (the sets drift as the harness improves).\n"
    )
    print(f"wrote {len(ap)} always-pass, {len(af)} always-fail -> {out}/")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("generate")
    g.add_argument("--provider", required=True)
    g.add_argument("--model", required=True)
    g.add_argument("--dataset", required=True)
    g.add_argument("--k", type=int, default=3)
    g.add_argument("--limit", type=int, default=0)
    g.add_argument("--out", required=True)

    for name in ("aggregate", "canary"):
        s = sub.add_parser(name)
        s.add_argument("results", nargs="+", help="k eval_results.json paths (one per pass)")
        if name == "aggregate":
            s.add_argument("--json", action="store_true")
        else:
            s.add_argument("--out", default="infra/benchmark/canary")

    a = ap.parse_args()
    {"generate": cmd_generate, "aggregate": cmd_aggregate, "canary": cmd_canary}[a.cmd](a)


if __name__ == "__main__":
    main()
