#!/usr/bin/env python3
"""Model-by-model HIGH-complexity eval (RL-2026-06).

Runs the EXACT same 5 HIGH product specs (high5-prompts.json) through the real local
pipeline once per model, using the ENTERPRISE per-run model pin (RunRequest.modelName ->
RunModelOverrides.withRunPin) so ONE model runs classifier + planner + every worker task +
reviewer — a true model-vs-model comparison, not a tier-matrix comparison.

Preflight: submits a tiny pinned smoke run and greps the backend log for the
"ENTERPRISE per-run pin" line. If the pin is ignored (owner tier not ENTERPRISE / model not
routable) it ABORTS before spending on the matrix.

Usage:
  python3 scripts/model-eval/high5-eval.py [--matrix-only|--preflight-only]
Env: BASE (default http://localhost:8080), EMAIL/PASSWORD (else parsed from
~/Desktop/local_creds.txt — first row whose email contains 'admin', falling back through rows),
BACKEND_LOG (default /tmp/es-local.log).
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8080").rstrip("/")
BACKEND_LOG = os.environ.get("BACKEND_LOG", "/tmp/es-local.log")
HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "results")
PROMPTS_FILE = os.path.join(HERE, "high5-prompts.json")

MODELS = [
    ("DEEPSEEK", "deepseek-v4-flash-native"),
    ("DEEPSEEK", "deepseek-v4-pro-native"),
    ("ANTHROPIC", "claude-sonnet-4-6"),
    ("OPENAI", "gpt-5.4"),
    ("GEMINI", "gemini-3.5-flash"),
]
TERMINAL = {"COMPLETE", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED"}
RUN_TIMEOUT_S = 75 * 60  # per-run hard ceiling, then cancel
SCORE_WAIT_S = 10 * 60  # post-terminal wait for the async verifiedScore
POLL_S = 20
CONCURRENCY = 2
USER_COST_CAP = 5.0

_print_lock = threading.Lock()
_ledger_lock = threading.Lock()
LEDGER_PATH = os.path.join(OUT_DIR, "ledger.json")


def log(msg):
    with _print_lock:
        print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def load_ledger():
    """cell-key -> runId, written at submit time so a killed harness can ADOPT its
    in-flight runs on relaunch instead of re-submitting (and re-paying for) them."""
    try:
        return json.load(open(LEDGER_PATH))
    except Exception:  # noqa: BLE001
        return {}


def ledger_put(key, run_id):
    with _ledger_lock:
        led = load_ledger()
        led[key] = run_id
        with open(LEDGER_PATH, "w") as fh:
            json.dump(led, fh, indent=2)


def req(path, token=None, method="GET", body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        t = resp.read().decode()
        return json.loads(t) if t.strip() else {}


def login():
    """ENTERPRISE-capable login. Order: EMAIL/PASSWORD env -> the local admin (v2-eval.sh
    defaults) -> creds-file rows with 'admin' in the email -> remaining rows. The per-run
    model pin needs an ENTERPRISE owner, so a FREE/PRO test row must never win by accident
    (it did on the first launch — preflight aborted, as designed)."""
    candidates = []
    email, pw = os.environ.get("EMAIL"), os.environ.get("PASSWORD")
    if email and pw:
        candidates.append((email, pw))
    candidates.append(("admin@executespec.local", "admin"))  # v2-eval.sh local default
    creds_path = os.path.expanduser("~/Desktop/local_creds.txt")
    if os.path.exists(creds_path):
        rows = []
        for line in open(creds_path):
            parts = line.split()
            if len(parts) >= 2 and "@" in parts[0]:
                rows.append((parts[0], parts[1]))
        rows.sort(key=lambda r: 0 if "admin" in r[0].lower() else 1)
        candidates.extend(rows)
    seen = set()
    for em, p in candidates:
        if em in seen:
            continue
        seen.add(em)
        try:
            d = req("/api/auth/login", method="POST", body={"email": em, "password": p})
            tok = d.get("accessToken") or d.get("token")
            if tok:
                log(f"auth ok as {em}")
                return tok
        except Exception:
            continue
    sys.exit("FATAL: no credential row could log in")


def submit(token, spec, provider, model):
    body = {
        "userRequest": spec,
        "skipApproval": True,
        "executionMode": "PROTOTYPE",
        "aiProvider": provider,
        "modelName": model,
        "userCostCap": USER_COST_CAP,
    }
    for attempt in (1, 2):
        try:
            d = req("/api/runs", token, "POST", body, timeout=90)
            rid = d.get("runId") or d.get("id")
            if rid:
                return rid
        except urllib.error.HTTPError as e:
            log(f"submit {model} HTTP {e.code}: {e.read().decode()[:200]}")
        except Exception as e:  # noqa: BLE001
            log(f"submit {model} error: {e}")
        time.sleep(3)
    return None


def run_detail(token, rid):
    try:
        return req(f"/api/runs/{rid}", token)
    except Exception:  # noqa: BLE001
        return {}


def poll_terminal(token, rid, label):
    start = time.time()
    last = ""
    while time.time() - start < RUN_TIMEOUT_S:
        d = run_detail(token, rid)
        st = (d.get("status") or "").upper()
        if st != last:
            log(f"{label} -> {st} ({int(time.time() - start)}s)")
            last = st
        if st in TERMINAL:
            return d, int(time.time() - start)
        time.sleep(POLL_S)
    log(f"{label} TIMEOUT after {RUN_TIMEOUT_S}s — cancelling")
    try:
        req(f"/api/runs/{rid}", token, "DELETE")
    except Exception:  # noqa: BLE001
        pass
    return {"status": "TIMEOUT"}, int(time.time() - start)


def wait_for_score(token, rid):
    """verifiedScore lands asynchronously (background validation) after terminal."""
    start = time.time()
    while time.time() - start < SCORE_WAIT_S:
        d = run_detail(token, rid)
        if d.get("verifiedScore") is not None:
            return d
        time.sleep(15)
    return run_detail(token, rid)


def cost_snapshot(token, rid):
    try:
        return req(f"/api/runs/{rid}/cost", token)
    except Exception:  # noqa: BLE001
        return {}


def one_cell(token, prompt, provider, model, raw_dir):
    label = f"{prompt['id']} × {model}"
    key = f"{prompt['id']}__{model}"
    t0 = time.time()
    rid = load_ledger().get(key)
    if rid:
        log(f"ADOPT {label} -> existing run {rid[:8]}")
    else:
        log(f"SUBMIT {label}")
        rid = submit(token, prompt["spec"], provider, model)
        if not rid:
            return {"prompt": prompt["id"], "model": model, "status": "SUBMIT_FAILED"}
        ledger_put(key, rid)
    d, dur = poll_terminal(token, rid, label)
    st = (d.get("status") or "").upper()
    if st in {"COMPLETE", "COMPLETED", "PARTIAL"}:
        d = wait_for_score(token, rid)
    cost = cost_snapshot(token, rid)
    tasks = d.get("tasks") or []
    cell = {
        "prompt": prompt["id"],
        "model": model,
        "provider": provider,
        "runId": rid,
        "status": st,
        "statusReason": d.get("statusReason"),
        "verifiedScore": d.get("verifiedScore"),
        "tasksTotal": len(tasks),
        "tasksDone": sum(1 for t in tasks if (t.get("status") or "").startswith("COMPLETE")),
        "tasksFailed": sum(1 for t in tasks if t.get("status") == "FAILED"),
        "planStrategy": d.get("planStrategy"),
        "costUsd": cost.get("costUsd") or d.get("actualCostUsd"),
        "inputTokens": cost.get("inputTokens") or d.get("totalInputTokens"),
        "outputTokens": cost.get("outputTokens") or d.get("totalOutputTokens"),
        "durationSec": dur,
        "wallStartedAt": t0,
    }
    with open(os.path.join(raw_dir, f"{prompt['id']}__{model}.json"), "w") as fh:
        json.dump({"cell": cell, "runDetail": d, "cost": cost}, fh, indent=2, default=str)
    log(
        f"DONE {label}: {st} score={cell['verifiedScore']} cost=${cell['costUsd']}"
        f" tasks={cell['tasksDone']}/{cell['tasksTotal']} {dur}s"
    )
    return cell


def preflight(token):
    """Tiny pinned run; verify the backend actually honored the ENTERPRISE pin."""
    log("PREFLIGHT: tiny pinned smoke (deepseek-v4-flash-native)")
    rid = submit(
        token,
        "Create a single Python file hello.py that prints 'Hello, ExecuteSpec!' and a"
        " test_hello.py with one pytest asserting the output. Nothing else.",
        "DEEPSEEK",
        "deepseek-v4-flash-native",
    )
    if not rid:
        sys.exit("PREFLIGHT FAIL: smoke submit failed")
    d, dur = poll_terminal(token, rid, "preflight")
    st = (d.get("status") or "").upper()
    pin_ok, pin_evidence = None, ""
    if os.path.exists(BACKEND_LOG):
        tail = open(BACKEND_LOG, errors="replace").read()[-400_000:]
        for line in tail.splitlines():
            if rid in line and "per-run pin" in line:
                pin_evidence = line[-220:]
                pin_ok = "ENTERPRISE per-run pin" in line
        if pin_ok is None:
            for line in tail.splitlines():
                if rid in line and "not ENTERPRISE" in line:
                    pin_ok, pin_evidence = False, line[-220:]
    log(f"preflight run {rid}: {st} in {dur}s; pin_ok={pin_ok}")
    if pin_evidence:
        log(f"pin evidence: …{pin_evidence}")
    if pin_ok is False:
        sys.exit(
            "PREFLIGHT FAIL: the per-run model pin was IGNORED (owner tier not ENTERPRISE or"
            " model not routable). Fix the local user's tier, then rerun."
        )
    if pin_ok is None:
        log("WARN: could not find pin log line (custom log path?) — proceeding on run success")
    if st not in {"COMPLETE", "COMPLETED", "PARTIAL"}:
        sys.exit(f"PREFLIGHT FAIL: smoke run ended {st}")
    log("PREFLIGHT OK")


def render_md(cells, out_md):
    prompts = sorted({c["prompt"] for c in cells})
    models = [m for _, m in MODELS]
    by = {(c["prompt"], c["model"]): c for c in cells}

    def fmt(c):
        if not c:
            return "—"
        s = c.get("verifiedScore")
        score = str(s) if s is not None else "?"
        return f"**{score}** · {c.get('status', '?')[:4]} · ${(c.get('costUsd') or 0):.2f}"

    lines = [
        "# HIGH-5 model-by-model eval (local, ENTERPRISE per-run pin)",
        "",
        f"Generated {time.strftime('%Y-%m-%d %H:%M')} · cell = verifiedScore · status · cost",
        "",
        "| Prompt | " + " | ".join(models) + " |",
        "|---|" + "---|" * len(models),
    ]
    for p in prompts:
        lines.append(f"| {p} | " + " | ".join(fmt(by.get((p, m))) for m in models) + " |")
    lines.append("")
    lines.append("| Model | avg score | total cost | avg min/run | failures |")
    lines.append("|---|---|---|---|---|")
    for m in models:
        mc = [c for c in cells if c["model"] == m]
        scores = [c["verifiedScore"] for c in mc if c.get("verifiedScore") is not None]
        avg = f"{sum(scores) / len(scores):.1f}" if scores else "—"
        cost = sum(float(c.get("costUsd") or 0) for c in mc)
        mins = (
            f"{sum(c.get('durationSec') or 0 for c in mc) / max(len(mc), 1) / 60:.0f}"
            if mc
            else "—"
        )
        fails = sum(1 for c in mc if c.get("status") not in {"COMPLETE", "COMPLETED"})
        lines.append(f"| {m} | {avg} | ${cost:.2f} | {mins} | {fails} |")
    with open(out_md, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    log(f"report -> {out_md}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preflight-only", action="store_true")
    ap.add_argument("--matrix-only", action="store_true", help="skip preflight")
    ap.add_argument("--concurrency", type=int, default=CONCURRENCY)
    a = ap.parse_args()

    ts = time.strftime("%Y%m%d-%H%M%S")
    raw_dir = os.path.join(OUT_DIR, f"high5-{ts}-raw")
    os.makedirs(raw_dir, exist_ok=True)
    prompts = json.load(open(PROMPTS_FILE))["prompts"]
    token = login()

    if not a.matrix_only:
        preflight(token)
    if a.preflight_only:
        return

    jobs = [(p, prov, m) for p in prompts for prov, m in MODELS]
    log(f"MATRIX: {len(jobs)} cells ({len(prompts)} prompts × {len(MODELS)} models),"
        f" concurrency={a.concurrency}")
    cells = []
    jsonl = os.path.join(OUT_DIR, f"high5-{ts}.jsonl")
    with cf.ThreadPoolExecutor(max_workers=a.concurrency) as pool:
        futs = {pool.submit(one_cell, token, p, prov, m, raw_dir): (p["id"], m)
                for p, prov, m in jobs}
        for fut in cf.as_completed(futs):
            try:
                cell = fut.result()
            except Exception as e:  # noqa: BLE001
                pid, m = futs[fut]
                cell = {"prompt": pid, "model": m, "status": "HARNESS_ERROR", "error": str(e)}
            cells.append(cell)
            with open(jsonl, "a") as fh:
                fh.write(json.dumps(cell, default=str) + "\n")
    render_md(cells, os.path.join(OUT_DIR, f"high5-{ts}.md"))
    done = sum(1 for c in cells if c.get("status") in {"COMPLETE", "COMPLETED"})
    log(f"ALL DONE: {done}/{len(cells)} complete. Results: {jsonl}")


if __name__ == "__main__":
    main()
