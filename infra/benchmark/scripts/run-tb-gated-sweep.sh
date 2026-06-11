#!/bin/bash
# run-tb-gated-sweep.sh — TB 2.0 GATED overnight sweep (RL-2026-06).
# OPERATOR RULE (do not change without authorization):
#   1. DeepSeek Flash (full-89, k=5, c=8) → submit
#   2. DeepSeek Pro   (full-89, k=5, c=8) → submit
#   3. HARD GATE: proceed to Sonnet ONLY IF Flash resolved% > 50 AND Pro resolved% > 50.
#      Otherwise STOP after Pro and Telegram for approval.
#   4. MiMo is NOT run here — explicit approval required.
# Launched once with nohup; self-contained on the benchmark VM. Telegram throughout.
set -uo pipefail
REPO="${REPO:-$HOME/executespec}"
SCRIPTS="$REPO/infra/benchmark/scripts"
JOBS_DIR="${JOBS_DIR:-$HOME/es-tb-jobs}"
GCS_BUCKET="${GCS_BUCKET:-gs://executespec-benchmark-workspaces/tb}"
TG="${TG:-$HOME/tbench/tg-send.py}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/temurin-21-jdk-amd64}"
SWEEPLOG="$JOBS_DIR/sweep-full.log"
mkdir -p "$JOBS_DIR"
tg(){ python3 "$TG" "$1" >/dev/null 2>&1 || true; echo "$(date '+%F %T') TG | $1" >> "$SWEEPLOG"; }
LOG(){ echo "$(date '+%F %T') | $*" >> "$SWEEPLOG"; }

# resolved% = distinct tasks with >=1 passing attempt, over the full 89-task set
resolved_pct(){ python3 - "$1" <<'PY'
import sys,os,glob,json,re
job=sys.argv[1]; passed=set(); seen=set()
for f in glob.glob(os.path.join(job,"**","result.json"), recursive=True):
    try: d=json.load(open(f))
    except Exception: continue
    if "verifier_result" not in d and "agent_result" not in d: continue
    task=re.sub(r'__[A-Za-z0-9]+$','',os.path.basename(os.path.dirname(f)))
    if not task: continue
    seen.add(task)
    rew=((d.get("verifier_result") or {}).get("rewards") or {}).get("reward",0)
    try: rew=float(rew)
    except Exception: rew=0
    if rew>=1.0: passed.add(task)
print(f"{len(passed)} {round(100.0*len(passed)/89,1)}")
PY
}

run_model(){ # prov es_model harbor_model job submit label
  local prov="$1" model="$2" hmodel="$3" job="$4" submit="$5" label="$6"
  tg "▶️ START $label — full-89, k=5, c=8. Live: https://benchmark.executespec.ai/benchmark"
  LOG "START $label ($prov/$model) job=$job"
  GCS_BUCKET="$GCS_BUCKET" REPO="$REPO" JOBS_DIR="$JOBS_DIR" TG="$TG" \
    ES_PROVIDER="$prov" ES_MODEL="$model" HARBOR_MODEL="$hmodel" \
    K=5 CONCURRENCY=8 JOB_NAME="$job" \
    bash "$SCRIPTS/run-tb.sh" >> "$SWEEPLOG" 2>&1 || LOG "WARN run-tb.sh nonzero for $label"
  local n pct; read -r n pct < <(resolved_pct "$JOBS_DIR/$job")
  echo "${pct:-0}" > "$JOBS_DIR/$job.pct"
  LOG "$label RESULT resolved=${n}/89 (${pct}%)"
  if [ "$submit" = yes ]; then
    if JOB_NAME="$job" bash "$SCRIPTS/submit-tb.sh" >> "$SWEEPLOG" 2>&1; then
      tg "✅ DONE $label — ${n}/89 resolved (${pct}%) · 🏆 submitted to leaderboard"
    else
      tg "✅ DONE $label — ${n}/89 resolved (${pct}%) · ⚠️ submission FAILED (see sweep log)"
    fi
  else
    tg "✅ DONE $label — ${n}/89 resolved (${pct}%)"
  fi
}

tg "🚀 TB 2.0 GATED sweep — Flash → Pro → (gate: BOTH >50% → Sonnet; else HOLD). MiMo held. Dashboard: https://benchmark.executespec.ai/benchmark"

run_model deepseek deepseek-v4-flash-native deepseek/deepseek-v4-flash-native es-tb-flash-k5 yes "DeepSeek V4 Flash"
run_model deepseek deepseek-v4-pro-native   deepseek/deepseek-v4-pro-native   es-tb-pro-k5   yes "DeepSeek V4 Pro"

FP=$(cat "$JOBS_DIR/es-tb-flash-k5.pct" 2>/dev/null || echo 0)
PP=$(cat "$JOBS_DIR/es-tb-pro-k5.pct" 2>/dev/null || echo 0)
GATE=$(python3 -c "f=float('${FP:-0}'); p=float('${PP:-0}'); print('PASS' if f>50 and p>50 else 'HOLD')")
LOG "GATE Flash=${FP}% Pro=${PP}% -> $GATE"

if [ "$GATE" = PASS ]; then
  tg "🟢 GATE PASS — Flash ${FP}% & Pro ${PP}% (both >50%). Auto-proceeding to Sonnet 4.6 per your authorization."
  run_model anthropic claude-sonnet-4-6 anthropic/claude-sonnet-4-6 es-tb-sonnet-k5 yes "Sonnet 4.6"
  tg "🏁 Flash + Pro + Sonnet COMPLETE. MiMo HELD for your approval. Dashboard: https://benchmark.executespec.ai/benchmark"
else
  tg "🟡 GATE HOLD — Flash ${FP}% / Pro ${PP}% (need BOTH >50%). STOPPED after Pro per your rule. Awaiting your approval before Sonnet."
fi
LOG "SWEEP END gate=$GATE"
