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

publish    Publish one official scorer result to the benchmark API/UI.  `generate` writes a
           `passN.tracking.json` sidecar; after scoring, run:
             ./k-eval.py publish --tracking-file out/pass1.tracking.json \
                 --manifest out/pass1.manifest.jsonl --results eval_results.json
           This emits one TASK_SCORED per instance followed by RUN_DONE.

Env: ES_BASE (default http://localhost:8080); admin creds ~/swebench-bench/.espec-admin.env.
"""

import argparse
import concurrent.futures
from contextlib import contextmanager
from decimal import Decimal, InvalidOperation
import fcntl
import json
import threading
import os
import pathlib
import statistics
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("ES_BASE", "http://localhost:8080")
HOME = pathlib.Path.home()
MONEY_QUANTUM = Decimal("0.000001")


# ---------- shared ----------

def load_env():
    # CI/operator runs may inject short-lived credentials without rewriting the local file.
    override_email = os.environ.get("ES_ADMIN_EMAIL")
    override_pw = os.environ.get("ES_ADMIN_PW")
    if override_email and override_pw:
        return override_email, override_pw
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


def post_authenticated(path, body, token_box, refresh_lock, timeout=3900):
    """POST once, refresh a rejected token once, then fail closed."""
    token_used = token_box["t"]
    try:
        return post(path, body, token_used, timeout=timeout)
    except urllib.error.HTTPError as exc:
        if exc.code != 401:
            raise
        with refresh_lock:
            if token_box["t"] == token_used:
                token_box["t"] = login()
        return post(path, body, token_box["t"], timeout=timeout)


# ---------- tracking (in-app /benchmark UI) ----------

def tracking_start(token_box, refresh_lock, provider, model, k, total, dataset_label):
    """Register this generation run before spend. Missing tracking is fatal."""
    _st, data = post_authenticated(
        "/api/v2/test/benchmark/start",
        {"suite": "SWE_BENCH", "dataset": dataset_label, "provider": provider,
         "model": model, "k": k, "totalTasks": total, "harborJobName": "k-eval-local"},
        token_box, refresh_lock, timeout=30)
    benchmark_id = data.get("id")
    if not isinstance(benchmark_id, str) or not benchmark_id.strip():
        raise RuntimeError("tracking start returned no benchmark id")
    return benchmark_id


def tracking_event(token_box, refresh_lock, bench_id, body):
    """Publish one event. Failure is fatal because tracking is part of the run contract."""
    if not bench_id:
        raise RuntimeError("benchmark tracking id is required")
    post_authenticated(
        f"/api/v2/test/benchmark/{bench_id}/event",
        body,
        token_box,
        refresh_lock,
        timeout=30,
    )


def money(value, label):
    try:
        parsed = Decimal(str(value)).quantize(MONEY_QUANTUM)
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ValueError(f"{label} must be a finite decimal") from exc
    if not parsed.is_finite() or parsed < 0:
        raise ValueError(f"{label} must be a finite non-negative decimal")
    return parsed


def atomic_json_write(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


class CampaignBudget:
    """Persistent reservation ledger; concurrent dispatch cannot oversubscribe the cap."""

    def __init__(self, path, per_task_cap, campaign_cap, opening_balance, expected_total):
        self.path = path
        self.per_task_cap = money(per_task_cap, "per-task cap")
        self.campaign_cap = money(campaign_cap, "campaign cap")
        self.opening_balance = money(opening_balance, "opening balance")
        self.expected_total = int(expected_total)
        self.lock = threading.Lock()
        self.lock_path = path.with_suffix(path.suffix + ".lock")
        if self.per_task_cap <= 0 or self.campaign_cap <= 0:
            raise ValueError("per-task and campaign caps must be positive")
        if self.opening_balance < self.campaign_cap:
            raise ValueError("opening provider balance is below the campaign reservation")
        if self.per_task_cap * self.expected_total > self.campaign_cap:
            raise ValueError("campaign cap cannot reserve the per-task cap for every expected task")
        with self._exclusive_file_lock():
            if path.exists():
                self._reload()
                expected = {
                    "perTaskCapUsd": str(self.per_task_cap),
                    "campaignCapUsd": str(self.campaign_cap),
                    "openingBalanceUsd": str(self.opening_balance),
                    "expectedTotal": self.expected_total,
                }
                for key, value in expected.items():
                    if self.state.get(key) != value:
                        raise ValueError(f"campaign ledger mismatch for {key}")
            else:
                self.state = {
                    "perTaskCapUsd": str(self.per_task_cap),
                    "campaignCapUsd": str(self.campaign_cap),
                    "openingBalanceUsd": str(self.opening_balance),
                    "expectedTotal": self.expected_total,
                    "tasks": {},
                }
                self._save()

    @contextmanager
    def _exclusive_file_lock(self):
        with open(self.lock_path, "a+") as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)

    def _reload(self):
        self.state = json.loads(self.path.read_text())

    def _save(self):
        atomic_json_write(self.path, self.state)

    def _totals(self):
        spent = sum(
            (money(task.get("costUsd", "0"), "settled task cost")
             for task in self.state["tasks"].values() if task.get("status") == "SETTLED"),
            Decimal("0"),
        )
        reserved = sum(
            (self.per_task_cap for task in self.state["tasks"].values()
             if task.get("status") == "RESERVED"),
            Decimal("0"),
        )
        return spent, reserved

    def reserve(self, instance_id):
        with self.lock:
            with self._exclusive_file_lock():
                self._reload()
                current = self.state["tasks"].get(instance_id)
                if current and current.get("status") == "SETTLED":
                    return False
                if current and current.get("status") == "RESERVED":
                    raise RuntimeError(
                        f"task {instance_id} has an unresolved reservation; reconcile the "
                        "in-flight provider request before resuming")
                spent, reserved = self._totals()
                if spent + reserved + self.per_task_cap > self.campaign_cap:
                    raise RuntimeError(
                        f"campaign cap exhausted: spent={spent} reserved={reserved} "
                        f"next={self.per_task_cap} cap={self.campaign_cap}")
                self.state["tasks"][instance_id] = {"status": "RESERVED"}
                self._save()
                return True

    def settle(self, instance_id, actual_cost):
        cost = money(actual_cost, f"cost for {instance_id}")
        with self.lock:
            with self._exclusive_file_lock():
                self._reload()
                if cost > self.per_task_cap:
                    raise RuntimeError(
                        f"task {instance_id} cost {cost} exceeded hard cap {self.per_task_cap}")
                current = self.state["tasks"].get(instance_id)
                if current and current.get("status") == "SETTLED":
                    existing = money(current.get("costUsd"), f"settled cost for {instance_id}")
                    if existing != cost:
                        raise RuntimeError(
                            f"task {instance_id} is already settled at {existing}; "
                            f"refusing replacement cost {cost}")
                    return
                self.state["tasks"][instance_id] = {"status": "SETTLED", "costUsd": str(cost)}
                spent, _reserved = self._totals()
                if spent > self.campaign_cap:
                    raise RuntimeError(
                        f"settled spend {spent} exceeded campaign cap {self.campaign_cap}")
                self._save()

    def release(self, instance_id):
        with self.lock:
            with self._exclusive_file_lock():
                self._reload()
                if self.state["tasks"].get(instance_id, {}).get("status") == "RESERVED":
                    del self.state["tasks"][instance_id]
                    self._save()


def load_generation_manifest(path):
    """Return successful terminal rows; failed rows never count as resume completion."""
    completed = {}
    if not path.exists():
        return completed
    for line_number, line in enumerate(path.read_text().splitlines(), start=1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError as exc:
            raise SystemExit(f"malformed manifest row {line_number}: {exc}") from exc
        instance_id = row.get("instance_id")
        if not isinstance(instance_id, str) or not instance_id:
            raise SystemExit(f"malformed manifest row {line_number}: instance_id missing")
        if row.get("status") != 200:
            continue
        required = ("outcome", "runId", "costUsd", "submissionPatch", "predsJson", "proPatchJson")
        missing = [key for key in required if row.get(key) is None]
        if missing:
            raise SystemExit(
                f"malformed terminal manifest row {line_number} for {instance_id}: missing {missing}")
        text_fields = ("outcome", "runId", "submissionPatch", "predsJson", "proPatchJson")
        invalid_text = [key for key in text_fields if not isinstance(row.get(key), str)]
        if invalid_text:
            raise SystemExit(
                f"malformed terminal manifest row {line_number} for {instance_id}: "
                f"non-string {invalid_text}")
        try:
            money(row["costUsd"], f"manifest cost for {instance_id}")
        except ValueError as exc:
            raise SystemExit(f"malformed terminal manifest row {line_number}: {exc}") from exc
        if instance_id in completed:
            raise SystemExit(f"duplicate terminal manifest row for {instance_id}")
        completed[instance_id] = row
    return completed


def write_generation_manifest(path, completed, ordered_ids):
    rows = [completed[instance_id] for instance_id in ordered_ids if instance_id in completed]
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text("".join(json.dumps(row) + "\n" for row in rows))
    temporary.replace(path)


# ---------- generate ----------

def cmd_generate(a):
    if a.k <= 0:
        sys.exit("--k must be positive")
    if a.concurrency <= 0:
        sys.exit("--concurrency must be positive")
    if a.expected_total <= 0:
        sys.exit("--expected-total must be positive")
    dataset_rows = json.load(open(a.dataset))
    if not isinstance(dataset_rows, list):
        sys.exit("dataset must be a JSON array")
    try:
        instances = [row["instance_id"] for row in dataset_rows]
    except (KeyError, TypeError) as exc:
        sys.exit(f"dataset contains a malformed row: {exc}")
    if a.limit:
        instances = instances[: a.limit]
    if len(instances) != len(set(instances)):
        sys.exit("dataset contains duplicate instance_id values")
    if len(instances) != a.expected_total:
        sys.exit(
            f"dataset cardinality {len(instances)} != --expected-total {a.expected_total}; "
            "use an exact frozen dataset file")
    out_root = pathlib.Path(a.out).expanduser()
    out_root.mkdir(parents=True, exist_ok=True)
    # Validate and persist the ONE command-wide campaign reservation before login or tracker
    # creation. k passes are trials in the same provider-account campaign, not independent caps.
    try:
        budget = CampaignBudget(
            out_root / "campaign.budget.json",
            a.per_task_cap_usd,
            a.campaign_cap_usd,
            a.opening_balance_usd,
            a.expected_total * a.k,
        )
    except (ValueError, RuntimeError) as exc:
        sys.exit(f"campaign budget refused before tracking/provider dispatch: {exc}")
    token = login()
    token_box = {"t": token}
    refresh_lock = threading.Lock()
    dataset_label = pathlib.Path(a.dataset).stem + " " + a.model + " k-eval " + time.strftime("%Y-%m-%d")

    for k in range(1, a.k + 1):
        tracking_file = out_root / f"pass{k}.tracking.json"
        if tracking_file.exists():
            tracking_metadata = json.loads(tracking_file.read_text())
            bench_id = tracking_metadata.get("benchmarkId")
            if not bench_id:
                sys.exit(f"tracking sidecar has no benchmarkId: {tracking_file}")
            expected_tracking = {
                "provider": a.provider,
                "model": a.model,
                "attempt": k,
                "totalTasks": len(instances),
            }
            mismatched = [
                key for key, value in expected_tracking.items()
                if tracking_metadata.get(key) != value
            ]
            if mismatched:
                sys.exit(f"tracking sidecar does not match this run: {mismatched}")
            tracking_event(
                token_box, refresh_lock, bench_id,
                {"type": "RUN_PROGRESS", "message": "fail-closed generation resume preflight"})
        else:
            try:
                bench_id = tracking_start(
                    token_box, refresh_lock, a.provider, a.model, a.k, len(instances),
                    f"{dataset_label} pass{k}")
            except Exception as exc:
                sys.exit(f"tracking start failed before provider dispatch: {exc}")
            atomic_json_write(
                tracking_file,
                {
                    "benchmarkId": bench_id,
                    "provider": a.provider,
                    "model": a.model,
                    "attempt": k,
                    "totalTasks": len(instances),
                },
            )
        manifest = out_root / f"pass{k}.manifest.jsonl"
        attempts = out_root / f"pass{k}.attempts.jsonl"
        completed = load_generation_manifest(manifest)
        unexpected = sorted(set(completed) - set(instances))
        if unexpected:
            sys.exit(f"manifest contains task(s) outside frozen dataset: {unexpected[:5]}")
        for instance_id, row in completed.items():
            budget.settle(f"pass{k}:{instance_id}", row["costUsd"])
        done = set(completed)
        todo = [iid for iid in instances if iid not in done]
        print(f"== pass {k}/{a.k}: {len(instances)} instances ({len(done)} done, {len(todo)} to run, concurrency {a.concurrency})")
        mf_lock = threading.Lock()
        counter = {"n": 0, "successful": 0}
        stop_dispatch = threading.Event()

        def run_one(iid):
            if stop_dispatch.is_set():
                return None
            t0 = time.time()
            row = {"instance_id": iid, "pass": k, "model": a.model}
            reservation_key = f"pass{k}:{iid}"
            reserved = False
            provider_dispatched = False
            try:
                reserved = budget.reserve(reservation_key)
                if not reserved:
                    return completed.get(iid)
                provider_dispatched = True
                st, d = post_authenticated(
                    "/api/admin/benchmark/generate",
                    {"instanceId": iid, "aiProvider": a.provider, "modelName": a.model,
                     "hardCapUsd": float(a.per_task_cap_usd)},
                    token_box,
                    refresh_lock,
                )
                # EXACT artifact paths from the response — never glob (attribution bug).
                row.update(
                    status=st, outcome=d.get("outcome"), patchBytes=d.get("patchBytes"),
                    runId=d.get("runId"), iterations=d.get("iterations"),
                    proPatchJson=d.get("proPatchJson"), predsJson=d.get("predsJson"),
                    submissionPatch=d.get("submissionPatch"), costUsd=d.get("costUsd"),
                    inputTokens=d.get("inputTokens"), outputTokens=d.get("outputTokens"),
                    seconds=round(time.time() - t0, 1),
                )
                if st != 200:
                    raise RuntimeError(f"generation returned HTTP {st}")
                actual_cost = money(row.get("costUsd"), f"cost for {iid}")
                if actual_cost > budget.per_task_cap:
                    raise RuntimeError(
                        f"task {iid} cost {actual_cost} exceeded hard cap {budget.per_task_cap}")
            except urllib.error.HTTPError as e:
                row.update(status=e.code, error=e.read().decode()[:300], seconds=round(time.time() - t0, 1))
                stop_dispatch.set()
            except Exception as e:  # record and continue; manifest is the audit trail
                row.update(status=-1, error=str(e)[:300], seconds=round(time.time() - t0, 1))
                if provider_dispatched:
                    stop_dispatch.set()
            if row.get("status") != 200 and reserved and not provider_dispatched:
                budget.release(reservation_key)
            with mf_lock:
                counter["n"] += 1
                i = counter["n"]
                with open(attempts, "a") as af:
                    af.write(json.dumps(row) + "\n")
                    af.flush()
                if row.get("status") == 200:
                    completed[iid] = row
                    write_generation_manifest(manifest, completed, instances)
                    # Manifest-first makes a crash recoverable: on resume this exact terminal row
                    # deterministically settles any still-RESERVED ledger entry.
                    budget.settle(reservation_key, row["costUsd"])
                print(f"   [{i}/{len(todo)}] {iid}: {row.get('outcome') or row.get('error','?')} ({row['seconds']}s)")
                if row.get("status") == 200:
                    counter["successful"] += 1
                try:
                    tracking_event(
                        token_box, refresh_lock, bench_id,
                        {"type": "RUN_PROGRESS", "taskName": iid, "esRunId": row.get("runId"),
                         "outcome": row.get("outcome"),
                         "message": f"[{i}/{len(todo)}] {iid}: {row.get('outcome') or row.get('error','?')}"
                                    f" patch={row.get('patchBytes')}B ${row.get('costUsd')} {row['seconds']}s (UNSCORED)"})
                except Exception:
                    stop_dispatch.set()
                    raise
            return row

        if a.concurrency <= 1:
            for iid in todo:
                run_one(iid)
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=a.concurrency) as ex:
                list(ex.map(run_one, todo))
        if len(completed) != a.expected_total:
            tracking_event(token_box, refresh_lock, bench_id, {"type": "RUN_FAILED",
                           "message": f"generation incomplete: {len(completed)}/{a.expected_total} terminal rows"})
            sys.exit(f"generation incomplete: {len(completed)}/{a.expected_total} terminal rows")
        if todo and counter["successful"] == 0:
            tracking_event(token_box, refresh_lock, bench_id, {"type": "RUN_FAILED",
                           "message": "generation preflight failed; no tasks completed"})
        else:
            tracking_event(token_box, refresh_lock, bench_id, {"type": "RUN_PROGRESS",
                           "message": f"pass {k}/{a.k} generation complete — score externally, then post rewards"})
        print(f"== pass {k} manifest: {manifest}")
    print("\ngenerate done. Score each pass with the Scale harness, then: k-eval.py aggregate <k eval_results.json>")


def cmd_publish(a):
    """Publish official per-instance scores to an existing /benchmark run."""
    token = login()
    token_box = {"t": token}
    refresh_lock = threading.Lock()
    benchmark_id = a.benchmark_id
    if a.tracking_file:
        metadata = json.loads(pathlib.Path(a.tracking_file).expanduser().read_text())
        benchmark_id = benchmark_id or metadata.get("benchmarkId")
        attempt = a.attempt or int(metadata.get("attempt", 1))
    else:
        attempt = a.attempt or 1
    if not benchmark_id:
        sys.exit("publish requires --benchmark-id or --tracking-file from generate")
    scores = load_results(a.results)
    manifest_rows = {}
    manifest_ids = []
    if a.manifest:
        manifest_rows = load_generation_manifest(pathlib.Path(a.manifest).expanduser())
        manifest_ids = list(manifest_rows)
    expected_total = a.expected_total
    if expected_total is None and a.tracking_file:
        expected_total = metadata.get("totalTasks")
    validate_publish_coverage(scores, manifest_ids, expected_total)
    posted = 0
    for instance_id in sorted(scores):
        row = manifest_rows.get(instance_id, {})
        body = {
            "type": "TASK_SCORED",
            "taskName": instance_id,
            "attempt": attempt,
            "reward": int(scores[instance_id]),
        }
        if row.get("runId"):
            body["esRunId"] = row["runId"]
        if row.get("costUsd") is not None:
            body["costUsd"] = row["costUsd"]
        if row.get("inputTokens") is not None:
            body["inputTokens"] = int(row["inputTokens"])
        if row.get("outputTokens") is not None:
            body["outputTokens"] = int(row["outputTokens"])
        try:
            post_authenticated(
                f"/api/v2/test/benchmark/{benchmark_id}/event",
                body,
                token_box,
                refresh_lock,
                timeout=60,
            )
            posted += 1
        except Exception as exc:
            sys.exit(f"publish failed for {instance_id}: {exc}")
    post_authenticated(
        f"/api/v2/test/benchmark/{benchmark_id}/event",
        {"type": "RUN_DONE", "message": f"official scoring published: {posted} tasks"},
        token_box,
        refresh_lock,
        timeout=60,
    )
    print(f"published {posted} TASK_SCORED events + RUN_DONE to benchmark {benchmark_id}")


def validate_publish_coverage(scores, manifest_ids, expected_total=None):
    """Fail closed unless the official report covers the intended submission set."""
    score_ids = set(scores)
    if manifest_ids:
        expected_ids = set(manifest_ids)
        if expected_total is not None and len(expected_ids) != int(expected_total):
            raise SystemExit(
                f"publish refused: manifest terminal count {len(expected_ids)} "
                f"!= expected total {int(expected_total)}")
        missing = sorted(expected_ids - score_ids)
        extra = sorted(score_ids - expected_ids)
        if missing or extra:
            details = []
            if missing:
                details.append(f"missing results for {len(missing)} manifest instance(s): {', '.join(missing[:5])}")
            if extra:
                details.append(f"results contain {len(extra)} instance(s) absent from manifest: {', '.join(extra[:5])}")
            raise SystemExit("publish refused: incomplete result coverage; " + "; ".join(details))
        return
    if expected_total is None:
        raise SystemExit("publish requires --manifest or --expected-total to prove exact result coverage")
    if len(score_ids) != int(expected_total):
        raise SystemExit(
            f"publish refused: official result count {len(score_ids)} != expected total {int(expected_total)}"
        )


# ---------- result parsing (tolerant) ----------

def load_results(path):
    """Return {instance_id: 0/1} from a Scale eval_results.json (schema-tolerant)."""
    d = json.load(open(path))
    out = {}
    if isinstance(d, dict):
        # Official SWE-bench harness report schema. Empty patches are verifier-truth
        # unresolved submissions; harness errors likewise remain in the denominator.
        # `submitted_ids` is authoritative, so a report for a dataset subset cannot
        # accidentally pull the full split's `incomplete_ids` into this run.
        if "submitted_ids" in d and isinstance(d["submitted_ids"], list):
            submitted = d["submitted_ids"]
            if len(submitted) != len(set(submitted)):
                sys.exit("official result contains duplicate submitted_ids")
            category_lists = [
                d.get("resolved_ids", []),
                d.get("unresolved_ids", []),
                d.get("empty_patch_ids", []),
                d.get("error_ids", []),
            ]
            if any(len(values) != len(set(values)) for values in category_lists):
                sys.exit("official result contains duplicate ids in a terminal category")
            resolved, unresolved, empty, errors = map(set, category_lists)
            categories = [resolved, unresolved, empty, errors]
            if sum(len(category) for category in categories) != len(set().union(*categories)):
                sys.exit("official result classifies an instance in multiple terminal categories")
            classified = resolved | unresolved | empty | errors
            missing = sorted(set(submitted) - classified)
            extra = sorted(classified - set(submitted))
            if missing or extra:
                sys.exit(
                    "official result terminal classification mismatch: "
                    f"missing={missing[:5]} extra={extra[:5]}")
            for iid in submitted:
                out[iid] = 1 if iid in resolved else 0
            if out:
                return out
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
                if iid in out:
                    sys.exit(f"official result contains duplicate instance_id: {iid}")
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
    g.add_argument("--concurrency", type=int, default=1, help="parallel generate requests (server cap is max-concurrent-runs; watch RAM)")
    g.add_argument("--expected-total", type=int, required=True,
                   help="exact frozen dataset cardinality; mismatch stops before provider dispatch")
    g.add_argument("--per-task-cap-usd", type=Decimal, required=True,
                   help="hard cap sent to the benchmark worker for every task")
    g.add_argument("--campaign-cap-usd", type=Decimal, required=True,
                   help="aggregate reservation ceiling across this pass")
    g.add_argument("--opening-balance-usd", type=Decimal, required=True,
                   help="operator-recorded provider balance; must cover the campaign reservation")

    p = sub.add_parser("publish")
    p.add_argument("--results", required=True, help="official scorer eval_results.json")
    p.add_argument("--benchmark-id", help="existing /benchmark run id")
    p.add_argument("--tracking-file", help="passN.tracking.json emitted by generate")
    p.add_argument("--manifest", help="generation manifest for esRunId/cost attribution")
    p.add_argument("--expected-total", type=int, help="expected result count when no manifest is available")
    p.add_argument("--attempt", type=int, default=0)

    for name in ("aggregate", "canary"):
        s = sub.add_parser(name)
        s.add_argument("results", nargs="+", help="k eval_results.json paths (one per pass)")
        if name == "aggregate":
            s.add_argument("--json", action="store_true")
        else:
            s.add_argument("--out", default="infra/benchmark/canary")

    a = ap.parse_args()
    {"generate": cmd_generate, "publish": cmd_publish, "aggregate": cmd_aggregate, "canary": cmd_canary}[a.cmd](a)


if __name__ == "__main__":
    main()
