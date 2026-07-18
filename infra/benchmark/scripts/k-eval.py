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
import hashlib
import json
import math
import re
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
REDACTED = "[REDACTED]"
SENSITIVE_FIELD_NAMES = {
    "authorization", "proxyauthorization", "apikey", "accesskey", "secretkey",
    "accesstoken", "refreshtoken", "idtoken", "password", "passwd", "secret",
    "credential", "credentials", "cookie", "setcookie", "privatekey", "token",
    "bearertoken", "authtoken", "clientsecret", "passwordhash", "pat",
    "personalaccesstoken",
}
SENSITIVE_FIELD_SUFFIXES = (
    "apikey", "accesstoken", "refreshtoken", "idtoken", "password", "passwd",
    "secret", "credential", "privatekey", "token",
)
SECRET_TEXT_PATTERNS = (
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"),
    re.compile(r"\bes_pat_[A-Za-z0-9._-]{8,}"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"\bsk-[A-Za-z0-9_-]{16,}"),
    re.compile(r"\bAIza[A-Za-z0-9_-]{20,}"),
    re.compile(r"\bAKIA[A-Z0-9]{16}\b"),
    re.compile(
        r"(?i)\b(api[_-]?key|(?:personal[_-]?access|access|refresh|auth)?[_-]?token|pat|"
        r"password|passwd|secret|credential)\s*([:=])\s*(?!\[REDACTED\])"
        r"([^\s,;&}\]]+)"),
    re.compile(
        r"(?i)(?:~|/)[^\s\"']*(?:\.env|creds?|credentials?|key[_-]?rotation|secrets?)"
        r"[^\s\"']*"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\s\S]*?-----END "
               r"(?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)

PRE_PROVIDER_CONCURRENCY_429 = "PRE_PROVIDER_CONCURRENCY_429"
AMBIGUOUS_PRESSURE = "AMBIGUOUS_PRESSURE"
ABORT_AMBIGUOUS = "ABORT_AMBIGUOUS"
SERVER_CONCURRENCY_MESSAGE = "benchmark generation concurrency exhausted"


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


def get(path, token=None, timeout=60):
    req = urllib.request.Request(
        BASE + path,
        method="GET",
        headers={"Authorization": "Bearer " + token} if token else {},
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.status, json.loads(response.read().decode() or "null")


def get_authenticated(path, token_box, refresh_lock, timeout=60):
    """GET once, refresh a rejected token once, then fail closed."""
    token_used = token_box["t"]
    try:
        return get(path, token_used, timeout=timeout)
    except urllib.error.HTTPError as exc:
        if exc.code != 401:
            raise
        with refresh_lock:
            if token_box["t"] == token_used:
                token_box["t"] = login()
        return get(path, token_box["t"], timeout=timeout)


def get_bytes(path, token=None, timeout=60):
    """GET a bounded binary artifact and retain response metadata for integrity checks."""
    req = urllib.request.Request(
        BASE + path,
        method="GET",
        headers={"Authorization": "Bearer " + token} if token else {},
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.status, response.read(), {
            key.lower(): value for key, value in response.headers.items()
        }


def get_bytes_authenticated(path, token_box, refresh_lock, timeout=60):
    """Binary GET once, refresh a rejected token once, then fail closed."""
    token_used = token_box["t"]
    try:
        return get_bytes(path, token_used, timeout=timeout)
    except urllib.error.HTTPError as exc:
        if exc.code != 401:
            raise
        with refresh_lock:
            if token_box["t"] == token_used:
                token_box["t"] = login()
        return get_bytes(path, token_box["t"], timeout=timeout)


# ---------- tracking (in-app /benchmark UI) ----------

def tracking_start(
        token_box, refresh_lock, provider, model, k, total, dataset_label,
        submission_required=False):
    """Register this generation run before spend. Missing tracking is fatal."""
    _st, data = post_authenticated(
        "/api/v2/test/benchmark/start",
        {"suite": "SWE_BENCH", "dataset": dataset_label, "provider": provider,
         "model": model, "k": k, "totalTasks": total, "harborJobName": "k-eval-local",
         "submissionRequired": bool(submission_required)},
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
        # proPatchJson is SWE-bench Pro-only; Lite generation correctly returns null for it.
        required = ("outcome", "runId", "costUsd", "submissionPatch", "predsJson")
        missing = [key for key in required if row.get(key) is None]
        if missing:
            raise SystemExit(
                f"malformed terminal manifest row {line_number} for {instance_id}: missing {missing}")
        text_fields = ("outcome", "runId", "submissionPatch", "predsJson")
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


def trajectory_artifact(out_root, pass_number, instance_id, run_id, replay):
    """Persist the actual run event trajectory; return a safe relative reference and digest.

    Raw events stay in the operator artifact directory.  Only the path relative to ``out_root``
    and the SHA-256 are published to the benchmark API; host paths and event content never are.
    """
    if not isinstance(replay, dict):
        raise ValueError("run event-log replay response must be a JSON object")
    if not replay.get("available"):
        raise ValueError(
            "run event-log replay is unavailable: " + str(replay.get("reason") or "unknown"))
    if replay.get("truncated"):
        raise ValueError("run event-log replay is truncated; refusing incomplete trajectory evidence")
    events = replay.get("events")
    if not isinstance(events, list):
        raise ValueError("run event-log replay events must be a JSON array")
    if not events:
        raise ValueError("run event-log response is empty; refusing placeholder trajectory evidence")
    directory = out_root / f"pass{pass_number}.trajectories"
    directory.mkdir(parents=True, exist_ok=True)
    filename = hashlib.sha256(instance_id.encode("utf-8")).hexdigest()[:24] + ".json"
    path = directory / filename
    trajectory = redact_trajectory_value({
        "schemaVersion": 1,
        "taskName": instance_id,
        "attempt": int(pass_number),
        "runId": run_id,
        "lastEventId": replay.get("lastEventId"),
        "events": events,
    })
    encoded = (json.dumps(trajectory, sort_keys=True, separators=(",", ":")) + "\n").encode()
    assert_no_recognized_secret(encoded.decode("utf-8"))
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(encoded)
    temporary.replace(path)
    relative = path.relative_to(out_root).as_posix()
    return relative, hashlib.sha256(encoded).hexdigest()


def durable_trajectory_artifact(
        out_root, pass_number, instance_id, run_id, token_box, refresh_lock):
    """Download the complete benchmark-profile trajectory and verify its server checksum.

    The endpoint is deliberately separate from the 500-event customer SSE replay. It returns
    redacted NDJSON and a ``truncated=false`` integrity contract. Any missing header, empty body,
    checksum mismatch, or truncation fails the submission evidence path closed.
    """
    status, encoded, headers = get_bytes_authenticated(
        f"/api/v2/test/benchmark/runs/{run_id}/trajectory",
        token_box,
        refresh_lock,
        timeout=120,
    )
    if status != 200:
        raise ValueError(f"durable trajectory returned HTTP {status}")
    if headers.get("x-trajectory-truncated", "").lower() != "false":
        raise ValueError("durable trajectory is truncated or missing completeness contract")
    expected = headers.get("x-trajectory-sha256", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("durable trajectory is missing a valid SHA-256")
    actual = hashlib.sha256(encoded).hexdigest()
    if actual != expected:
        raise ValueError("durable trajectory checksum mismatch")
    try:
        count = int(headers.get("x-trajectory-event-count", "0"))
    except ValueError as exc:
        raise ValueError("durable trajectory event count is malformed") from exc
    if not encoded or count <= 0 or encoded.count(b"\n") != count:
        raise ValueError("durable trajectory is empty or has an inconsistent event count")
    decoded = encoded.decode("utf-8")
    assert_no_recognized_secret(decoded)
    directory = out_root / f"pass{pass_number}.trajectories"
    directory.mkdir(parents=True, exist_ok=True)
    filename = hashlib.sha256(instance_id.encode("utf-8")).hexdigest()[:24] + ".ndjson"
    path = directory / filename
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(encoded)
    temporary.replace(path)
    return path.relative_to(out_root).as_posix(), actual


def redact_trajectory_value(value, field_name=None):
    """Recursively redact recognized secret fields and credential-bearing strings."""
    normalized_field = re.sub(r"[^a-z0-9]", "", str(field_name or "").lower())
    if (normalized_field in SENSITIVE_FIELD_NAMES
            or normalized_field.endswith(SENSITIVE_FIELD_SUFFIXES)):
        return REDACTED
    if isinstance(value, dict):
        return {str(key): redact_trajectory_value(child, key) for key, child in value.items()}
    if isinstance(value, list):
        return [redact_trajectory_value(child) for child in value]
    if not isinstance(value, str):
        return value
    redacted = value
    for pattern in SECRET_TEXT_PATTERNS:
        if "api[_-]?key" in pattern.pattern or "personal[_-]?access" in pattern.pattern:
            redacted = pattern.sub(lambda match: f"{match.group(1)}{match.group(2)}{REDACTED}", redacted)
        elif pattern.pattern.startswith("(?i)\\bBearer"):
            redacted = pattern.sub("Bearer " + REDACTED, redacted)
        elif "(?:~|/)" in pattern.pattern:
            redacted = pattern.sub("[REDACTED_PATH]", redacted)
        else:
            redacted = pattern.sub(REDACTED, redacted)
    return redacted


def assert_no_recognized_secret(serialized):
    """Refuse persistence if a recognized credential pattern survived redaction."""
    for pattern in SECRET_TEXT_PATTERNS:
        if pattern.search(serialized):
            raise ValueError("recognized secret remained after trajectory redaction")


def estimate_eta_seconds(durations, remaining, concurrency):
    """Conservative wall ETA from completed attempts; unknown until one attempt finishes."""
    usable = [float(value) for value in durations if value is not None and float(value) >= 0]
    if not usable or remaining <= 0:
        return 0 if remaining <= 0 else None
    waves = math.ceil(remaining / max(1, int(concurrency)))
    return max(0, math.ceil(statistics.mean(usable) * waves))


# ---------- generate ----------

def benchmark_deadline_fields(task_timeout_seconds, shutdown_reserve_seconds, now_seconds=None):
    """Build the shared absolute-deadline contract immediately before HTTP dispatch."""
    now = time.time() if now_seconds is None else float(now_seconds)
    return {
        "absoluteDeadlineEpochMs": int(now * 1000) + int(task_timeout_seconds) * 1000,
        "shutdownReserveMs": int(shutdown_reserve_seconds) * 1000,
    }


def classify_generation_http_error(status, body):
    """Classify only the server's exact semaphore 429 as provably pre-provider."""
    message = ""
    try:
        decoded = json.loads(body or "{}")
        message = str(decoded.get("message") or decoded.get("error") or "")
    except (TypeError, ValueError):
        message = str(body or "")
    normalized = message.lower()
    if int(status) == 429 and SERVER_CONCURRENCY_MESSAGE in normalized:
        return PRE_PROVIDER_CONCURRENCY_429
    if int(status) == 429 or int(status) in (408, 425, 502, 503, 504):
        return AMBIGUOUS_PRESSURE
    return ABORT_AMBIGUOUS


def classify_generation_exception(error):
    text = str(error).lower()
    pressure_markers = (
        "429", "rate limit", "too many requests", "connect", "connection",
        "timed out", "timeout", "reset by peer", "temporarily unavailable",
    )
    return AMBIGUOUS_PRESSURE if any(marker in text for marker in pressure_markers) \
        else ABORT_AMBIGUOUS


def apply_failed_request_reservation(budget, reservation_key, classification):
    """Release only when ExecuteSpec itself proves provider dispatch never started."""
    if classification == PRE_PROVIDER_CONCURRENCY_429:
        budget.release(reservation_key)
        return "RELEASED_PRE_PROVIDER"
    return "PRESERVED_INDETERMINATE"


def lower_concurrency(current):
    current = max(1, int(current))
    if current > 4:
        return 4
    if current > 2:
        return 2
    return 1


def dispatch_adaptive_waves(instance_ids, initial_concurrency, run_one):
    """Dispatch bounded waves and never refill a wave while its requests are in flight.

    Exact server semaphore 429s are safe to retry once the next lower wave starts. Any ambiguous
    request failure stops all later waves because its provider-spend outcome is unknown.
    """
    pending = list(instance_ids)
    if len(pending) != len(set(pending)):
        raise ValueError("adaptive dispatch received duplicate instance ids")
    concurrency = max(1, int(initial_concurrency))
    results = []
    transitions = []

    while pending:
        wave, pending = pending[:concurrency], pending[concurrency:]
        if len(wave) == 1:
            wave_results = [run_one(wave[0])]
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=len(wave)) as executor:
                wave_results = list(executor.map(run_one, wave))
        results.extend(wave_results)

        retry_pre_provider = [
            result["instance_id"] for result in wave_results
            if result and result.get("dispatchClassification") == PRE_PROVIDER_CONCURRENCY_429
        ]
        ambiguous = any(
            result and result.get("dispatchClassification") in
            (AMBIGUOUS_PRESSURE, ABORT_AMBIGUOUS)
            for result in wave_results
        )
        pressure = bool(retry_pre_provider) or any(
            result and result.get("dispatchClassification") == AMBIGUOUS_PRESSURE
            for result in wave_results
        )
        if not pressure and not ambiguous:
            continue

        previous_concurrency = concurrency
        lowered = lower_concurrency(previous_concurrency) if pressure else previous_concurrency
        if lowered < previous_concurrency:
            transitions.append({"from": concurrency, "to": lowered})
            print(f"adaptive concurrency step-down: {concurrency} -> {lowered}")
            concurrency = lowered

        if ambiguous:
            return {
                "results": results,
                "remaining": retry_pre_provider + pending,
                "finalConcurrency": concurrency,
                "transitions": transitions,
                "aborted": True,
            }
        if retry_pre_provider and concurrency == previous_concurrency:
            # Already at concurrency 1: do not loop forever on a closed server admission gate.
            return {
                "results": results,
                "remaining": retry_pre_provider + pending,
                "finalConcurrency": concurrency,
                "transitions": transitions,
                "aborted": True,
            }
        pending = retry_pre_provider + pending

    return {
        "results": results,
        "remaining": [],
        "finalConcurrency": concurrency,
        "transitions": transitions,
        "aborted": False,
    }


def cmd_generate(a):
    if a.k <= 0:
        sys.exit("--k must be positive")
    if a.concurrency <= 0:
        sys.exit("--concurrency must be positive")
    if a.expected_total <= 0:
        sys.exit("--expected-total must be positive")
    requested_max_new_tasks = getattr(a, "max_new_tasks", None)
    if requested_max_new_tasks is not None and int(requested_max_new_tasks) <= 0:
        sys.exit("--max-new-tasks must be positive")
    max_new_tasks = int(requested_max_new_tasks or 0)
    if max_new_tasks and a.k != 1:
        sys.exit("--max-new-tasks is valid only for a resumable pass@1 run (--k 1)")
    task_timeout_seconds = int(getattr(a, "task_timeout_seconds", 3600))
    shutdown_reserve_seconds = int(getattr(a, "shutdown_reserve_seconds", 120))
    if task_timeout_seconds <= 0:
        sys.exit("--task-timeout-seconds must be positive")
    if shutdown_reserve_seconds < 0:
        sys.exit("--shutdown-reserve-seconds must be non-negative")
    if task_timeout_seconds <= shutdown_reserve_seconds:
        sys.exit("--task-timeout-seconds must exceed --shutdown-reserve-seconds")
    submission_required = bool(getattr(a, "submission_required", False))
    if submission_required and a.k != 1:
        sys.exit("--submission-required is valid only for a frozen pass@1 run (--k 1)")
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
                "submissionRequired": submission_required,
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
                # One benchmark row represents one pass. `attempt=k` distinguishes the sidecars;
                # sending the command-wide k here would make each row expect totalTasks*k scores
                # even though publish closes that single pass with exactly totalTasks scores.
                bench_id = tracking_start(
                    token_box, refresh_lock, a.provider, a.model, 1, len(instances),
                    f"{dataset_label} pass{k}", submission_required)
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
                    "submissionRequired": submission_required,
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
        all_todo = [iid for iid in instances if iid not in done]
        todo = all_todo[:max_new_tasks] if max_new_tasks else all_todo
        deferred_count = len(all_todo) - len(todo)
        print(
            f"== pass {k}/{a.k}: {len(instances)} instances ({len(done)} done, "
            f"{len(todo)} in this wave, {deferred_count} deferred, concurrency {a.concurrency})")
        mf_lock = threading.Lock()
        counter = {
            "attempts": 0,
            "successful": 0,
            "durations": [row.get("seconds") for row in completed.values()
                          if row.get("seconds") is not None],
        }
        def run_one(iid):
            t0 = time.time()
            row = {"instance_id": iid, "pass": k, "model": a.model}
            reservation_key = f"pass{k}:{iid}"
            reserved = False
            request_started = False
            try:
                reserved = budget.reserve(reservation_key)
                if not reserved:
                    return completed.get(iid)
                with mf_lock:
                    remaining = max(0, len(instances) - len(completed))
                    eta = estimate_eta_seconds(
                        counter["durations"], remaining, a.concurrency)
                tracking_event(
                    token_box,
                    refresh_lock,
                    bench_id,
                    {
                        "type": "TASK_STARTED",
                        "taskName": iid,
                        "attempt": k,
                        "etaSeconds": eta,
                        "message": f"attempt {k} started for {iid}",
                    },
                )
                request_started = True
                deadline_fields = benchmark_deadline_fields(
                    task_timeout_seconds, shutdown_reserve_seconds)
                st, d = post_authenticated(
                    "/api/admin/benchmark/generate",
                    {"instanceId": iid, "aiProvider": a.provider, "modelName": a.model,
                     "hardCapUsd": float(a.per_task_cap_usd),
                     **deadline_fields},
                    token_box,
                    refresh_lock,
                    timeout=task_timeout_seconds + 60,
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
                try:
                    trajectory_location, trajectory_sha256 = durable_trajectory_artifact(
                        out_root, k, iid, row["runId"], token_box, refresh_lock)
                    row["trajectoryLocation"] = trajectory_location
                    row["trajectorySha256"] = trajectory_sha256
                except Exception as trajectory_error:
                    # Generation already spent provider money and returned canonical usage. Keep
                    # the successful terminal row resumable, but make missing trajectory evidence
                    # explicit. Submission-required campaigns fail closed below.
                    row["trajectoryError"] = str(trajectory_error)[:300]
            except urllib.error.HTTPError as e:
                error_body = e.read().decode(errors="replace")[:1000]
                if request_started:
                    classification = classify_generation_http_error(e.code, error_body)
                    reservation_state = apply_failed_request_reservation(
                        budget, reservation_key, classification) if reserved else "NOT_RESERVED"
                else:
                    classification = ABORT_AMBIGUOUS
                    if reserved:
                        budget.release(reservation_key)
                        reservation_state = "RELEASED_BEFORE_REQUEST"
                    else:
                        reservation_state = "NOT_RESERVED"
                row.update(
                    status=e.code,
                    error=error_body[:300],
                    seconds=round(time.time() - t0, 1),
                    dispatchClassification=classification,
                    reservationState=reservation_state,
                    recommendedConcurrency=lower_concurrency(a.concurrency),
                )
            except Exception as e:  # record and continue; manifest is the audit trail
                classification = (
                    classify_generation_exception(e) if request_started else ABORT_AMBIGUOUS)
                if reserved and request_started:
                    reservation_state = apply_failed_request_reservation(
                        budget, reservation_key, classification)
                elif reserved:
                    budget.release(reservation_key)
                    reservation_state = "RELEASED_BEFORE_REQUEST"
                else:
                    reservation_state = "NOT_RESERVED"
                row.update(
                    status=-1,
                    error=str(e)[:300],
                    seconds=round(time.time() - t0, 1),
                    dispatchClassification=classification,
                    reservationState=reservation_state,
                    recommendedConcurrency=lower_concurrency(a.concurrency),
                )
            with mf_lock:
                counter["attempts"] += 1
                i = counter["attempts"]
                with open(attempts, "a") as af:
                    af.write(json.dumps(row) + "\n")
                    af.flush()
                if row.get("status") == 200:
                    completed[iid] = row
                    write_generation_manifest(manifest, completed, instances)
                    # Manifest-first makes a crash recoverable: on resume this exact terminal row
                    # deterministically settles any still-RESERVED ledger entry.
                    budget.settle(reservation_key, row["costUsd"])
                print(f"   [attempt {i}] {iid}: {row.get('outcome') or row.get('error','?')} ({row['seconds']}s)")
                if row.get("status") == 200:
                    counter["successful"] += 1
                    counter["durations"].append(row["seconds"])
                remaining = max(0, len(instances) - len(completed))
                eta = estimate_eta_seconds(counter["durations"], remaining, a.concurrency)
                try:
                    tracking_event(
                        token_box, refresh_lock, bench_id,
                        {"type": "RUN_PROGRESS", "taskName": iid, "attempt": k,
                         "esRunId": row.get("runId"), "etaSeconds": eta,
                         "outcome": row.get("outcome"),
                         "message": f"[attempt {i}] {iid}: {row.get('outcome') or row.get('error','?')}"
                                    f" patch={row.get('patchBytes')}B ${row.get('costUsd')} {row['seconds']}s (UNSCORED)"})
                except Exception:
                    # Propagate out of this bounded wave. The dispatcher does not launch the next
                    # wave until every current future returns, so no separate stop flag is needed.
                    raise
            return row

        dispatch = dispatch_adaptive_waves(todo, a.concurrency, run_one)
        if dispatch["aborted"]:
            tracking_event(
                token_box,
                refresh_lock,
                bench_id,
                {
                    "type": "RUN_FAILED",
                    "message":
                        "adaptive generation stopped before new work; "
                        f"remaining={len(dispatch['remaining'])} "
                        f"recommendedConcurrency={dispatch['finalConcurrency']}",
                },
            )
            sys.exit(
                "adaptive generation stopped fail-closed; reconcile preserved reservations "
                f"before resuming at concurrency {dispatch['finalConcurrency']}")
        if deferred_count and len(completed) < a.expected_total:
            tracking_event(
                token_box,
                refresh_lock,
                bench_id,
                {
                    "type": "RUN_PROGRESS",
                    "message":
                        f"qualification wave complete: {len(completed)}/{a.expected_total}; "
                        f"resume same output directory for next {deferred_count}",
                },
            )
            print(
                f"qualification wave complete: {len(completed)}/{a.expected_total}; "
                "resume with the same --out directory")
            return
        if len(completed) != a.expected_total:
            tracking_event(token_box, refresh_lock, bench_id, {"type": "RUN_FAILED",
                           "message": f"generation incomplete: {len(completed)}/{a.expected_total} terminal rows"})
            sys.exit(f"generation incomplete: {len(completed)}/{a.expected_total} terminal rows")
        if submission_required:
            missing_trajectory = sorted(
                instance_id for instance_id, row in completed.items()
                if not row.get("trajectoryLocation") or not row.get("trajectorySha256"))
            if missing_trajectory:
                tracking_event(
                    token_box,
                    refresh_lock,
                    bench_id,
                    {
                        "type": "RUN_FAILED",
                        "message": "submission trajectory export incomplete: "
                                   f"{len(missing_trajectory)}/{a.expected_total}",
                    },
                )
                sys.exit(
                    "submission-required generation lacks trajectory evidence for "
                    f"{len(missing_trajectory)} task(s): {', '.join(missing_trajectory[:5])}")
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
    score_evidence = load_result_evidence(a.results)
    manifest_rows = {}
    manifest_ids = []
    if a.manifest:
        manifest_rows = load_generation_manifest(pathlib.Path(a.manifest).expanduser())
        manifest_ids = list(manifest_rows)
    expected_total = a.expected_total
    if expected_total is None and a.tracking_file:
        expected_total = metadata.get("totalTasks")
    validate_publish_coverage(scores, manifest_ids, expected_total)
    submission_required = bool(metadata.get("submissionRequired", False)) if a.tracking_file else False
    if submission_required:
        missing_trajectory = sorted(
            instance_id for instance_id, row in manifest_rows.items()
            if not row.get("trajectoryLocation") or not row.get("trajectorySha256"))
        if missing_trajectory:
            sys.exit(
                "publish refused: submission-required run lacks trajectory evidence for "
                f"{len(missing_trajectory)} task(s): {', '.join(missing_trajectory[:5])}")
    posted = 0
    for instance_id in sorted(scores):
        row = manifest_rows.get(instance_id, {})
        evidence = score_evidence.get(instance_id, {})
        body = {
            "type": "TASK_SCORED",
            "taskName": instance_id,
            "attempt": attempt,
            "reward": int(scores[instance_id]),
            "outcome": evidence.get("outcome"),
            "reason": evidence.get("reason"),
            "failureClassification": evidence.get("failureClassification"),
        }
        if row.get("runId"):
            body["esRunId"] = row["runId"]
        if row.get("costUsd") is not None:
            body["costUsd"] = row["costUsd"]
        if row.get("inputTokens") is not None:
            body["inputTokens"] = int(row["inputTokens"])
        if row.get("outputTokens") is not None:
            body["outputTokens"] = int(row["outputTokens"])
        trajectory_location = row.get("trajectoryLocation")
        trajectory_sha = row.get("trajectorySha256")
        if bool(trajectory_location) != bool(trajectory_sha):
            sys.exit(
                f"publish refused: incomplete trajectory evidence for {instance_id}")
        if trajectory_location:
            body["trajectoryLocation"] = trajectory_location
            body["trajectorySha256"] = trajectory_sha
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


def load_result_evidence(path):
    """Return scorer-grounded outcome/failure evidence without inventing infra causes."""
    document = json.load(open(path))
    scores = load_results(path)
    evidence = {
        instance_id: {
            "outcome": "PASSED" if reward else "FAILED",
            "reason": None,
            "failureClassification": "NONE" if reward else "UNKNOWN",
        }
        for instance_id, reward in scores.items()
    }
    if isinstance(document, dict) and isinstance(document.get("submitted_ids"), list):
        categories = (
            ("resolved_ids", "PASSED", "NONE", None),
            ("unresolved_ids", "FAILED", "AGENT_OR_TEST_FAILURE", "official verifier unresolved"),
            ("empty_patch_ids", "EMPTY_PATCH", "AGENT_OR_TEST_FAILURE", "empty patch"),
            # The aggregate official report does not distinguish scorer, image, or harness errors.
            # Preserve that uncertainty instead of falsely labelling every error as infrastructure.
            ("error_ids", "SCORER_ERROR", "UNKNOWN", "official scorer reported an error"),
        )
        for field, outcome, classification, reason in categories:
            for instance_id in document.get(field, []):
                evidence[instance_id] = {
                    "outcome": outcome,
                    "reason": reason,
                    "failureClassification": classification,
                }
        return evidence
    if isinstance(document, dict) and isinstance(document.get("resolved"), list):
        for instance_id in document.get("unresolved", []):
            evidence[instance_id] = {
                "outcome": "FAILED",
                "reason": "official verifier unresolved",
                "failureClassification": "AGENT_OR_TEST_FAILURE",
            }
        for instance_id in document.get("failed", []):
            evidence[instance_id] = {
                "outcome": "SCORER_ERROR",
                "reason": "official scorer reported a failure",
                "failureClassification": "UNKNOWN",
            }
        return evidence
    rows = document if isinstance(document, list) else []
    if isinstance(document, dict):
        rows = [dict(value, instance_id=key) for key, value in document.items()
                if isinstance(value, dict)]
    for row in rows:
        instance_id = row.get("instance_id")
        if instance_id not in evidence:
            continue
        outcome = str(row.get("outcome") or evidence[instance_id]["outcome"])
        reason = row.get("reason") or row.get("error")
        explicit = str(row.get("failureClassification") or "").upper()
        allowed = {"NONE", "TIMEOUT", "INFRASTRUCTURE", "VERIFIER",
                   "AGENT_OR_TEST_FAILURE", "UNKNOWN"}
        if explicit not in allowed:
            normalized = f"{outcome} {reason or ''}".upper()
            if "TIMEOUT" in normalized or "TIMED OUT" in normalized:
                explicit = "TIMEOUT"
            elif "VERIFIER" in normalized or "SCORER" in normalized:
                explicit = "VERIFIER"
            elif any(word in normalized for word in ("INFRA", "CONTAINER", "IMAGE PULL")):
                explicit = "INFRASTRUCTURE"
            elif scores[instance_id]:
                explicit = "NONE"
            elif reason or outcome != "FAILED":
                explicit = "AGENT_OR_TEST_FAILURE"
            else:
                explicit = "UNKNOWN"
        evidence[instance_id] = {
            "outcome": outcome,
            "reason": str(reason)[:500] if reason is not None else None,
            "failureClassification": explicit,
        }
    return evidence


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
    g.add_argument(
        "--max-new-tasks", type=int,
        help="launch at most the next N unfinished tasks, then exit RUN_PROGRESS for safe resume")
    g.add_argument("--expected-total", type=int, required=True,
                   help="exact frozen dataset cardinality; mismatch stops before provider dispatch")
    g.add_argument("--per-task-cap-usd", type=Decimal, required=True,
                   help="hard cap sent to the benchmark worker for every task")
    g.add_argument("--campaign-cap-usd", type=Decimal, required=True,
                   help="aggregate reservation ceiling across this pass")
    g.add_argument("--opening-balance-usd", type=Decimal, required=True,
                   help="operator-recorded provider balance; must cover the campaign reservation")
    g.add_argument(
        "--task-timeout-seconds", type=int, default=3600,
        help="absolute per-task deadline sent to the server (default: 3600)")
    g.add_argument(
        "--shutdown-reserve-seconds", type=int, default=120,
        help="time reserved before the task deadline for cleanup/response delivery (default: 120)")
    g.add_argument(
        "--submission-required", action="store_true",
        help="mark this frozen pass@1 as a leaderboard-submission candidate; requires --k 1")

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
