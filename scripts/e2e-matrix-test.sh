#!/usr/bin/env bash
#
# ExecuteSpec E2E Matrix Test — 5 Specs x 3 Providers
#
# Usage:
#   ./scripts/e2e-matrix-test.sh all                # Run all 15 + generate reports
#   ./scripts/e2e-matrix-test.sh anthropic           # Run 5 for one provider
#   ./scripts/e2e-matrix-test.sh google              # Run 5 for one provider
#   ./scripts/e2e-matrix-test.sh openai              # Run 5 for one provider
#   ./scripts/e2e-matrix-test.sh report              # Regenerate reports from existing JSON
#   ./scripts/e2e-matrix-test.sh devils-advocate      # Regenerate devil's advocate from existing JSON
#
set -euo pipefail

BASE_URL="${BASE_URL:-https://dev.executespec.ai}"
EMAIL="${E2E_EMAIL:-admin@executespec.ai}"
PASSWORD="${E2E_PASSWORD:-Admin@123}"
POLL_INTERVAL=10
REPORT_WAIT=120      # seconds to wait for validation report

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_FILE="$SCRIPT_DIR/e2e-matrix-results.json"   # combined (merged at report time)
RESULTS_PREFIX="$SCRIPT_DIR/e2e-matrix-results"       # per-provider: {prefix}-{provider}.json
REPORT_FILE="$SCRIPT_DIR/e2e-matrix-report.md"
DEVILS_FILE="$SCRIPT_DIR/e2e-matrix-devils-advocate.md"

TOKEN=""

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*" >&2; }
ok()   { echo -e "${GREEN}  ✅ $*${NC}" >&2; }
fail() { echo -e "${RED}  ❌ $*${NC}" >&2; }
warn() { echo -e "${YELLOW}  ⚠️  $*${NC}" >&2; }
hdr()  { echo -e "\n${CYAN}━━━ $* ━━━${NC}\n" >&2; }

# ── Spec definitions ───────────────────────────────────────────────────────

SPEC_NAMES=("landing-page" "todo-app" "task-api-node" "notes-api-java" "bookstore-api-python")
SPEC_LABELS=("CloudPulse Landing Page" "Todo App (Material Design)" "Task API — Node.js + MongoDB" "Notes API — Java + MongoDB" "Bookstore API — Python + MongoDB")
SPEC_TIERS=("simple" "simple" "backend" "backend" "backend")
SPEC_TIMEOUTS=(300 300 600 600 600)

# Prompts — use heredocs stored in an array
read -r -d '' PROMPT_0 << 'SPEC' || true
Create a beautiful, modern landing page for a fictional SaaS product called "CloudPulse" — a real-time infrastructure monitoring dashboard.

Requirements:
- Single index.html file with embedded CSS and JavaScript
- Hero section with a bold headline, subheadline, and a gradient call-to-action button
- Features section with 3 cards (icons can be emoji or SVG): Real-time Metrics, Smart Alerts, Team Dashboards
- Testimonials section with 2 customer quotes
- Footer with copyright and social links
- Responsive design that works on mobile and desktop
- Smooth scroll behavior, subtle hover animations on buttons and cards
- Color palette: deep navy (#0f172a) with electric blue (#3b82f6) accents
- Modern sans-serif typography using system fonts
- No backend, no frameworks, pure HTML/CSS/JS
SPEC

read -r -d '' PROMPT_1 << 'SPEC' || true
Build a todo list web application with Google Material Design aesthetics.

Requirements:
- Single-page app with HTML, CSS, and JavaScript (no frameworks)
- Material Design 3 style: elevated cards, rounded corners (16px), ripple effect on buttons
- Color scheme: primary #6750A4 (purple), surface #FFFBFE, on-surface #1C1B1F
- Roboto font from Google Fonts
- Features:
  1. Add new todos with a floating action button (FAB) that opens an input dialog
  2. Mark todos as complete (strikethrough with checkbox animation)
  3. Delete todos with a swipe-to-delete gesture or delete icon
  4. Filter by: All, Active, Completed
  5. Todo count badge showing remaining items
  6. Clear all completed button
- All data persisted to localStorage — survives page refresh
- Empty state illustration (CSS-only) when no todos exist
- Responsive: works on mobile (360px) and desktop
- No backend, no external dependencies except Google Fonts
SPEC

read -r -d '' PROMPT_2 << 'SPEC' || true
Build a task management REST API using Node.js, Express, and MongoDB.

Requirements:
- Project structure: package.json, src/index.js, src/routes/, src/models/, src/middleware/
- MongoDB connection via mongoose with configurable MONGODB_URI environment variable
- User authentication:
  1. POST /api/auth/register — email, password (bcrypt hashed), name
  2. POST /api/auth/login — returns JWT token
  3. JWT middleware protecting all /api/tasks routes
- Task CRUD:
  1. POST /api/tasks — create task (title, description, priority: LOW|MEDIUM|HIGH, dueDate)
  2. GET /api/tasks — list tasks for authenticated user (supports ?status=pending|completed&priority=HIGH)
  3. GET /api/tasks/:id — get single task
  4. PUT /api/tasks/:id — update task (only owner can update)
  5. DELETE /api/tasks/:id — delete task (only owner can delete)
  6. PATCH /api/tasks/:id/complete — mark task as completed with completedAt timestamp
- Task model fields: title, description, priority, status (pending/completed), dueDate, completedAt, createdBy (ref to User), timestamps
- Error handling middleware with consistent JSON error responses
- Input validation using express-validator or joi
- CORS enabled
- Health check: GET /health returns {status: "ok", uptime: process.uptime()}
SPEC

read -r -d '' PROMPT_3 << 'SPEC' || true
Build a notes management REST API using Java 21, Spring Boot 3, and MongoDB.

Requirements:
- Maven project with pom.xml including spring-boot-starter-web, spring-boot-starter-data-mongodb, spring-boot-starter-validation, lombok
- Package structure: com.example.notesapi with controller/, model/, repository/, service/, exception/ packages
- MongoDB configuration via spring.data.mongodb.uri property
- Note entity: id (String, auto-generated), title (required, max 200 chars), content (required, max 10000 chars), tags (List<String>), category (PERSONAL|WORK|IDEAS|ARCHIVE), pinned (boolean), createdAt, updatedAt (auto-managed)
- REST endpoints:
  1. POST /api/notes — create note (validate title and content required)
  2. GET /api/notes — list all notes (supports ?category=WORK&pinned=true&search=keyword query params)
  3. GET /api/notes/:id — get single note (404 if not found)
  4. PUT /api/notes/:id — full update
  5. PATCH /api/notes/:id/pin — toggle pinned status
  6. DELETE /api/notes/:id — delete note
  7. GET /api/notes/tags — list all unique tags across notes
- Global exception handler with @ControllerAdvice returning consistent error DTOs
- Pagination support on GET /api/notes (page, size, sort params)
- Health check: GET /actuator/health (Spring Boot default)
- application.yml with sensible defaults
SPEC

read -r -d '' PROMPT_4 << 'SPEC' || true
Build a bookstore REST API using Python with FastAPI and MongoDB.

Requirements:
- Project structure: requirements.txt, main.py, app/routes/, app/models/, app/database.py
- MongoDB connection via motor (async driver) with configurable MONGODB_URI environment variable
- Book CRUD:
  1. POST /api/books — create book (title, author, isbn, price, genre, publishedYear, inStock boolean)
  2. GET /api/books — list books with pagination (skip, limit) and filters (?genre=fiction&author=name&minPrice=10&maxPrice=50&inStock=true)
  3. GET /api/books/{id} — get single book (404 if not found)
  4. PUT /api/books/{id} — full update
  5. PATCH /api/books/{id}/stock — toggle inStock status
  6. DELETE /api/books/{id} — delete book
  7. GET /api/books/stats — return aggregate stats: total books, books by genre count, average price, out-of-stock count
- Pydantic models for request/response validation
- Proper error handling with HTTPException and custom error responses
- ISBN uniqueness validation (return 409 on duplicate)
- Health check: GET /health returns {"status": "ok"}
- CORS middleware enabled
- requirements.txt with fastapi, uvicorn, motor, pydantic
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
        echo -e "${RED}Auth failed: $resp${NC}" >&2
        exit 1
    fi
}

# ── API helpers ────────────────────────────────────────────────────────────

submit_run() {
    local prompt="$1"
    local provider="$2"
    local provider_upper
    provider_upper=$(echo "$provider" | tr '[:lower:]' '[:upper:]')

    # Use jq to safely escape the prompt into JSON
    local body
    body=$(jq -n \
        --arg req "$prompt" \
        --arg prov "$provider_upper" \
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
        -d '{"approved":true}'
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
                approve_run "$run_id" > /dev/null 2>&1 || true
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

    # Extract all metrics as a JSON object
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
        agentTokens: (.agentTokens // []),
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
                modelUsed: (.modelUsed // null),
                reportInputTokens: (.inputTokens // 0),
                reportOutputTokens: (.outputTokens // 0)
            }'
            return 0
        fi

        sleep 5
    done
}

# ── Run a single spec ──────────────────────────────────────────────────────

run_spec() {
    local spec_idx="$1"
    local provider="$2"
    local spec_name="${SPEC_NAMES[$spec_idx]}"
    local spec_label="${SPEC_LABELS[$spec_idx]}"
    local timeout="${SPEC_TIMEOUTS[$spec_idx]}"
    local prompt="${SPEC_PROMPTS[$spec_idx]}"

    hdr "Spec $((spec_idx+1))/5: $spec_label | Provider: $provider"

    # Re-authenticate to avoid JWT expiry
    auth_login 2>/dev/null

    # 1. Submit
    log "Submitting run..."
    local run_id
    run_id=$(submit_run "$prompt" "$provider") || true

    if [[ "$run_id" == SUBMIT_FAILED* ]]; then
        fail "Submit failed: $run_id"
        save_result "$spec_idx" "$provider" "SUBMIT_FAILED" "" 0 "{}" "{}"
        return
    fi
    ok "Submitted: $run_id"

    # 2. Poll until terminal
    local result
    result=$(poll_until_terminal "$run_id" "$timeout") || true
    local status
    status=$(echo "$result" | cut -d'|' -f1)
    local duration
    duration=$(echo "$result" | cut -d'|' -f2)

    if [[ "$status" == "TIMEOUT" ]]; then
        fail "Timed out after ${duration}s"
        # Cancel
        curl -s -X DELETE "$BASE_URL/api/runs/$run_id" \
            -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1 || true
        save_result "$spec_idx" "$provider" "TIMEOUT" "$run_id" "$duration" "{}" "{}"
        return
    fi

    # 3. Collect metrics
    log "Collecting metrics..."
    local metrics
    metrics=$(collect_metrics "$run_id")

    # 4. Wait for validation report
    local report_data="{}"
    if [[ "$status" == "COMPLETE" || "$status" == "PARTIAL" ]]; then
        log "Waiting for validation report (up to ${REPORT_WAIT}s)..."
        report_data=$(wait_for_report "$run_id") || report_data="{}"
    fi

    # 5. Log result
    local pipeline_result
    pipeline_result=$(echo "$report_data" | jq -r '.pipelineResult // "N/A"')
    local actual_cost
    actual_cost=$(echo "$metrics" | jq -r '.actualCostUsd // "N/A"')
    local tasks_done
    tasks_done=$(echo "$metrics" | jq -r '.tasksCompleted')
    local tasks_total
    tasks_total=$(echo "$metrics" | jq -r '.tasksTotal')

    case "$status" in
        COMPLETE)
            ok "$status — tasks: $tasks_done/$tasks_total, duration: ${duration}s, cost: \$$actual_cost, pipeline: $pipeline_result"
            ;;
        PARTIAL)
            warn "$status — tasks: $tasks_done/$tasks_total, duration: ${duration}s, cost: \$$actual_cost"
            ;;
        FAILED)
            local reason
            reason=$(echo "$metrics" | jq -r '.failureReason // "unknown"')
            fail "$status — duration: ${duration}s, reason: $reason"
            ;;
        *)
            fail "$status — duration: ${duration}s"
            ;;
    esac

    # 6. Save result
    save_result "$spec_idx" "$provider" "$status" "$run_id" "$duration" "$metrics" "$report_data"
}

# ── Results storage ────────────────────────────────────────────────────────

init_results() {
    local provider="${1:-}"
    if [[ -n "$provider" ]]; then
        local pfile="${RESULTS_PREFIX}-${provider}.json"
        echo '[]' > "$pfile"
    fi
    # Combined file created at merge time
}

merge_results() {
    # Merge all per-provider files into combined results
    local merged='[]'
    for p in anthropic google openai; do
        local pfile="${RESULTS_PREFIX}-${p}.json"
        if [[ -f "$pfile" ]]; then
            merged=$(echo "$merged" | jq --slurpfile prov "$pfile" '. + $prov[0]')
        fi
    done
    echo "$merged" > "$RESULTS_FILE"
    ok "Merged results into $RESULTS_FILE ($(echo "$merged" | jq length) entries)"
}

save_result() {
    local spec_idx="$1"
    local provider="$2"
    local status="$3"
    local run_id="$4"
    local duration="$5"
    local metrics="$6"
    local report_data="$7"

    local spec_name="${SPEC_NAMES[$spec_idx]}"
    local spec_label="${SPEC_LABELS[$spec_idx]}"
    local spec_tier="${SPEC_TIERS[$spec_idx]}"
    local provider_upper
    provider_upper=$(echo "$provider" | tr '[:lower:]' '[:upper:]')

    # Build result entry
    local entry
    entry=$(jq -n \
        --arg specName "$spec_name" \
        --arg specLabel "$spec_label" \
        --arg specTier "$spec_tier" \
        --argjson specIndex "$spec_idx" \
        --arg provider "$provider_upper" \
        --arg runId "$run_id" \
        --arg status "$status" \
        --argjson duration "$duration" \
        --argjson metrics "${metrics:-{\}}" \
        --argjson report "${report_data:-{\}}" \
        '{
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
            costEstimateLow: ($metrics.costEstimateLow // null),
            costEstimateHigh: ($metrics.costEstimateHigh // null),
            creditsCharged: ($metrics.creditsCharged // 1),
            plannerModel: ($metrics.plannerModel // null),
            workerModel: ($metrics.workerModel // null),
            reviewerModel: ($metrics.reviewerModel // null),
            tasksTotal: ($metrics.tasksTotal // 0),
            tasksCompleted: ($metrics.tasksCompleted // 0),
            tasksFailed: ($metrics.tasksFailed // 0),
            failureReason: ($metrics.failureReason // null),
            techStack: ($metrics.techStack // null),
            hasFrontend: ($metrics.hasFrontend // false),
            pipelineResult: ($report.pipelineResult // null),
            filesGenerated: ($report.filesGenerated // 0),
            checksPassed: ($report.checksPassed // []),
            compilationPassed: (($report.checksPassed // []) | if type == "array" then any(. == "COMPILATION") else false end),
            reportAvailable: (($report.pipelineResult // null) != null),
            generationTimeMs: ($report.generationTimeMs // null),
            agentTokens: ($metrics.agentTokens // []),
            plannerInputTokens: ($metrics.plannerInputTokens // 0),
            plannerOutputTokens: ($metrics.plannerOutputTokens // 0),
            reviewerInputTokens: ($metrics.reviewerInputTokens // 0),
            reviewerOutputTokens: ($metrics.reviewerOutputTokens // 0),
            compilerInputTokens: ($metrics.compilerInputTokens // 0),
            compilerOutputTokens: ($metrics.compilerOutputTokens // 0),
            timestamp: (now | todate)
        }')

    # Write to per-provider file (safe for parallel execution)
    local pfile="${RESULTS_PREFIX}-$(echo "$provider" | tr '[:upper:]' '[:lower:]').json"
    if [[ ! -f "$pfile" ]]; then
        echo '[]' > "$pfile"
    fi
    local current
    current=$(cat "$pfile")
    current=$(echo "$current" | jq --arg sn "$spec_name" --arg pv "$provider_upper" \
        '[.[] | select(.specName != $sn or .provider != $pv)]')
    echo "$current" | jq ". + [$entry]" > "$pfile"
}

# ── Report generation ──────────────────────────────────────────────────────

generate_report() {
    local results="$RESULTS_FILE"
    if [[ ! -f "$results" ]]; then
        echo "No results file found at $results" >&2
        return 1
    fi

    local total
    total=$(jq length "$results")
    if [[ "$total" -eq 0 ]]; then
        echo "Results file is empty" >&2
        return 1
    fi

    {
        echo "# ExecuteSpec E2E Matrix Test Report"
        echo ""
        echo "**Date:** $(date '+%Y-%m-%d %H:%M %Z')"
        echo "**Environment:** $BASE_URL"
        echo "**Execution Mode:** PROTOTYPE"
        echo "**Total Runs:** $total"
        echo ""
        echo "---"
        echo ""

        # ── Executive Summary ──
        local completed failed partial timeout total_cost total_input total_output total_duration
        completed=$(jq '[.[] | select(.status == "COMPLETE")] | length' "$results")
        failed=$(jq '[.[] | select(.status == "FAILED" or .status == "SUBMIT_FAILED")] | length' "$results")
        partial=$(jq '[.[] | select(.status == "PARTIAL")] | length' "$results")
        timeout=$(jq '[.[] | select(.status == "TIMEOUT")] | length' "$results")
        total_cost=$(jq '[.[].actualCostUsd // 0] | add' "$results")
        total_input=$(jq '[.[].totalInputTokens // 0] | add' "$results")
        total_output=$(jq '[.[].totalOutputTokens // 0] | add' "$results")
        total_duration=$(jq '[.[].durationSeconds // 0] | add' "$results")

        echo "## Executive Summary"
        echo ""
        echo "| Metric | Value |"
        echo "|--------|-------|"
        echo "| Total runs | $total |"
        echo "| Completed | $completed |"
        echo "| Failed | $failed |"
        echo "| Partial | $partial |"
        echo "| Timeout | $timeout |"
        echo "| Success rate | ${completed}/${total} ($(( completed * 100 / (total > 0 ? total : 1) ))%) |"
        echo "| Total wall-clock time | ${total_duration}s ($(( total_duration / 60 ))m $(( total_duration % 60 ))s) |"
        echo "| Total cost | \$${total_cost} |"
        echo "| Total tokens | ${total_input} in / ${total_output} out |"
        echo ""
        echo "---"
        echo ""

        # ── Per-Run Results Matrix ──
        echo "## 1. Per-Run Results Matrix"
        echo ""
        echo "| # | Spec | Provider | Status | Duration | Cost (USD) | Tokens (in/out) | Tasks | Report | Pipeline | Compile |"
        echo "|---|------|----------|--------|----------|-----------|----------------|-------|--------|----------|---------|"

        jq -r 'sort_by(.specIndex, .provider) | .[] |
            "| \(.specIndex + 1) | \(.specLabel) | \(.provider) | \(.status) | \(.durationSeconds)s | $\(.actualCostUsd // "N/A") | \(.totalInputTokens)/\(.totalOutputTokens) | \(.tasksCompleted)/\(.tasksTotal) | \(if .reportAvailable then "YES" else "NO" end) | \(.pipelineResult // "N/A") | \(if .compilationPassed then "PASS" else "FAIL" end) |"' "$results"

        echo ""
        echo "---"
        echo ""

        # ── Per-Provider Comparison ──
        echo "## 2. Per-Provider Comparison"
        echo ""
        echo "| Metric | ANTHROPIC | GOOGLE | OPENAI |"
        echo "|--------|-----------|--------|--------|"

        for metric in \
            "Success Rate|[.[] | select(.provider == \"PROV\" and .status == \"COMPLETE\")] | length|/5" \
            "Avg Duration|[.[] | select(.provider == \"PROV\")] | if length > 0 then ([.[].durationSeconds] | add / length | floor) else 0 end|s" \
            "Avg Cost|[.[] | select(.provider == \"PROV\")] | if length > 0 then ([.[] | .actualCostUsd // 0] | add / length * 100 | floor / 100) else 0 end|$" \
            "Total Cost|[.[] | select(.provider == \"PROV\")] | [.[] | .actualCostUsd // 0] | add|$" \
            "Total Input Tokens|[.[] | select(.provider == \"PROV\")] | [.[].totalInputTokens] | add|" \
            "Total Output Tokens|[.[] | select(.provider == \"PROV\")] | [.[].totalOutputTokens] | add|" \
            "Compilation Pass|[.[] | select(.provider == \"PROV\" and .compilationPassed == true)] | length|/5" \
            "Pipeline Pass|[.[] | select(.provider == \"PROV\" and (.pipelineResult == \"PASSED\" or .pipelineResult == \"PASSED_WITH_FIXES\"))] | length|/5" \
        ; do
            local label suffix
            label=$(echo "$metric" | cut -d'|' -f1)
            suffix=$(echo "$metric" | cut -d'|' -f3)
            local query_tmpl
            query_tmpl=$(echo "$metric" | cut -d'|' -f2)

            local val_a val_g val_o
            val_a=$(jq "$(echo "$query_tmpl" | sed 's/PROV/ANTHROPIC/g')" "$results" 2>/dev/null || echo "0")
            val_g=$(jq "$(echo "$query_tmpl" | sed 's/PROV/GOOGLE/g')" "$results" 2>/dev/null || echo "0")
            val_o=$(jq "$(echo "$query_tmpl" | sed 's/PROV/OPENAI/g')" "$results" 2>/dev/null || echo "0")

            if [[ "$suffix" == '$' ]]; then
                echo "| $label | \$${val_a} | \$${val_g} | \$${val_o} |"
            elif [[ "$suffix" == "s" ]]; then
                echo "| $label | ${val_a}s | ${val_g}s | ${val_o}s |"
            else
                echo "| $label | ${val_a}${suffix} | ${val_g}${suffix} | ${val_o}${suffix} |"
            fi
        done

        echo ""
        echo "---"
        echo ""

        # ── Per-Spec Comparison ──
        echo "## 3. Per-Spec Comparison Across Providers"
        echo ""

        for i in 0 1 2 3 4; do
            local spec_label="${SPEC_LABELS[$i]}"
            local spec_tier="${SPEC_TIERS[$i]}"
            echo "### Spec $((i+1)): $spec_label ($spec_tier)"
            echo ""
            echo "| Provider | Status | Duration | Cost | Tokens (in/out) | Tasks | Pipeline | Compile |"
            echo "|----------|--------|----------|------|----------------|-------|----------|---------|"

            jq -r --argjson idx "$i" '[.[] | select(.specIndex == $idx)] | sort_by(.provider) | .[] |
                "| \(.provider) | \(.status) | \(.durationSeconds)s | $\(.actualCostUsd // "N/A") | \(.totalInputTokens)/\(.totalOutputTokens) | \(.tasksCompleted)/\(.tasksTotal) | \(.pipelineResult // "N/A") | \(if .compilationPassed then "PASS" else "FAIL" end) |"' "$results"

            # Best provider for this spec
            local best
            best=$(jq -r --argjson idx "$i" '
                [.[] | select(.specIndex == $idx and .status == "COMPLETE")]
                | sort_by(.durationSeconds)
                | first
                | if . then "**Best:** \(.provider) (\(.durationSeconds)s, $\(.actualCostUsd // "N/A"))" else "**Best:** N/A (no completions)" end
            ' "$results")
            echo ""
            echo "$best"
            echo ""
        done

        echo "---"
        echo ""

        # ── Failure Analysis ──
        echo "## 4. Failure Analysis"
        echo ""

        local failure_count
        failure_count=$(jq '[.[] | select(.status != "COMPLETE")] | length' "$results")

        if [[ "$failure_count" -eq 0 ]]; then
            echo "No failures detected across all runs."
        else
            echo "### Failed/Non-Complete Runs"
            echo ""
            echo "| Spec | Provider | Status | Duration | Failure Reason |"
            echo "|------|----------|--------|----------|---------------|"

            jq -r '[.[] | select(.status != "COMPLETE")] | sort_by(.specIndex) | .[] |
                "| \(.specLabel) | \(.provider) | \(.status) | \(.durationSeconds)s | \(.failureReason // "N/A") |"' "$results"

            echo ""

            # Cross-cutting failures
            echo "### Cross-Cutting Failures"
            echo ""
            for i in 0 1 2 3 4; do
                local all_fail=true
                for p in ANTHROPIC GOOGLE OPENAI; do
                    local st
                    st=$(jq -r --argjson idx "$i" --arg p "$p" \
                        '.[] | select(.specIndex == $idx and .provider == $p) | .status' "$results" 2>/dev/null)
                    if [[ "$st" == "COMPLETE" ]]; then
                        all_fail=false
                        break
                    fi
                done
                if $all_fail; then
                    echo "- **${SPEC_LABELS[$i]}**: Failed on ALL providers — likely a pipeline issue, not provider-specific"
                fi
            done
        fi

        echo ""
        echo "---"
        echo ""

        # ── Agent Token Breakdown ──
        echo "## 5. Token & Cost Breakdown by Agent Role"
        echo ""
        echo "| Provider | Planner (in/out) | Worker (est.) | Compiler (in/out) | Reviewer (in/out) |"
        echo "|----------|-----------------|--------------|-------------------|-------------------|"

        for p in ANTHROPIC GOOGLE OPENAI; do
            local p_in p_out c_in c_out r_in r_out total_in total_out
            p_in=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .plannerInputTokens] | add // 0' "$results")
            p_out=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .plannerOutputTokens] | add // 0' "$results")
            c_in=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .compilerInputTokens] | add // 0' "$results")
            c_out=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .compilerOutputTokens] | add // 0' "$results")
            r_in=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .reviewerInputTokens] | add // 0' "$results")
            r_out=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .reviewerOutputTokens] | add // 0' "$results")
            total_in=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .totalInputTokens] | add // 0' "$results")
            total_out=$(jq --arg p "$p" '[.[] | select(.provider == $p) | .totalOutputTokens] | add // 0' "$results")
            # Worker tokens estimated as total minus planner/compiler/reviewer
            local w_in=$(( total_in - p_in - c_in - r_in ))
            local w_out=$(( total_out - p_out - c_out - r_out ))
            [[ $w_in -lt 0 ]] && w_in=0
            [[ $w_out -lt 0 ]] && w_out=0

            echo "| $p | ${p_in}/${p_out} | ${w_in}/${w_out} | ${c_in}/${c_out} | ${r_in}/${r_out} |"
        done

        echo ""
        echo "---"
        echo ""

        # ── Models Used ──
        echo "## 6. Models Used"
        echo ""
        echo "| Provider | Planner | Worker | Reviewer |"
        echo "|----------|---------|--------|----------|"

        for p in ANTHROPIC GOOGLE OPENAI; do
            local pm wm rm
            pm=$(jq -r --arg p "$p" '[.[] | select(.provider == $p) | .plannerModel // "N/A"] | first // "N/A"' "$results")
            wm=$(jq -r --arg p "$p" '[.[] | select(.provider == $p) | .workerModel // "N/A"] | first // "N/A"' "$results")
            rm=$(jq -r --arg p "$p" '[.[] | select(.provider == $p) | .reviewerModel // "N/A"] | first // "N/A"' "$results")
            echo "| $p | $pm | $wm | $rm |"
        done

        echo ""
        echo "---"
        echo ""

        # ── Raw Run IDs ──
        echo "## 7. Raw Run IDs"
        echo ""
        echo "| Spec | ANTHROPIC | GOOGLE | OPENAI |"
        echo "|------|-----------|--------|--------|"

        for i in 0 1 2 3 4; do
            local a_id g_id o_id
            a_id=$(jq -r --argjson idx "$i" '.[] | select(.specIndex == $idx and .provider == "ANTHROPIC") | .runId // "—"' "$results")
            g_id=$(jq -r --argjson idx "$i" '.[] | select(.specIndex == $idx and .provider == "GOOGLE") | .runId // "—"' "$results")
            o_id=$(jq -r --argjson idx "$i" '.[] | select(.specIndex == $idx and .provider == "OPENAI") | .runId // "—"' "$results")
            echo "| ${SPEC_LABELS[$i]} | ${a_id:-—} | ${g_id:-—} | ${o_id:-—} |"
        done

        echo ""
    } > "$REPORT_FILE"

    ok "Report written to $REPORT_FILE"
}

# ── Devil's Advocate ───────────────────────────────────────────────────────

generate_devils_advocate() {
    local results="$RESULTS_FILE"
    if [[ ! -f "$results" ]]; then
        echo "No results file found" >&2
        return 1
    fi

    local total completed failed
    total=$(jq length "$results")
    completed=$(jq '[.[] | select(.status == "COMPLETE")] | length' "$results")
    failed=$(( total - completed ))
    local success_pct=$(( completed * 100 / (total > 0 ? total : 1) ))
    local total_cost
    total_cost=$(jq '[.[] | .actualCostUsd // 0] | add' "$results")
    local wasted_cost
    wasted_cost=$(jq '[.[] | select(.status != "COMPLETE") | .actualCostUsd // 0] | add' "$results")
    local compilation_passed
    compilation_passed=$(jq '[.[] | select(.compilationPassed == true)] | length' "$results")
    local pipeline_passed
    pipeline_passed=$(jq '[.[] | select(.pipelineResult == "PASSED" or .pipelineResult == "PASSED_WITH_FIXES")] | length' "$results")
    local reports_available
    reports_available=$(jq '[.[] | select(.reportAvailable == true)] | length' "$results")

    # Cost variance across providers for same spec
    local max_cost_ratio="N/A"
    for i in 0 1 2 3 4; do
        local costs
        costs=$(jq --argjson idx "$i" '[.[] | select(.specIndex == $idx and .status == "COMPLETE") | .actualCostUsd // 0] | sort' "$results")
        local min_c max_c
        min_c=$(echo "$costs" | jq 'first // 0')
        max_c=$(echo "$costs" | jq 'last // 0')
        if [[ "$min_c" != "0" && "$min_c" != "null" ]]; then
            local ratio
            ratio=$(echo "scale=1; $max_c / $min_c" | bc 2>/dev/null || echo "N/A")
            if [[ "$max_cost_ratio" == "N/A" ]] || (( $(echo "$ratio > ${max_cost_ratio:-0}" | bc 2>/dev/null || echo 0) )); then
                max_cost_ratio="$ratio"
            fi
        fi
    done

    # Backend failure rate
    local backend_total backend_complete
    backend_total=$(jq '[.[] | select(.specTier == "backend")] | length' "$results")
    backend_complete=$(jq '[.[] | select(.specTier == "backend" and .status == "COMPLETE")] | length' "$results")
    local frontend_total frontend_complete
    frontend_total=$(jq '[.[] | select(.specTier == "simple")] | length' "$results")
    frontend_complete=$(jq '[.[] | select(.specTier == "simple" and .status == "COMPLETE")] | length' "$results")

    {
        echo "# ExecuteSpec Platform Assessment — Devil's Advocate Analysis"
        echo ""
        echo "**Based on:** E2E Matrix Test Results ($(date '+%Y-%m-%d'))"
        echo "**Environment:** $BASE_URL"
        echo "**Runs analyzed:** $total"
        echo ""
        echo "> This document provides a deliberately critical assessment of the ExecuteSpec"
        echo "> platform based on empirical E2E test data. It highlights risks, weaknesses,"
        echo "> and areas that would concern a skeptical evaluator."
        echo ""
        echo "---"
        echo ""

        # ── 1. Reliability ──
        echo "## 1. Reliability Concerns"
        echo ""
        echo "### Overall Failure Rate"
        echo "- **${failed} of ${total} runs did not reach COMPLETE status ($(( 100 - success_pct ))% failure rate)**"
        if [[ "$success_pct" -lt 80 ]]; then
            echo "- This is below the 80% reliability threshold expected for a production-ready code generation platform"
        fi
        echo ""

        echo "### Provider-Specific Reliability"
        echo ""
        for p in ANTHROPIC GOOGLE OPENAI; do
            local p_total p_complete
            p_total=$(jq --arg p "$p" '[.[] | select(.provider == $p)] | length' "$results")
            p_complete=$(jq --arg p "$p" '[.[] | select(.provider == $p and .status == "COMPLETE")] | length' "$results")
            local p_pct=$(( p_complete * 100 / (p_total > 0 ? p_total : 1) ))
            echo "- **$p:** ${p_complete}/${p_total} success (${p_pct}%)"

            # List failures
            local p_failures
            p_failures=$(jq -r --arg p "$p" '.[] | select(.provider == $p and .status != "COMPLETE") |
                "  - \(.specLabel): \(.status) — \(.failureReason // "no reason captured")"' "$results")
            if [[ -n "$p_failures" ]]; then
                echo "$p_failures"
            fi
        done

        echo ""

        echo "### Pipeline Fragility Indicators"
        echo "- Validation reports available for only **${reports_available}/${total}** runs"
        echo "- Compilation passed on **${compilation_passed}/${total}** runs"
        echo "- Pipeline PASSED/PASSED_WITH_FIXES on **${pipeline_passed}/${total}** runs"
        echo "- PARTIAL status means code is incomplete — users receive half-built applications"
        echo ""
        echo "---"
        echo ""

        # ── 2. Cost ──
        echo "## 2. Cost Concerns"
        echo ""
        echo "### Cost Predictability"
        echo "- Total spend across all runs: **\$${total_cost}**"
        echo "- Cost wasted on non-COMPLETE runs: **\$${wasted_cost}**"
        if [[ "$max_cost_ratio" != "N/A" ]]; then
            echo "- Widest cost ratio across providers for same spec: **${max_cost_ratio}x**"
        fi
        echo ""

        echo "### Cost Estimation Accuracy"
        echo ""
        echo "| Spec | Provider | Estimated (low-high) | Actual | Accuracy |"
        echo "|------|----------|---------------------|--------|----------|"
        jq -r 'sort_by(.specIndex, .provider) | .[] | select(.status == "COMPLETE") |
            "| \(.specLabel) | \(.provider) | $\(.costEstimateLow // "?")–$\(.costEstimateHigh // "?") | $\(.actualCostUsd // "?") | \(
                if (.costEstimateHigh // 0) > 0 and (.actualCostUsd // 0) > 0 then
                    if (.actualCostUsd // 0) <= (.costEstimateHigh // 0) then "WITHIN"
                    else "OVER"
                    end
                else "N/A"
                end
            ) |"' "$results"
        echo ""

        echo "### Hidden Costs"
        echo "- Failed runs still consume LLM tokens before failing"
        echo "- Users are charged credits at plan approval — refunds only on FAILURE status, not PARTIAL"
        echo "- Retry attempts (task-level) consume additional tokens not reflected in the estimate"
        echo ""
        echo "---"
        echo ""

        # ── 3. Quality ──
        echo "## 3. Quality Concerns"
        echo ""
        echo "### What \"COMPLETE\" Does NOT Tell You"
        echo "- COMPLETE means all tasks ran to completion — NOT that the code works correctly"
        echo "- Pipeline result breakdown:"
        for pr in PASSED PASSED_WITH_FIXES NEEDS_ATTENTION FAILED; do
            local pr_count
            pr_count=$(jq --arg pr "$pr" '[.[] | select(.pipelineResult == $pr)] | length' "$results")
            echo "  - $pr: $pr_count"
        done
        echo "- No report generated: $(( total - reports_available ))"
        echo ""

        echo "### Backend Spec Limitations"
        echo "- MongoDB specs generate code but **do NOT verify against a running MongoDB instance**"
        echo "- \"Compilation\" for Node.js/Python means dependency check — not functional verification"
        echo "- No integration tests execute against an actual database"
        echo "- Backend failure rate: $(( backend_total - backend_complete ))/${backend_total} vs frontend: $(( frontend_total - frontend_complete ))/${frontend_total}"
        echo ""

        echo "### Aesthetic Quality (Frontend Specs)"
        echo "- Landing page and Todo app aesthetics **cannot be assessed programmatically**"
        echo "- \"Beautiful\" and \"Material Design\" are subjective — only manual review validates"
        echo "- No Lighthouse scores, accessibility audits, or screenshot comparison"
        echo ""
        echo "---"
        echo ""

        # ── 4. Architecture ──
        echo "## 4. Architecture & Scalability Concerns"
        echo ""
        echo "### Concurrency"
        echo "- Tests ran sequentially to avoid overloading dev — what happens under real concurrent load?"
        echo "- Bounded thread pool with CallerRunsPolicy means backpressure under load"
        echo "- JWT re-authentication needed between specs — token expiry during long runs is a risk"
        echo ""

        echo "### Pipeline Timing"
        echo ""
        echo "| Spec Tier | Avg Duration | Slowest Run |"
        echo "|-----------|-------------|-------------|"
        for tier in simple backend; do
            local avg_dur slowest
            avg_dur=$(jq --arg t "$tier" '[.[] | select(.specTier == $t)] | if length > 0 then ([.[].durationSeconds] | add / length | floor) else 0 end' "$results")
            slowest=$(jq -r --arg t "$tier" '[.[] | select(.specTier == $t)] | sort_by(-.durationSeconds) | first | "\(.durationSeconds)s (\(.specLabel), \(.provider))"' "$results")
            echo "| $tier | ${avg_dur}s | $slowest |"
        done
        echo ""

        echo "### Validation Gap"
        echo "- Time from COMPLETE to validation report: varies widely"
        echo "- Users see COMPLETE status before code quality is verified"
        echo "- If validation finds critical issues, user may have already deployed"
        echo ""
        echo "---"
        echo ""

        # ── 5. Model Matrix ──
        echo "## 5. Model Matrix Risks"
        echo ""
        echo "### Models Observed"
        echo ""
        for p in ANTHROPIC GOOGLE OPENAI; do
            local pm wm
            pm=$(jq -r --arg p "$p" '[.[] | select(.provider == $p) | .plannerModel // "N/A"] | first // "N/A"' "$results")
            wm=$(jq -r --arg p "$p" '[.[] | select(.provider == $p) | .workerModel // "N/A"] | first // "N/A"' "$results")
            echo "- **$p:** Planner=$pm, Worker=$wm"
        done
        echo ""
        echo "### Concerns"
        echo "- Single model per tier per provider — no intra-provider redundancy"
        echo "- Model deprecation risk: provider model sunset could break the platform"
        echo "- Results are NOT directly comparable across providers (different model capabilities)"
        echo "- \"Best provider\" depends on optimization target (cost vs speed vs reliability)"
        echo ""
        echo "---"
        echo ""

        # ── 6. Recommendations ──
        echo "## 6. Recommendations (Priority Order)"
        echo ""
        echo "1. **Add functional validation:** Execute generated code against real dependencies (MongoDB, etc.)"
        echo "2. **Track cost estimation accuracy:** Compare estimated vs actual cost systematically"
        echo "3. **Add concurrent load testing:** Run multiple specs simultaneously to find limits"
        echo "4. **Add visual regression testing:** Screenshot comparison for frontend specs"
        echo "5. **Add Lighthouse scoring:** Automated performance/accessibility for generated frontends"
        echo "6. **Improve PARTIAL handling:** Clearly communicate to users what \"partial\" means"
        echo "7. **Track model versions:** Log exact model version strings to detect regressions on updates"
        echo "8. **Add retry cost tracking:** Separate retry token consumption from initial estimate"
        echo ""
        echo "---"
        echo ""
        echo "*Generated automatically from E2E matrix test results.*"

    } > "$DEVILS_FILE"

    ok "Devil's advocate written to $DEVILS_FILE"
}

# ── Main ───────────────────────────────────────────────────────────────────

main() {
    local command="${1:-}"

    if [[ -z "$command" ]]; then
        echo "Usage: $0 <all|anthropic|google|openai|report|devils-advocate>"
        echo ""
        echo "  all             Run all 15 tests (5 specs x 3 providers) + generate reports"
        echo "  anthropic       Run 5 tests with Anthropic provider"
        echo "  google          Run 5 tests with Google/Gemini provider"
        echo "  openai          Run 5 tests with OpenAI provider"
        echo "  report          Regenerate report from existing results JSON"
        echo "  devils-advocate Regenerate devil's advocate from existing results JSON"
        exit 1
    fi

    # Report-only commands (merge first, then generate)
    if [[ "$command" == "report" ]]; then
        merge_results
        generate_report
        exit 0
    fi
    if [[ "$command" == "devils-advocate" ]]; then
        merge_results
        generate_devils_advocate
        exit 0
    fi

    # Determine providers to run
    local providers=()
    case "$command" in
        all)       providers=("anthropic" "google" "openai") ;;
        anthropic) providers=("anthropic") ;;
        google)    providers=("google") ;;
        openai)    providers=("openai") ;;
        *)
            echo "Unknown command: $command"
            exit 1
            ;;
    esac

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  ExecuteSpec E2E Matrix Test"
    echo "  Specs: ${#SPEC_NAMES[@]} | Providers: ${providers[*]} | Total: $(( ${#SPEC_NAMES[@]} * ${#providers[@]} ))"
    if [[ ${#providers[@]} -gt 1 ]]; then
        echo "  Mode: PARALLEL (all providers run concurrently)"
    else
        echo "  Mode: SEQUENTIAL (single provider)"
    fi
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    # Health check
    local health_resp
    health_resp=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" 2>/dev/null || echo "000")
    if [[ "$health_resp" != "200" ]]; then
        echo -e "${RED}Server not reachable at $BASE_URL (HTTP $health_resp)${NC}"
        exit 1
    fi
    ok "Server reachable at $BASE_URL"

    # Auth (verify credentials work)
    auth_login
    ok "Authenticated as $EMAIL"

    # Init per-provider result files
    for provider in "${providers[@]}"; do
        init_results "$provider"
    done

    local batch_start
    batch_start=$(date +%s)

    if [[ ${#providers[@]} -gt 1 ]]; then
        # ── PARALLEL MODE: launch each provider as a background job ──
        local pids=()
        local logdir="$SCRIPT_DIR/e2e-matrix-logs"
        mkdir -p "$logdir"

        for provider in "${providers[@]}"; do
            local logfile="$logdir/${provider}.log"
            (
                # Each subshell gets its own auth token
                hdr "Provider: $(echo "$provider" | tr '[:lower:]' '[:upper:]')"
                for i in $(seq 0 $((${#SPEC_NAMES[@]} - 1))); do
                    run_spec "$i" "$provider"
                done
                log "Provider $provider: all 5 specs done"
            ) > "$logfile" 2>&1 &
            pids+=($!)
            log "Launched $provider (PID $!, log: $logfile)"
        done

        # Wait for all providers, report as they finish
        local any_failed=false
        for idx in "${!providers[@]}"; do
            local provider="${providers[$idx]}"
            local pid="${pids[$idx]}"
            if wait "$pid"; then
                ok "Provider $provider finished successfully"
            else
                fail "Provider $provider exited with error"
                any_failed=true
            fi
        done

        # Print logs inline
        echo ""
        for provider in "${providers[@]}"; do
            hdr "Log: $provider"
            cat "$logdir/${provider}.log"
        done
    else
        # ── SEQUENTIAL MODE: single provider ──
        local provider="${providers[0]}"
        hdr "Provider: $(echo "$provider" | tr '[:lower:]' '[:upper:]')"

        for i in $(seq 0 $((${#SPEC_NAMES[@]} - 1))); do
            run_spec "$i" "$provider"
        done
    fi

    local batch_duration=$(( $(date +%s) - batch_start ))

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "All runs complete in ${batch_duration}s ($(( batch_duration / 60 ))m $(( batch_duration % 60 ))s)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # Merge per-provider results into combined file
    merge_results

    # Quick summary
    local total_runs pass_count
    total_runs=$(jq length "$RESULTS_FILE")
    pass_count=$(jq '[.[] | select(.status == "COMPLETE")] | length' "$RESULTS_FILE")
    echo ""
    echo -e "  ${GREEN}Pass: $pass_count${NC} / $total_runs total"
    echo "  Results: $RESULTS_FILE"

    # Generate reports
    log "Generating reports..."
    generate_report
    generate_devils_advocate

    echo ""
    echo "  Report:          $REPORT_FILE"
    echo "  Devil's advocate: $DEVILS_FILE"
    echo ""
}

main "$@"
