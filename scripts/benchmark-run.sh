#!/usr/bin/env bash
#
# ExecuteSpec Production Readiness Benchmark — 9 Sequential Runs
#
# Runs the benchmark matrix defined in BENCHMARK_CONTEXT.md (2026-04-11).
# Providers: Anthropic + OpenAI only (Google skipped, API unstable).
# Mode: PROTOTYPE (skipApproval=true)
#
# Usage:
#   ./scripts/benchmark-run.sh               # Run all 9 sequentially
#   ./scripts/benchmark-run.sh report        # Regenerate report from existing JSON
#   BASE_URL=http://localhost:8080 ./scripts/benchmark-run.sh
#
# Prerequisites:
#   - Server running locally: JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run
#   - jq installed
#   - E2E_EMAIL / E2E_PASSWORD env vars (or defaults below)
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${E2E_EMAIL:-admin@executespec.ai}"
PASSWORD="${E2E_PASSWORD:-REDACTED_FOR_PUBLIC_RELEASE}"
POLL_INTERVAL=10
REPORT_WAIT=180      # seconds to wait for validation report after COMPLETE

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_FILE="$SCRIPT_DIR/benchmark-results.json"
REPORT_FILE="$SCRIPT_DIR/benchmark-report.md"

TOKEN=""

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*" >&2; }
ok()   { echo -e "${GREEN}  ✅ $*${NC}" >&2; }
fail() { echo -e "${RED}  ❌ $*${NC}" >&2; }
warn() { echo -e "${YELLOW}  ⚠️  $*${NC}" >&2; }
hdr()  { echo -e "\n${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n  $*\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n" >&2; }

# ── Benchmark run matrix (9 runs, sequential) ──────────────────────────────
#
# Format: SPEC_INDEX|PROVIDER
# Spec indexes: 0=HTML  1=Java  2=TypeScript  3=Python  4=React
#
RUNS=(
    "0|OPENAI"       # Run 1: HTML/OpenAI
    "1|ANTHROPIC"    # Run 2: Java/Anthropic
    "1|OPENAI"       # Run 3: Java/OpenAI
    "2|ANTHROPIC"    # Run 4: TypeScript/Anthropic
    "2|OPENAI"       # Run 5: TypeScript/OpenAI
    "3|ANTHROPIC"    # Run 6: Python/Anthropic
    "3|OPENAI"       # Run 7: Python/OpenAI
    "4|ANTHROPIC"    # Run 8: React/Anthropic
    "4|OPENAI"       # Run 9: React/OpenAI
)

SPEC_NAMES=("html-todo" "java-task-api" "ts-task-api" "python-task-api" "react-task-tracker")
SPEC_LABELS=("Static HTML Todo App" "Task Tracker REST API (Java/Spring Boot)" "Task Tracker REST API (TypeScript/Express)" "Task Tracker REST API (Python/FastAPI)" "Task Tracker Frontend (React/Vite/Tailwind)")
SPEC_TIERS=("single_shot" "multi_task" "multi_task" "multi_task" "multi_task")
SPEC_TIMEOUTS=(600 1200 1200 1200 1200)  # seconds; 20 min for complex backend specs

# Prompts from BENCHMARK_CONTEXT.md
read -r -d '' PROMPT_0 << 'SPEC' || true
Build a beautiful static HTML/CSS/JS todo app with NO backend. Single index.html file. Features: add task, mark complete with strikethrough, delete task, task counter. Clean minimal design with soft shadows, rounded cards, blue/white palette, smooth CSS transitions, LocalStorage persistence.
SPEC

read -r -d '' PROMPT_1 << 'SPEC' || true
Build a Java Spring Boot REST API task tracker. Features: CRUD for tasks (title, description, status: TODO/IN_PROGRESS/DONE, priority: LOW/MEDIUM/HIGH, dueDate), list tasks filterable by status and priority, mark task complete endpoint, overdue task detection (flag tasks past dueDate). Spring Data JPA with H2 in-memory DB, schema.sql included. Port 8080.
SPEC

read -r -d '' PROMPT_2 << 'SPEC' || true
Build a TypeScript Node.js Express REST API task tracker. Features: CRUD for tasks (title, description, status: todo/in_progress/done, priority: low/medium/high, dueDate), list tasks filterable by status and priority, mark task complete endpoint, overdue task detection. sqlite3 for persistence, migration script included. Port 3000.
SPEC

read -r -d '' PROMPT_3 << 'SPEC' || true
Build a Python FastAPI task tracker REST API. Features: CRUD for tasks (title, description, status: todo/in_progress/done, priority: low/medium/high, due_date), list tasks filterable by status and priority, mark task complete endpoint, overdue task detection. SQLite with SQLAlchemy ORM. Port 8000.
SPEC

read -r -d '' PROMPT_4 << 'SPEC' || true
Build a React TypeScript task tracker frontend. Kanban board with TODO/IN_PROGRESS/DONE columns, add task (title, priority: low/medium/high, dueDate), drag tasks between columns, mark complete, delete, filter by priority. LocalStorage persistence, no backend. Vite + Tailwind CSS, clean minimal design.
SPEC

SPEC_PROMPTS=("$PROMPT_0" "$PROMPT_1" "$PROMPT_2" "$PROMPT_3" "$PROMPT_4")

# ── Auth ───────────────────────────────────────────────────────────────────

auth_login() {
    local resp
    resp=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" 2>&1)

    TOKEN=$(echo "$resp" | jq -r '.accessToken // empty')
    if [[ -z "$TOKEN" ]]; then
        echo -e "${RED}Auth failed. Is the server running at $BASE_URL?${NC}" >&2
        echo -e "${RED}Response: $resp${NC}" >&2
        exit 1
    fi
    log "Authenticated as $EMAIL"
}

# ── API helpers ────────────────────────────────────────────────────────────

submit_run() {
    local prompt="$1"
    local provider="$2"

    local body
    body=$(jq -n \
        --arg req "$prompt" \
        --arg prov "$provider" \
        '{userRequest: $req, skipApproval: true, executionMode: "PROTOTYPE", aiProvider: $prov}')

    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$body")

    local http_code
    http_code=$(echo "$resp" | tail -1)
    local body_resp
    body_resp=$(echo "$resp" | sed '$d')

    if [[ "$http_code" != "202" && "$http_code" != "200" ]]; then
        echo "SUBMIT_FAILED|$http_code|$body_resp"
        return 1
    fi

    local run_id
    run_id=$(echo "$body_resp" | jq -r '.runId // empty')
    if [[ -z "$run_id" ]]; then
        echo "SUBMIT_FAILED|$http_code|no runId"
        return 1
    fi
    echo "$run_id"
}

get_run() {
    curl -s "$BASE_URL/api/runs/$1" \
        -H "Authorization: Bearer $TOKEN"
}

approve_run() {
    curl -s -X POST "$BASE_URL/api/runs/$1/approve" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"approved":true}' > /dev/null 2>&1 || true
}

poll_until_terminal() {
    local run_id="$1"
    local timeout="$2"
    local start_time
    start_time=$(date +%s)
    local last_status=""

    while true; do
        local elapsed=$(( $(date +%s) - start_time ))
        if (( elapsed > timeout )); then
            echo "TIMEOUT|$elapsed"
            return 1
        fi

        local detail
        detail=$(get_run "$run_id")
        local status
        status=$(echo "$detail" | jq -r '.status // "UNKNOWN"')

        if [[ "$status" != "$last_status" ]]; then
            log "    $run_id: $status (${elapsed}s)"
            last_status="$status"
        fi

        case "$status" in
            COMPLETE|PARTIAL|FAILED|CANCELLED|REJECTED)
                echo "$status|$elapsed"
                return 0
                ;;
            AWAITING_APPROVAL)
                log "    Auto-approving plan gate..."
                approve_run "$run_id"
                sleep 3
                ;;
            *)
                sleep $POLL_INTERVAL
                ;;
        esac
    done
}

collect_metrics() {
    local run_id="$1"
    local detail
    detail=$(get_run "$run_id")

    echo "$detail" | jq '{
        status: (.status // "UNKNOWN"),
        failureReason: (.failureReason // null),
        totalInputTokens: (.totalInputTokens // 0),
        totalOutputTokens: (.totalOutputTokens // 0),
        actualCostUsd: (.actualCostUsd // null),
        costEstimateLow: (.costEstimateLow // null),
        costEstimateHigh: (.costEstimateHigh // null),
        creditsCharged: (.creditsCharged // 1),
        plannerModel: (.plannerModel // null),
        workerModel: (.workerModel // null),
        reviewerModel: (.reviewerModel // null),
        aiProvider: (.aiProvider // null),
        startedAt: (.startedAt // null),
        completedAt: (.completedAt // null),
        techStack: (.techStack // null),
        runTitle: (.runTitle // null),
        executionMode: (.executionMode // null),
        hasFrontend: (.hasFrontend // false),
        previewStatus: (.previewStatus // null),
        tasksTotal: ((.tasks // []) | length),
        tasksCompleted: ([(.tasks // [])[] | select(.status == "COMPLETE")] | length),
        tasksFailed: ([(.tasks // [])[] | select(.status == "FAILED")] | length),
        tasksPartial: ([(.tasks // [])[] | select(.status == "PARTIAL")] | length),
        maxIterations: ([(.tasks // [])[] | .iterationCount // 0] | max // 0),
        avgIterations: (if ((.tasks // []) | length) > 0 then ([(.tasks // [])[] | .iterationCount // 0] | add / length) else 0 end),
        totalRetries: ([(.tasks // [])[] | .retryCount // 0] | add // 0),
        plannerInputTokens: (.plannerInputTokens // 0),
        plannerOutputTokens: (.plannerOutputTokens // 0),
        reviewerInputTokens: (.reviewerInputTokens // 0),
        reviewerOutputTokens: (.reviewerOutputTokens // 0),
        compilerInputTokens: (.compilerInputTokens // 0),
        compilerOutputTokens: (.compilerOutputTokens // 0)
    }'
}

wait_for_report() {
    local run_id="$1"
    local max_wait="${2:-$REPORT_WAIT}"
    local start_time
    start_time=$(date +%s)

    log "    Waiting for validation report (up to ${max_wait}s)..."

    while true; do
        local elapsed=$(( $(date +%s) - start_time ))
        if (( elapsed > max_wait )); then
            echo "{}"
            return 1
        fi

        local resp
        resp=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/runs/$run_id/report" \
            -H "Authorization: Bearer $TOKEN")
        local http_code
        http_code=$(echo "$resp" | tail -1)
        local body
        body=$(echo "$resp" | sed '$d')

        if [[ "$http_code" == "200" ]]; then
            echo "$body" | jq '{
                pipelineResult: (.pipelineResult // null),
                filesGenerated: (.filesGenerated // 0),
                languages: (.languages // null),
                checksPassed: (.checksPassed // []),
                generationTimeMs: (.generationTimeMs // null),
                issuesJson: (.issuesJson // "[]"),
                specAdherenceScore: (.specAdherenceScore // null),
                endpointCoverage: (.endpointCoverage // null),
                schemaAccuracy: (.schemaAccuracy // null),
                testCoverage: (.testCoverage // null),
                missingRequirements: (.missingRequirements // []),
                partialRequirements: (.partialRequirements // []),
                autoFixSucceeded: (.autoFixSucceeded // false),
                reviewVerdict: (.reviewVerdict // null),
                reportInputTokens: (.inputTokens // 0),
                reportOutputTokens: (.outputTokens // 0)
            }'
            return 0
        fi

        sleep 5
    done
}

# ── Run a single benchmark run ─────────────────────────────────────────────

run_benchmark() {
    local run_num="$1"
    local spec_idx="$2"
    local provider="$3"

    local spec_name="${SPEC_NAMES[$spec_idx]}"
    local spec_label="${SPEC_LABELS[$spec_idx]}"
    local timeout="${SPEC_TIMEOUTS[$spec_idx]}"
    local prompt="${SPEC_PROMPTS[$spec_idx]}"

    hdr "Run $run_num/9: $spec_label | $provider"

    # Re-auth before each run to avoid JWT expiry
    auth_login 2>/dev/null

    # 1. Submit
    log "Submitting run..."
    local run_id
    run_id=$(submit_run "$prompt" "$provider") || true

    if [[ "$run_id" == SUBMIT_FAILED* ]]; then
        fail "Submit failed: $run_id"
        save_result "$run_num" "$spec_idx" "$provider" "SUBMIT_FAILED" "" 0 "{}" "{}"
        return
    fi
    ok "Submitted: $run_id"
    log "Monitor: $BASE_URL/runs/$run_id"

    # 2. Poll
    local result
    result=$(poll_until_terminal "$run_id" "$timeout") || true
    local status
    status=$(echo "$result" | cut -d'|' -f1)
    local duration
    duration=$(echo "$result" | cut -d'|' -f2)

    if [[ "$status" == "TIMEOUT" ]]; then
        fail "Timed out after ${duration}s"
        curl -s -X DELETE "$BASE_URL/api/runs/$run_id" \
            -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1 || true
        save_result "$run_num" "$spec_idx" "$provider" "TIMEOUT" "$run_id" "$duration" "{}" "{}"
        return
    fi

    # 3. Collect metrics
    log "Collecting metrics..."
    local metrics
    metrics=$(collect_metrics "$run_id")

    # 4. Wait for validation report (only on COMPLETE)
    local report_data="{}"
    if [[ "$status" == "COMPLETE" ]]; then
        report_data=$(wait_for_report "$run_id") || report_data="{}"
    fi

    # 5. Log result
    local actual_cost
    actual_cost=$(echo "$metrics" | jq -r '.actualCostUsd // "N/A"')
    local tasks_done
    tasks_done=$(echo "$metrics" | jq -r '.tasksCompleted')
    local tasks_total
    tasks_total=$(echo "$metrics" | jq -r '.tasksTotal')
    local total_in
    total_in=$(echo "$metrics" | jq -r '.totalInputTokens')
    local total_out
    total_out=$(echo "$metrics" | jq -r '.totalOutputTokens')
    local max_iter
    max_iter=$(echo "$metrics" | jq -r '.maxIterations')
    local pipeline_result
    pipeline_result=$(echo "$report_data" | jq -r '.pipelineResult // "N/A"')
    local adherence_score
    adherence_score=$(echo "$report_data" | jq -r '.specAdherenceScore // "N/A"')

    case "$status" in
        COMPLETE)
            ok "$status — tasks:$tasks_done/$tasks_total  duration:${duration}s  cost:\$$actual_cost  tokens:${total_in}in/${total_out}out  max_iter:$max_iter  pipeline:$pipeline_result  adherence:$adherence_score"
            ;;
        PARTIAL)
            warn "$status — tasks:$tasks_done/$tasks_total  duration:${duration}s  cost:\$$actual_cost"
            ;;
        FAILED)
            local reason
            reason=$(echo "$metrics" | jq -r '.failureReason // "unknown"')
            fail "$status — duration:${duration}s  reason:$reason"
            ;;
        *)
            fail "$status — duration:${duration}s"
            ;;
    esac

    # 6. Save
    save_result "$run_num" "$spec_idx" "$provider" "$status" "$run_id" "$duration" "$metrics" "$report_data"
}

# ── Results storage ────────────────────────────────────────────────────────

save_result() {
    local run_num="$1"
    local spec_idx="$2"
    local provider="$3"
    local status="$4"
    local run_id="$5"
    local duration="$6"
    local metrics="$7"
    local report_data="$8"

    local spec_name="${SPEC_NAMES[$spec_idx]}"
    local spec_label="${SPEC_LABELS[$spec_idx]}"
    local spec_tier="${SPEC_TIERS[$spec_idx]}"

    local entry
    entry=$(jq -n \
        --argjson runNum "$run_num" \
        --argjson specIndex "$spec_idx" \
        --arg specName "$spec_name" \
        --arg specLabel "$spec_label" \
        --arg specTier "$spec_tier" \
        --arg provider "$provider" \
        --arg runId "$run_id" \
        --arg status "$status" \
        --argjson duration "$duration" \
        --argjson metrics "${metrics:-{\}}" \
        --argjson report "${report_data:-{\}}" \
        '{
            runNumber: $runNum,
            specIndex: $specIndex,
            specName: $specName,
            specLabel: $specLabel,
            specTier: $specTier,
            provider: $provider,
            runId: $runId,
            status: $status,
            durationSeconds: $duration,
            totalInputTokens: ($metrics.totalInputTokens // 0),
            totalOutputTokens: ($metrics.totalOutputTokens // 0),
            actualCostUsd: ($metrics.actualCostUsd // null),
            creditsCharged: ($metrics.creditsCharged // 1),
            plannerModel: ($metrics.plannerModel // null),
            workerModel: ($metrics.workerModel // null),
            reviewerModel: ($metrics.reviewerModel // null),
            tasksTotal: ($metrics.tasksTotal // 0),
            tasksCompleted: ($metrics.tasksCompleted // 0),
            tasksFailed: ($metrics.tasksFailed // 0),
            tasksPartial: ($metrics.tasksPartial // 0),
            maxIterations: ($metrics.maxIterations // 0),
            avgIterations: ($metrics.avgIterations // 0),
            totalRetries: ($metrics.totalRetries // 0),
            failureReason: ($metrics.failureReason // null),
            previewStatus: ($metrics.previewStatus // null),
            plannerInputTokens: ($metrics.plannerInputTokens // 0),
            plannerOutputTokens: ($metrics.plannerOutputTokens // 0),
            reviewerInputTokens: ($metrics.reviewerInputTokens // 0),
            reviewerOutputTokens: ($metrics.reviewerOutputTokens // 0),
            compilerInputTokens: ($metrics.compilerInputTokens // 0),
            compilerOutputTokens: ($metrics.compilerOutputTokens // 0),
            pipelineResult: ($report.pipelineResult // null),
            filesGenerated: ($report.filesGenerated // 0),
            checksPassed: ($report.checksPassed // []),
            compilationPassed: (($report.checksPassed // []) | if type == "array" then any(. == "COMPILATION") else false end),
            reportAvailable: (($report.pipelineResult // null) != null),
            specAdherenceScore: ($report.specAdherenceScore // null),
            schemaAccuracy: ($report.schemaAccuracy // null),
            testCoverage: ($report.testCoverage // null),
            reviewVerdict: ($report.reviewVerdict // null),
            autoFixSucceeded: ($report.autoFixSucceeded // false),
            missingRequirements: ($report.missingRequirements // []),
            partialRequirements: ($report.partialRequirements // []),
            timestamp: (now | todate)
        }')

    # Read existing, remove any duplicate for same run number, append new
    local current='[]'
    if [[ -f "$RESULTS_FILE" ]]; then
        current=$(cat "$RESULTS_FILE")
    fi
    current=$(echo "$current" | jq --argjson rn "$run_num" '[.[] | select(.runNumber != $rn)]')
    echo "$current" | jq ". + [$entry]" > "$RESULTS_FILE"
    log "Saved result for run $run_num ($spec_label / $provider)"
}

# ── Score a run 0–100 across 5 dimensions (20 pts each) ───────────────────
#
# Rubric from BENCHMARK_CONTEXT.md §7:
#   Completeness:   all features=20, 1-2 minor missing=15, several=10, core absent=0
#   Correctness:    compile+runs+correct=20, minor gaps=15, major=10, fails=0
#   Spec Adherence: ≥90=20, 75–89=15, 50–74=10, <50=0
#   Reliability:    COMPLETE 0 retries=20, COMPLETE w/retry=15, PARTIAL=10, FAILED=0
#   Cost Efficiency:≤$0.10=20, $0.10-0.25=15, $0.25-0.50=10, >$0.50=0
#
# Note: Completeness and Correctness require manual assessment; this script
# auto-scores Spec Adherence, Reliability, and Cost Efficiency and leaves
# Completeness/Correctness as "MANUAL" placeholders in the report.

score_reliability() {
    local status="$1"
    local retries="$2"
    case "$status" in
        COMPLETE)
            if [[ "$retries" -eq 0 ]]; then echo 20
            else echo 15
            fi
            ;;
        PARTIAL) echo 10 ;;
        *) echo 0 ;;
    esac
}

score_adherence() {
    local score="$1"
    if [[ "$score" == "null" || "$score" == "N/A" ]]; then echo "N/A"; return; fi
    local s
    s=$(echo "$score" | jq -r '. | floor' 2>/dev/null || echo 0)
    if   (( s >= 90 )); then echo 20
    elif (( s >= 75 )); then echo 15
    elif (( s >= 50 )); then echo 10
    else echo 0
    fi
}

score_cost() {
    local cost="$1"
    if [[ "$cost" == "null" || "$cost" == "N/A" ]]; then echo "N/A"; return; fi
    local c
    c=$(echo "$cost" | jq -r '.' 2>/dev/null || echo 0)
    if   (( $(echo "$c <= 0.10" | bc -l) )); then echo 20
    elif (( $(echo "$c <= 0.25" | bc -l) )); then echo 15
    elif (( $(echo "$c <= 0.50" | bc -l) )); then echo 10
    else echo 0
    fi
}

# ── Report generation ──────────────────────────────────────────────────────

generate_report() {
    if [[ ! -f "$RESULTS_FILE" ]]; then
        echo "No results at $RESULTS_FILE" >&2; return 1
    fi

    local total
    total=$(jq length "$RESULTS_FILE")
    if [[ "$total" -eq 0 ]]; then echo "Results file is empty" >&2; return 1; fi

    local completed partial failed timeout total_cost total_input total_output
    completed=$(jq '[.[] | select(.status == "COMPLETE")] | length' "$RESULTS_FILE")
    partial=$(jq '[.[] | select(.status == "PARTIAL")] | length' "$RESULTS_FILE")
    failed=$(jq '[.[] | select(.status == "FAILED" or .status == "SUBMIT_FAILED")] | length' "$RESULTS_FILE")
    timeout=$(jq '[.[] | select(.status == "TIMEOUT")] | length' "$RESULTS_FILE")
    total_cost=$(jq '[.[].actualCostUsd // 0] | add' "$RESULTS_FILE")
    total_input=$(jq '[.[].totalInputTokens // 0] | add' "$RESULTS_FILE")
    total_output=$(jq '[.[].totalOutputTokens // 0] | add' "$RESULTS_FILE")

    local avg_adherence
    avg_adherence=$(jq '[.[] | select(.specAdherenceScore != null) | .specAdherenceScore] | if length > 0 then add / length else null end' "$RESULTS_FILE")

    local compile_passed
    compile_passed=$(jq '[.[] | select(.compilationPassed == true)] | length' "$RESULTS_FILE")

    local task_complete_rate
    task_complete_rate=$(jq '
        ([ .[] | .tasksCompleted ] | add) as $done |
        ([ .[] | .tasksTotal ] | add) as $total |
        if $total > 0 then ($done * 100 / $total | floor) else 0 end
    ' "$RESULTS_FILE")

    local avg_cost
    avg_cost=$(jq '[ .[].actualCostUsd // 0 ] | add / length' "$RESULTS_FILE")

    local avg_duration
    avg_duration=$(jq '[.[].durationSeconds // 0] | add / length | floor' "$RESULTS_FILE")

    {
        echo "# ExecuteSpec Production Readiness Benchmark Report"
        echo ""
        echo "**Date:** $(date '+%Y-%m-%d %H:%M %Z')"
        echo "**Branch:** dev (af82023)"
        echo "**Environment:** $BASE_URL (local)"
        echo "**Providers:** Anthropic + OpenAI (Google skipped)"
        echo "**Mode:** PROTOTYPE · skipApproval=true"
        echo "**Runs:** $total / 9"
        echo ""
        echo "---"
        echo ""

        # ── Executive Summary ──────────────────────────────────────────────
        echo "## 1. Executive Summary"
        echo ""
        echo "| Metric | Value | Go/No-Go Gate |"
        echo "|--------|-------|--------------|"
        echo "| Total runs | $total | — |"
        local success_pct=$(( completed * 100 / (total > 0 ? total : 1) ))
        echo "| COMPLETE | $completed ($success_pct%) | ≥85% |"
        echo "| PARTIAL | $partial | ≤20% |"
        echo "| TIMEOUT | $timeout | — |"
        echo "| FAILED | $failed | ≥85% success |"
        echo "| Compile pass rate | $compile_passed/$total | ≥95% |"
        echo "| Task COMPLETE rate | ${task_complete_rate}% | ≥90% |"
        echo "| Avg spec adherence | $avg_adherence / 100 | ≥75 avg, ≥85 p90 |"
        echo "| Avg cost/run | \$$avg_cost | ≤\$0.25 |"
        echo "| Avg wall-clock time | ${avg_duration}s | ≤480s (8 min) |"
        echo "| Total cost | \$$total_cost | — |"
        echo "| Total tokens | ${total_input} in / ${total_output} out | — |"
        echo ""
        echo "---"
        echo ""

        # ── Per-Run Results ────────────────────────────────────────────────
        echo "## 2. Per-Run Results"
        echo ""
        echo "| # | Spec | Provider | Status | Duration | Cost | Tokens (in/out) | Tasks | Retries | Max Iter | Adherence | Compile | Pipeline |"
        echo "|---|------|----------|--------|----------|------|----------------|-------|---------|----------|-----------|---------|----------|"

        jq -r 'sort_by(.runNumber) | .[] |
            "| \(.runNumber) | \(.specLabel) | \(.provider) | \(.status) | \(.durationSeconds)s | $\(.actualCostUsd // "N/A") | \(.totalInputTokens)/\(.totalOutputTokens) | \(.tasksCompleted)/\(.tasksTotal) | \(.totalRetries) | \(.maxIterations) | \(.specAdherenceScore // "N/A") | \(if .compilationPassed then "PASS" else "FAIL" end) | \(.pipelineResult // "N/A") |"' \
            "$RESULTS_FILE"

        echo ""
        echo "---"
        echo ""

        # ── Provider Comparison ────────────────────────────────────────────
        echo "## 3. Provider Comparison (Anthropic vs OpenAI)"
        echo ""
        echo "| Metric | ANTHROPIC | OPENAI |"
        echo "|--------|-----------|--------|"

        for p in ANTHROPIC OPENAI; do
            local count
            count=$(jq --arg p "$p" '[.[] | select(.provider == $p)] | length' "$RESULTS_FILE")
            if [[ "$count" -eq 0 ]]; then continue; fi
        done

        local metrics_pairs=(
            "COMPLETE runs|[.[] | select(.provider == \"PROV\" and .status == \"COMPLETE\")] | length"
            "Success rate|([.[] | select(.provider == \"PROV\" and .status == \"COMPLETE\")] | length) as \$c | ([.[] | select(.provider == \"PROV\")] | length) as \$t | if \$t > 0 then (\$c * 100 / \$t | floor | tostring) + \"%\" else \"N/A\" end"
            "Avg duration (s)|[.[] | select(.provider == \"PROV\")] | [.[].durationSeconds // 0] | add / length | floor"
            "Total cost|[.[] | select(.provider == \"PROV\") | .actualCostUsd // 0] | add"
            "Avg cost/run|[.[] | select(.provider == \"PROV\") | .actualCostUsd // 0] | add / ([.[] | select(.provider == \"PROV\")] | length)"
            "Total input tokens|[.[] | select(.provider == \"PROV\") | .totalInputTokens // 0] | add"
            "Total output tokens|[.[] | select(.provider == \"PROV\") | .totalOutputTokens // 0] | add"
            "Task complete rate|([.[] | select(.provider == \"PROV\") | .tasksCompleted // 0] | add) as \$d | ([.[] | select(.provider == \"PROV\") | .tasksTotal // 0] | add) as \$t | if \$t > 0 then (\$d * 100 / \$t | floor | tostring) + \"%\" else \"N/A\" end"
            "Compile passes|[.[] | select(.provider == \"PROV\" and .compilationPassed == true)] | length"
            "Avg spec adherence|[.[] | select(.provider == \"PROV\" and .specAdherenceScore != null) | .specAdherenceScore] | if length > 0 then add / length | floor else null end"
            "Total retries|[.[] | select(.provider == \"PROV\") | .totalRetries // 0] | add"
        )

        for pair in "${metrics_pairs[@]}"; do
            local label="${pair%%|*}"
            local query="${pair#*|}"
            local val_a val_o
            val_a=$(jq "$(echo "$query" | sed 's/PROV/ANTHROPIC/g')" "$RESULTS_FILE" 2>/dev/null || echo "N/A")
            val_o=$(jq "$(echo "$query" | sed 's/PROV/OPENAI/g')" "$RESULTS_FILE" 2>/dev/null || echo "N/A")
            echo "| $label | $val_a | $val_o |"
        done

        echo ""
        echo "---"
        echo ""

        # ── Per-Spec Breakdown ─────────────────────────────────────────────
        echo "## 4. Per-Spec Breakdown"
        echo ""

        for i in 0 1 2 3 4; do
            local spec_label="${SPEC_LABELS[$i]}"
            local spec_tier="${SPEC_TIERS[$i]}"
            echo "### Spec $((i+1)): $spec_label (\`$spec_tier\`)"
            echo ""
            echo "| Provider | Status | Duration | Cost | Tokens (in/out) | Tasks | Adherence | Compile |"
            echo "|----------|--------|----------|------|----------------|-------|-----------|---------|"

            jq -r --argjson idx "$i" 'def fmt_cost: if . != null then ("$" + tostring) else "N/A" end;
                def fmt_score: if . != null then tostring else "N/A" end;
                [.[] | select(.specIndex == $idx)] | sort_by(.provider) | .[] |
                "| \(.provider) | \(.status) | \(.durationSeconds)s | \(.actualCostUsd | fmt_cost) | \(.totalInputTokens)/\(.totalOutputTokens) | \(.tasksCompleted)/\(.tasksTotal) | \(.specAdherenceScore | fmt_score) | \(if .compilationPassed then "PASS" else "FAIL" end) |"' \
                "$RESULTS_FILE"

            echo ""
        done

        echo "---"
        echo ""

        # ── 5-Dimension Scoring Rubric ─────────────────────────────────────
        echo "## 5. 5-Dimension Scoring Rubric"
        echo ""
        echo "> Completeness and Correctness require manual code review."
        echo "> Spec Adherence, Reliability, and Cost Efficiency are auto-scored."
        echo ""
        echo "| # | Spec | Provider | Completeness | Correctness | Spec Adherence | Reliability | Cost Efficiency | Total/100 |"
        echo "|---|------|----------|-------------|-------------|---------------|-------------|----------------|-----------|"

        while IFS= read -r row; do
            local run_num spec_label provider status retries adherence cost
            run_num=$(echo "$row" | jq -r '.runNumber')
            spec_label=$(echo "$row" | jq -r '.specLabel')
            provider=$(echo "$row" | jq -r '.provider')
            status=$(echo "$row" | jq -r '.status')
            retries=$(echo "$row" | jq -r '.totalRetries // 0')
            adherence=$(echo "$row" | jq -r '.specAdherenceScore // "null"')
            cost=$(echo "$row" | jq -r '.actualCostUsd // "null"')

            local rel_score adh_score cost_score
            rel_score=$(score_reliability "$status" "$retries")
            adh_score=$(score_adherence "$adherence")
            cost_score=$(score_cost "$cost")

            local auto_total="N/A"
            if [[ "$adh_score" != "N/A" && "$cost_score" != "N/A" ]]; then
                # Auto-scored dimensions only: reliability + adherence + cost = up to 60
                # Completeness + Correctness = MANUAL (fill in 20+20=40 range later)
                auto_total="$(( rel_score + adh_score + cost_score )) + MANUAL"
            fi

            echo "| $run_num | $spec_label | $provider | MANUAL/20 | MANUAL/20 | ${adh_score}/20 | ${rel_score}/20 | ${cost_score}/20 | $auto_total |"
        done < <(jq -c 'sort_by(.runNumber) | .[]' "$RESULTS_FILE")

        echo ""
        echo "---"
        echo ""

        # ── Go/No-Go Assessment ────────────────────────────────────────────
        echo "## 6. Go/No-Go Assessment"
        echo ""
        echo "| Gate | Threshold | Actual | Status |"
        echo "|------|-----------|--------|--------|"

        local gates_passing=0
        local gates_failing=0

        # Run success rate
        local success_rate=$(( completed * 100 / (total > 0 ? total : 1) ))
        local gate_status="❌ FAIL"
        [[ $success_rate -ge 85 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| Run success rate | ≥85% COMPLETE | ${success_rate}% | $gate_status |"

        # Spec adherence avg
        local adh_avg
        adh_avg=$(jq '[.[] | select(.specAdherenceScore != null) | .specAdherenceScore] | if length > 0 then add / length | floor else 0 end' "$RESULTS_FILE")
        gate_status="❌ FAIL"
        [[ $adh_avg -ge 75 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| Spec adherence (avg) | ≥75 | $adh_avg | $gate_status |"

        # Spec adherence p90 (approximate with max if < 10 runs)
        local adh_p90
        adh_p90=$(jq '[.[] | select(.specAdherenceScore != null) | .specAdherenceScore] | sort | if length >= 10 then .[length * 9 / 10 | floor] else last // 0 end' "$RESULTS_FILE")
        gate_status="❌ FAIL"
        [[ $adh_p90 -ge 85 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| Spec adherence (p90) | ≥85 | $adh_p90 | $gate_status |"

        # PARTIAL rate
        local partial_rate=$(( partial * 100 / (total > 0 ? total : 1) ))
        gate_status="❌ FAIL"
        [[ $partial_rate -le 20 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| PARTIAL rate | ≤20% | ${partial_rate}% | $gate_status |"

        # Task complete rate
        gate_status="❌ FAIL"
        [[ $task_complete_rate -ge 90 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| Task COMPLETE rate | ≥90% | ${task_complete_rate}% | $gate_status |"

        # Avg cost
        local avg_cost_cents
        avg_cost_cents=$(jq '([.[].actualCostUsd // 0] | add / length * 100 | floor)' "$RESULTS_FILE")
        gate_status="❌ FAIL"
        [[ $avg_cost_cents -le 25 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| Avg cost/run | ≤\$0.25 | \$$(echo "scale=4; $avg_cost_cents / 100" | bc) | $gate_status |"

        # Avg wall-clock time
        gate_status="❌ FAIL"
        [[ $avg_duration -le 480 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| Avg wall-clock time | ≤480s (8min) | ${avg_duration}s | $gate_status |"

        # Compile pass rate
        local compile_pct=$(( compile_passed * 100 / (total > 0 ? total : 1) ))
        gate_status="❌ FAIL"
        [[ $compile_pct -ge 95 ]] && gate_status="✅ PASS" && (( gates_passing++ )) || (( gates_failing++ ))
        echo "| Compile pass rate | ≥95% | ${compile_pct}% | $gate_status |"

        echo ""

        local total_gates=$(( gates_passing + gates_failing ))
        if [[ $compile_pct -lt 95 || $success_rate -lt 85 ]]; then
            echo "### ❌ NO GO"
            echo ""
            echo "Compile pass rate or run success rate below threshold. Fix before production."
        elif [[ $gates_failing -eq 0 ]]; then
            echo "### ✅ GO"
            echo ""
            echo "All $total_gates gates pass. Platform is ready for production."
        elif [[ $gates_failing -le 2 ]]; then
            echo "### ⚠️ CONDITIONAL GO"
            echo ""
            echo "$gates_passing/$total_gates gates pass. Deploy with monitoring and fix plan for failing gates."
        else
            echo "### ❌ NO GO"
            echo ""
            echo "$gates_failing/$total_gates gates failing. Do not deploy until issues are resolved."
        fi

        echo ""
        echo "---"
        echo ""

        # ── Failure Analysis ───────────────────────────────────────────────
        echo "## 7. Failure Analysis"
        echo ""

        local fail_count
        fail_count=$(jq '[.[] | select(.status != "COMPLETE")] | length' "$RESULTS_FILE")

        if [[ "$fail_count" -eq 0 ]]; then
            echo "No failures — all runs completed successfully."
        else
            echo "| # | Spec | Provider | Status | Duration | Tasks | Failure Reason |"
            echo "|---|------|----------|--------|----------|-------|---------------|"

            jq -r '[.[] | select(.status != "COMPLETE")] | sort_by(.runNumber) | .[] |
                "| \(.runNumber) | \(.specLabel) | \(.provider) | \(.status) | \(.durationSeconds)s | \(.tasksCompleted)/\(.tasksTotal) | \(.failureReason // "N/A") |"' \
                "$RESULTS_FILE"

            echo ""

            echo "### Missing Requirements (from Validation Reports)"
            echo ""

            jq -r '[.[] | select(.missingRequirements != null and (.missingRequirements | length) > 0)] | .[] |
                "**\(.specLabel) / \(.provider):**\n" + (.missingRequirements | map("- " + .) | join("\n"))' \
                "$RESULTS_FILE" 2>/dev/null || echo "N/A"
        fi

        echo ""
        echo "---"
        echo ""

        # ── Raw Run IDs ────────────────────────────────────────────────────
        echo "## 8. Raw Run IDs"
        echo ""
        echo "| # | Spec | Provider | Run ID | Status |"
        echo "|---|------|----------|--------|--------|"

        jq -r 'sort_by(.runNumber) | .[] |
            "| \(.runNumber) | \(.specLabel) | \(.provider) | \(.runId // "—") | \(.status) |"' \
            "$RESULTS_FILE"

        echo ""
        echo "---"
        echo ""
        echo "*Generated by benchmark-run.sh at $(date '+%Y-%m-%d %H:%M %Z')*"

    } > "$REPORT_FILE"

    ok "Report written → $REPORT_FILE"
}

# ── Pre-flight check ───────────────────────────────────────────────────────

preflight() {
    hdr "Pre-flight Checks"

    # jq
    if ! command -v jq &> /dev/null; then
        fail "jq is not installed. Install with: brew install jq"
        exit 1
    fi
    ok "jq available"

    # Server reachable
    local health
    health=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" 2>/dev/null || echo "000")
    if [[ "$health" != "200" ]]; then
        fail "Server not reachable at $BASE_URL/actuator/health (HTTP $health)"
        echo -e "${YELLOW}  Start with: JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run${NC}" >&2
        exit 1
    fi
    ok "Server reachable at $BASE_URL"

    # Auth
    auth_login
    ok "Authentication successful"

    # Initialize results file
    echo '[]' > "$RESULTS_FILE"
    ok "Results file initialized: $RESULTS_FILE"

    echo ""
    log "Starting 9-run sequential benchmark..."
    log "Specs: HTML/OpenAI | Java×2 | TypeScript×2 | Python×2 | React×2"
    log "Logs:  $SCRIPT_DIR/benchmark-results.json"
    log "Press Ctrl+C to abort (partial results will be saved)"
    echo ""
}

# ── Main ───────────────────────────────────────────────────────────────────

main() {
    case "${1:-run}" in
        report)
            generate_report
            ;;
        run|all|"")
            preflight
            local run_num=0
            for entry in "${RUNS[@]}"; do
                run_num=$(( run_num + 1 ))
                local spec_idx="${entry%%|*}"
                local provider="${entry##*|}"
                run_benchmark "$run_num" "$spec_idx" "$provider"
                echo "" >&2
            done
            hdr "Benchmark Complete — Generating Report"
            generate_report
            echo "" >&2
            ok "All done. Results: $RESULTS_FILE"
            ok "Report:  $REPORT_FILE"
            ;;
        *)
            echo "Usage: $0 [run|report]" >&2
            exit 1
            ;;
    esac
}

main "$@"
