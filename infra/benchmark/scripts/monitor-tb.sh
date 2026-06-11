#!/bin/bash
set -euo pipefail

JOB="${JOB_NAME:-}"
PROCESS_LINES="$(pgrep -af '/harbor .*run|harbor run|tb-bench-bridge.py|es_harbor_agent.py' || true)"
ACTIVE_JOB_DIR="$(printf '%s\n' "$PROCESS_LINES" | sed -nE 's#.*--job-dir ([^ ]+).*#\1#p' | tail -1)"

JOBS_DIR="${ACTIVE_JOB_DIR%/*}"
if [ "$JOBS_DIR" = "$ACTIVE_JOB_DIR" ]; then
  JOBS_DIR=""
fi
if [ -z "${JOBS_DIR:-}" ]; then
  JOBS_DIR="$HOME/es-tb-jobs"
fi

if [ -z "$JOB" ] && [ -n "$ACTIVE_JOB_DIR" ]; then
  JOB="$(basename "$ACTIVE_JOB_DIR")"
fi
if [ -z "$JOB" ] && [ -f "$JOBS_DIR/tb21-focused-current.txt" ]; then
  JOB="$(cat "$JOBS_DIR/tb21-focused-current.txt")"
fi
if [ -z "$JOB" ] && [ -f "$JOBS_DIR/tb21-current.txt" ]; then
  JOB="$(cat "$JOBS_DIR/tb21-current.txt")"
fi
if [ -z "$JOB" ]; then
  LATEST_MARKER="$(ls -1t /home/*/es-tb-jobs/tb21-focused-current.txt /home/*/es-tb-jobs/tb21-current.txt 2>/dev/null | head -1 || true)"
  if [ -n "$LATEST_MARKER" ]; then
    JOB="$(cat "$LATEST_MARKER")"
    JOBS_DIR="$(dirname "$LATEST_MARKER")"
  fi
fi
if [ -z "$JOB" ]; then
  LATEST_RUN_LOG="$(ls -1t /home/*/es-tb-jobs/*-run.log "$JOBS_DIR"/*-run.log 2>/dev/null | head -1 || true)"
  if [ -n "$LATEST_RUN_LOG" ]; then
    JOB="$(basename "$LATEST_RUN_LOG" | sed -E 's#(.*)-run\.log#\1#')"
    JOBS_DIR="$(dirname "$LATEST_RUN_LOG")"
  fi
fi

HOME_FOR_JOB="$(printf '%s\n' "$JOBS_DIR" | sed -nE 's#^(/home/[^/]+)/es-tb-jobs$#\1#p')"
if [ -z "$HOME_FOR_JOB" ]; then
  HOME_FOR_JOB="$HOME"
fi
ADMIN_ENV="$HOME_FOR_JOB/swebench-bench/.espec-admin.env"

if [ "${ES_TB_MONITOR_AS_OPERATOR:-0}" != "1" ] && [ "$HOME_FOR_JOB" != "$HOME" ]; then
  RUN_USER="$(stat -c %U "$HOME_FOR_JOB" 2>/dev/null || echo "")"
  if [ -n "$RUN_USER" ] && [ "$RUN_USER" != "$(id -un)" ]; then
    exec sudo -H -u "$RUN_USER" env ES_TB_MONITOR_AS_OPERATOR=1 HOME="$HOME_FOR_JOB" JOB_NAME="$JOB" bash "$0"
  fi
fi

echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "job=${JOB:-<none>}"
echo "jobs_dir=$JOBS_DIR"
echo "job_home=$HOME_FOR_JOB"
echo

echo "== app health =="
curl -fsS http://localhost:8080/actuator/health || true
echo

echo "== processes =="
printf '%s\n' "$PROCESS_LINES"
echo

if [ -z "$JOB" ]; then
  echo "No job found."
  exit 0
fi

JOB_DIR="$JOBS_DIR/$JOB"
RUN_LOG="$JOBS_DIR/${JOB}-run.log"
BRIDGE_LOG="$JOBS_DIR/${JOB}-bridge.log"
PIDS="$JOBS_DIR/${JOB}-pids.txt"

echo "== files =="
[ -f "$PIDS" ] && cat "$PIDS" || echo "no pids file"
[ -d "$JOB_DIR" ] && echo "job_dir_present=yes" || echo "job_dir_present=no"
[ -f "$JOB_DIR/result.json" ] && echo "harbor_root_result=yes" || echo "harbor_root_result=no"
[ -f "$RUN_LOG" ] && echo "run_log=$RUN_LOG"
[ -f "$BRIDGE_LOG" ] && echo "bridge_log=$BRIDGE_LOG"
echo

echo "== harness homes =="
for home in "$HOME" /home/*; do
  [ -d "$home" ] || continue
  user="$(stat -c %U "$home" 2>/dev/null || echo unknown)"
  harbor="no"
  admin_env="no"
  [ -x "$home/tbench/.venv/bin/harbor" ] && harbor="yes"
  [ -f "$home/swebench-bench/.espec-admin.env" ] && admin_env="yes"
  if [ -d "$home/tbench" ] || [ -d "$home/swebench-bench" ] || [ "$harbor" = "yes" ] || [ "$admin_env" = "yes" ]; then
    echo "home=$home user=$user tbench=$([ -d "$home/tbench" ] && echo yes || echo no) harbor=$harbor admin_env=$admin_env"
  fi
done
echo

echo "== bridge markers =="
if [ -f "$BRIDGE_LOG" ]; then
  grep -E 'benchmark_run id|RUN_DONE|TASK_SCORED|error|failed|Traceback' "$BRIDGE_LOG" | tail -80 || true
else
  echo "no bridge log"
fi
echo

echo "== harbor summary =="
python3 - "$JOB_DIR" <<'PY'
import glob, json, os, sys

job_dir = sys.argv[1]
if not os.path.isdir(job_dir):
    print("job dir missing")
    raise SystemExit

root = os.path.join(job_dir, "result.json")
result_files = [
    p for p in glob.glob(os.path.join(job_dir, "**", "result.json"), recursive=True)
    if os.path.abspath(p) != os.path.abspath(root)
]
rows = []
for path in sorted(result_files):
    try:
        with open(path) as f:
            d = json.load(f)
    except Exception as e:
        rows.append((os.path.relpath(path, job_dir), "parse_error", "", str(e)[:120]))
        continue
    task = d.get("task_id") or d.get("task_name") or d.get("name") or os.path.dirname(os.path.relpath(path, job_dir))
    if isinstance(task, dict):
        task = task.get("name") or task.get("task_name") or str(task)
    reward = d.get("reward")
    verifier = d.get("verifier_result") or {}
    verifier_rewards = verifier.get("rewards") or {}
    if reward is None:
        reward = verifier_rewards.get("reward")
    agent = d.get("agent_result") or {}
    meta = d.get("metadata") or agent.get("metadata") or {}
    es_run = meta.get("es_run_id") or meta.get("run_id") or ""
    status = (
        d.get("status")
        or d.get("error")
        or agent.get("status")
        or agent.get("error")
        or agent.get("exception")
        or ""
    )
    if not status and agent.get("failure_mode"):
        status = agent.get("failure_mode")
    rows.append((task, reward, es_run, status))

resolved = sum(1 for _, r, *_ in rows if isinstance(r, (int, float)) and float(r) > 0)
rewards = [r for _, r, *_ in rows if isinstance(r, (int, float))]
avg = (sum(rewards) / len(rewards)) if rewards else 0
print(f"trial_results={len(rows)} resolved={resolved} avg_reward={avg:.3f}")
print("task\treward\tes_run_id\tstatus")
for task, reward, es_run, status in rows:
    print(f"{task}\t{reward}\t{es_run}\t{status}")
if os.path.exists(root):
    try:
        with open(root) as f:
            root_d = json.load(f)
        print("root_result_keys=" + ",".join(sorted(root_d.keys())[:20]))
        if "accuracy" in root_d:
            print("root_accuracy=" + str(root_d["accuracy"]))
    except Exception as e:
        print("root_parse_error=" + str(e))
PY
echo

echo "== app benchmark tracking =="
if [ -f "$ADMIN_ENV" ]; then
  set +u
  . "$ADMIN_ENV"
  set -u
  python3 - "$BRIDGE_LOG" <<'PY'
import json, os, re, sys, urllib.request

bridge_log = sys.argv[1]
base = "http://localhost:8080"
email = os.environ.get("ESPEC_ADMIN_EMAIL", "")
pw = os.environ.get("ESPEC_ADMIN_PW", "")
if not email or not pw:
    print("admin env missing email/password")
    raise SystemExit

def req(path, token=None, body=None):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(
        base + path,
        data=data,
        headers=headers,
        method="POST" if body is not None else "GET",
    )
    with urllib.request.urlopen(r, timeout=20) as resp:
        raw = resp.read().decode()
    return json.loads(raw) if raw.strip() else None

try:
    token = req("/api/auth/login", body={"email": email, "password": pw})["accessToken"]
    runs = req("/api/v2/test/benchmark", token)
except Exception as e:
    print("api_query_failed=" + repr(e))
    raise SystemExit

rid = None
if os.path.exists(bridge_log):
    txt = open(bridge_log, errors="ignore").read()
    m = re.findall(r"benchmark_run id = ([a-f0-9-]+)", txt)
    if m:
        rid = m[-1]
if not rid and runs:
    rid = runs[0].get("id")

print("latest_runs:")
for r in (runs or [])[:5]:
    print(f"{r.get('id')}\t{r.get('harborJobName')}\t{r.get('status')}\t{r.get('resolvedTasks')}/{r.get('totalTasks')}\tcost={r.get('costUsd')}")

if rid:
    d = req(f"/api/v2/test/benchmark/{rid}", token)
    run = d.get("run", {})
    tasks = d.get("tasks", [])
    events = d.get("events", [])
    print(f"detail_run={rid} job={run.get('harborJobName')} status={run.get('status')} resolved={run.get('resolvedTasks')}/{run.get('totalTasks')} cost={run.get('costUsd')} events={len(events)} tasks={len(tasks)}")
    for t in tasks:
        print(f"task={t.get('taskName')} attempt={t.get('attempt')} reward={t.get('reward')} esRunId={t.get('esRunId')} cost={t.get('costUsd')}")
PY
else
  echo "admin env missing"
fi
echo

echo "== run log tail =="
[ -f "$RUN_LOG" ] && tail -80 "$RUN_LOG" || echo "no run log"
