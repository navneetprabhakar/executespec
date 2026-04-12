#!/usr/bin/env bash
# ExecuteSpec — Provider Eval Script
# Measures: cost, time, accuracy, efficiency across Anthropic and OpenAI.
#
# Usage:
#   ./scripts/test-providers.sh                              # both specs, ANTHROPIC + OPENAI
#   BASE_URL=http://localhost:8080 ./scripts/test-providers.sh
#   PROVIDERS="ANTHROPIC" ./scripts/test-providers.sh       # single provider
#   SPEC_TYPE=single ./scripts/test-providers.sh            # single-shot spec only
#   SPEC_TYPE=multi ./scripts/test-providers.sh             # multi-task spec only
#   SKIP_REPORT=1 ./scripts/test-providers.sh               # skip waiting for report
#
# Output:
#   - Per-run table printed to stdout
#   - JSON results appended to scripts/eval-results-$(date).json

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${E2E_EMAIL:-admin@executespec.ai}"
PASSWORD="${E2E_PASSWORD:-REDACTED_FOR_PUBLIC_RELEASE}"
PROVIDERS="${PROVIDERS:-ANTHROPIC OPENAI}"
SPEC_TYPE="${SPEC_TYPE:-both}"   # single | multi | both
SKIP_REPORT="${SKIP_REPORT:-0}"

POLL_INTERVAL=8
RUN_TIMEOUT=1200    # 20 min per run
REPORT_WAIT=300     # 5 min to wait for report — multi-task validation takes longer

RESULTS_FILE="scripts/eval-results-$(date +%Y%m%d-%H%M%S).json"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}  ✅ $*${NC}"; }
fail() { echo -e "${RED}  ❌ $*${NC}"; }
warn() { echo -e "${YELLOW}  ⚠️  $*${NC}"; }
hdr()  { echo -e "\n${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n  $*\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"; }

# ── Spec A: single-shot — measures baseline accuracy on simple 1-file output ──
SPEC_SINGLE='Build a static HTML/CSS/JS todo app. Single index.html file.
Features: add task, mark complete with strikethrough, delete task, task counter.
Clean design: soft shadows, rounded cards, blue/white palette, smooth transitions.
Use CSS custom properties (--color-primary, --color-bg, --color-text, --radius, --shadow) for all colors, spacing, and shadows — no hardcoded hex values in rules.
LocalStorage persistence. No backend, no build step.'

# ── Spec B: multi-task — measures accuracy on a real multi-file Express+HTML project ──
SPEC_MULTI='Build a Node.js Express REST API for expense tracking with a simple HTML dashboard.

Backend (Node.js + Express, no database — use in-memory arrays):
- Resource: expenses with fields: id (uuid), title (string, required), amount (number, positive, required), category (string, required), date (ISO string, required), notes (string, optional)
- Resource: categories with fields: id (uuid), name (string, required), budget (number, positive, required)
- Endpoints:
  GET    /api/expenses                  list all (filter: ?category=&from=&to= date range)
  POST   /api/expenses                  create (validate all required fields)
  DELETE /api/expenses/:id             delete by id (404 if not found)
  GET    /api/categories               list all
  POST   /api/categories               create
  DELETE /api/categories/:id           delete by id
  GET    /api/summary                  return { byCategory: [{category, total, budget}], monthlyTotals: [{month, total}] }
- Seed 5 categories and 10 expenses on startup so the dashboard has data
- Centralized JSON error middleware (4-arg Express handler)
- 404 handler returning JSON (placed BEFORE error middleware)
- CORS enabled for all origins
- Server listens on process.env.PORT (default 3001) bound to 0.0.0.0

Frontend (single index.html — no build step, vanilla JS):
- Fetch data from relative URLs: /api/expenses, /api/categories, /api/summary
- Show: expense table (title, amount, category, date), category totals table, monthly totals table
- Add expense form (all fields, POST to /api/expenses, refresh list on success)
- Delete button per expense row (DELETE /api/expenses/:id, refresh list)
- Use CSS custom properties for all colors (--color-primary, --color-bg, --color-text, --color-accent)
- Clean, professional layout; responsive

Module system: CommonJS throughout — use require()/module.exports in ALL files. Do NOT use import/export syntax anywhere.
npm start script in package.json: must be exactly "node server.js" — a single command, no concurrently. The HTML/CSS/JS frontend files are served as static assets by Express (express.static), NOT by a separate Vite or dev server process. There is no "npm run dev" or frontend dev server.
README with curl examples for every endpoint.'

TOKEN=""
ALL_RESULTS=()

# ── Auth ───────────────────────────────────────────────────────────────────────
auth_login() {
    local resp
    resp=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
    TOKEN=$(echo "$resp" | jq -r '.accessToken // empty')
    if [[ -z "$TOKEN" ]]; then
        echo -e "${RED}Auth failed. Response: $resp${NC}"; exit 1
    fi
    log "Authenticated as $EMAIL"
}

# ── Submit ─────────────────────────────────────────────────────────────────────
submit_run() {
    local provider="$1" spec="$2"
    local body
    body=$(jq -n --arg req "$spec" --arg prov "$provider" \
        '{userRequest: $req, skipApproval: true, executionMode: "PROTOTYPE", aiProvider: $prov}')
    local tmp_submit="/tmp/submit_${provider}_$$.json"
    curl -s -X POST "$BASE_URL/api/runs" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$body" > "$tmp_submit" 2>/dev/null
    jq -r '.runId // empty' "$tmp_submit" 2>/dev/null || echo ""
}

# ── Poll until terminal — returns JSON detail of the final state ───────────────
poll_run() {
    local run_id="$1" provider="$2"
    local elapsed=0
    local tmp_detail="/tmp/poll_${run_id}.json"
    local status

    while [[ $elapsed -lt $RUN_TIMEOUT ]]; do
        curl -s "$BASE_URL/api/runs/$run_id" -H "Authorization: Bearer $TOKEN" > "$tmp_detail" 2>/dev/null
        status=$(jq -r '.status // empty' "$tmp_detail" 2>/dev/null || echo "")
        local phase
        phase=$(jq -r '.phase // ""' "$tmp_detail" 2>/dev/null || echo "")
        log "[$provider] status=$status phase=$phase (${elapsed}s)"
        case "$status" in
            COMPLETE|FAILED|PARTIAL) cat "$tmp_detail"; return 0 ;;
        esac
        sleep $POLL_INTERVAL
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    # Timeout — return last known detail
    cat "$tmp_detail"
}

# ── Fetch validation report ────────────────────────────────────────────────────
fetch_report() {
    local run_id="$1"
    local elapsed=0
    local resp http_code score

    while [[ $elapsed -lt $REPORT_WAIT ]]; do
        resp=$(curl -s -w "\n__HTTP_CODE__:%{http_code}" "$BASE_URL/api/runs/$run_id/report" -H "Authorization: Bearer $TOKEN")
        http_code=$(echo "$resp" | grep -o '__HTTP_CODE__:[0-9]*' | cut -d: -f2)
        resp=$(echo "$resp" | sed 's/__HTTP_CODE__:[0-9]*//')
        # Report is ready when HTTP 200 and accuracyScore is non-null
        if [[ "$http_code" == "200" ]]; then
            score=$(echo "$resp" | jq -r '.accuracyScore // "null"')
            if [[ "$score" != "null" ]]; then
                echo "$resp"; return 0
            fi
        fi
        sleep 10
        elapsed=$((elapsed + 10))
    done
    echo "{}"
}

# ── Run one provider+spec and collect all 4 dimensions ────────────────────────
run_provider() {
    local provider="$1" spec_label="$2" spec="$3"
    hdr "Provider: $provider  |  Spec: $spec_label"

    # Submit
    local run_id
    run_id=$(submit_run "$provider" "$spec")
    if [[ -z "$run_id" ]]; then
        fail "[$provider] Submit failed"
        ALL_RESULTS+=("{\"provider\":\"$provider\",\"spec\":\"$spec_label\",\"error\":\"SUBMIT_FAILED\"}")
        return
    fi
    ok "[$provider/$spec_label] Run $run_id submitted"
    local start_epoch
    start_epoch=$(date +%s)

    # ── TIME: measure wall-clock duration ──
    local tmp_detail="/tmp/poll_${run_id}.json"
    poll_run "$run_id" "$provider" > /dev/null  # polls and writes to tmp file
    local end_epoch
    end_epoch=$(date +%s)
    local wall_seconds=$(( end_epoch - start_epoch ))

    # ── STATUS ──
    local status
    status=$(jq -r '.status // "UNKNOWN"' "$tmp_detail" 2>/dev/null || echo "UNKNOWN")

    # ── COST ──
    local actual_cost estimated_low estimated_high credits charged hard_cap
    actual_cost=$(jq -r '.actualCostUsd      // "null"' "$tmp_detail" 2>/dev/null || echo "null")
    estimated_low=$(jq -r '.costEstimateLow  // "null"' "$tmp_detail" 2>/dev/null || echo "null")
    estimated_high=$(jq -r '.costEstimateHigh // "null"' "$tmp_detail" 2>/dev/null || echo "null")
    credits=$(jq -r '.creditsCharged         // "null"' "$tmp_detail" 2>/dev/null || echo "null")
    hard_cap=$(jq -r '.hardCapUsd            // "null"' "$tmp_detail" 2>/dev/null || echo "null")

    # ── EFFICIENCY: tokens + task counts ──
    local in_tokens out_tokens tasks_total tasks_done tasks_failed tasks_partial
    in_tokens=$(jq -r '.totalInputTokens  // 0' "$tmp_detail" 2>/dev/null || echo "0")
    out_tokens=$(jq -r '.totalOutputTokens // 0' "$tmp_detail" 2>/dev/null || echo "0")
    tasks_total=$(jq -r '.tasks | length // 0' "$tmp_detail" 2>/dev/null || echo "0")
    tasks_done=$(jq -r '[.tasks[]? | select(.status=="COMPLETE")] | length' "$tmp_detail" 2>/dev/null || echo "0")
    tasks_failed=$(jq -r '[.tasks[]? | select(.status=="FAILED")]   | length' "$tmp_detail" 2>/dev/null || echo "0")
    tasks_partial=$(jq -r '[.tasks[]? | select(.status=="PARTIAL")]  | length' "$tmp_detail" 2>/dev/null || echo "0")

    # tokens per task (avoid div/0)
    local tokens_per_task="N/A"
    if [[ "$tasks_total" -gt 0 ]]; then
        local total_tokens=$(( in_tokens + out_tokens ))
        tokens_per_task=$(( total_tokens / tasks_total ))
    fi

    # ── ACCURACY: from validation report ──
    local accuracy_score="N/A" accuracy_grade="N/A"
    local issues_total="N/A" build_breakers="N/A" review_failures="N/A"
    local report_status="SKIPPED"

    if [[ "$SKIP_REPORT" == "0" && ( "$status" == "COMPLETE" || "$status" == "PARTIAL" ) ]]; then
        log "[$provider/$spec_label] Waiting for validation report…"
        local report_json
        report_json=$(fetch_report "$run_id")
        accuracy_score=$(echo "$report_json" | jq -r '.accuracyScore // "N/A"')

        if [[ "$accuracy_score" != "N/A" && "$accuracy_score" != "null" ]]; then
            report_status="COMPLETE"
            accuracy_grade=$(echo "$report_json"   | jq -r '.accuracyGrade   // "N/A"')
            issues_total=$(echo "$report_json"     | jq -r '.issuesCaught    // 0')
            build_breakers=$(echo "$report_json"   | jq -r '.buildBreakers   // 0')
            review_failures=$(echo "$report_json"  | jq -r '.reviewFailures  // 0')
            ok "[$provider/$spec_label] Report: accuracy=$accuracy_score ($accuracy_grade), issues=$issues_total (build=$build_breakers, review=$review_failures)"
        else
            report_status="NOT_GENERATED"
            warn "[$provider/$spec_label] Report not ready within ${REPORT_WAIT}s"
        fi
    fi

    # ── Print per-provider summary ──
    echo ""
    echo -e "${BOLD}  ┌─ $provider / $spec_label ──────────────────────────────┐${NC}"
    printf "  │  %-22s  %s\n"  "Status:"           "$status"
    printf "  │\n"
    echo -e "  │  ${CYAN}TIME${NC}"
    printf "  │  %-22s  %ds\n"  "Wall clock:"       "$wall_seconds"
    printf "  │\n"
    echo -e "  │  ${CYAN}COST${NC}"
    printf "  │  %-22s  \$%s\n" "Actual cost:"      "$actual_cost"
    printf "  │  %-22s  \$%s – \$%s\n" "Estimated range:"  "$estimated_low" "$estimated_high"
    printf "  │  %-22s  %s credits (cap \$%s)\n" "Credits charged:" "$credits" "$hard_cap"
    printf "  │\n"
    echo -e "  │  ${CYAN}EFFICIENCY${NC}"
    printf "  │  %-22s  %s in / %s out\n" "Tokens:"       "$in_tokens" "$out_tokens"
    printf "  │  %-22s  %s\n"  "Tokens/task:"      "$tokens_per_task"
    printf "  │  %-22s  %s total (%s done, %s partial, %s failed)\n" \
           "Tasks:"  "$tasks_total" "$tasks_done" "$tasks_partial" "$tasks_failed"
    printf "  │\n"
    echo -e "  │  ${CYAN}ACCURACY${NC}"
    printf "  │  %-22s  %s (%s)\n"  "Spec adherence:"  "$accuracy_score" "$accuracy_grade"
    printf "  │  %-22s  %s total (%s build, %s review)\n" \
           "Issues found:"  "$issues_total" "$build_breakers" "$review_failures"
    printf "  │  %-22s  %s\n"  "Report status:"   "$report_status"
    echo -e "  └────────────────────────────────────────────────────────┘"
    echo ""

    # Emit status color
    case "$status" in
        COMPLETE) ok  "[$provider/$spec_label] COMPLETE" ;;
        PARTIAL)  warn "[$provider/$spec_label] PARTIAL" ;;
        *)        fail "[$provider/$spec_label] $status" ;;
    esac

    # Collect JSON for file output
    local result_json
    result_json=$(jq -n \
        --arg provider "$provider" \
        --arg spec "$spec_label" \
        --arg run_id "$run_id" \
        --arg status "$status" \
        --argjson wall_seconds "$wall_seconds" \
        --arg actual_cost "$actual_cost" \
        --arg estimated_low "$estimated_low" \
        --arg estimated_high "$estimated_high" \
        --arg credits "$credits" \
        --arg hard_cap "$hard_cap" \
        --argjson in_tokens "$in_tokens" \
        --argjson out_tokens "$out_tokens" \
        --argjson tasks_total "$tasks_total" \
        --argjson tasks_done "$tasks_done" \
        --argjson tasks_failed "$tasks_failed" \
        --argjson tasks_partial "$tasks_partial" \
        --arg accuracy_score "$accuracy_score" \
        --arg accuracy_grade "$accuracy_grade" \
        --arg issues_total "$issues_total" \
        --arg build_breakers "$build_breakers" \
        --arg review_failures "$review_failures" \
        --arg report_status "$report_status" \
        --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        '{
            provider: $provider,
            spec: $spec,
            run_id: $run_id,
            timestamp: $timestamp,
            status: $status,
            time: {
                wall_seconds: $wall_seconds
            },
            cost: {
                actual_usd: $actual_cost,
                estimated_low_usd: $estimated_low,
                estimated_high_usd: $estimated_high,
                credits_charged: $credits,
                hard_cap_usd: $hard_cap
            },
            efficiency: {
                input_tokens: $in_tokens,
                output_tokens: $out_tokens,
                tasks_total: $tasks_total,
                tasks_done: $tasks_done,
                tasks_failed: $tasks_failed,
                tasks_partial: $tasks_partial
            },
            accuracy: {
                score: $accuracy_score,
                grade: $accuracy_grade,
                issues_total: $issues_total,
                build_breakers: $build_breakers,
                review_failures: $review_failures,
                report_status: $report_status
            }
        }')

    ALL_RESULTS+=("$result_json")
}

# ── Comparison table ───────────────────────────────────────────────────────────
print_comparison_table() {
    hdr "Comparison Table"
    printf "${BOLD}  %-12s  %-10s  %-8s  %-6s  %-12s  %-10s  %-14s  %-8s  %-10s${NC}\n" \
        "Provider" "Spec" "Status" "Time" "Cost(actual)" "Credits" "Tokens(in+out)" "Accuracy" "Issues"

    for result in "${ALL_RESULTS[@]}"; do
        local p spec status t cost cr tok acc iss
        p=$(echo "$result"      | jq -r '.provider')
        spec=$(echo "$result"   | jq -r '.spec // "unknown"')
        status=$(echo "$result" | jq -r '.status // "ERROR"')
        t=$(echo "$result"      | jq -r '.time.wall_seconds // 0')
        cost=$(echo "$result"   | jq -r '.cost.actual_usd // "null"')
        cr=$(echo "$result"     | jq -r '.cost.credits_charged // "null"')
        local in_t out_t
        in_t=$(echo "$result"   | jq -r '.efficiency.input_tokens // 0')
        out_t=$(echo "$result"  | jq -r '.efficiency.output_tokens // 0')
        tok="${in_t}+${out_t}"
        local sc grade
        sc=$(echo "$result"     | jq -r '.accuracy.score // "N/A"')
        grade=$(echo "$result"  | jq -r '.accuracy.grade // "N/A"')
        acc="${sc}(${grade})"
        iss=$(echo "$result"    | jq -r '.accuracy.issues_total // "N/A"')

        local color="$NC"
        [[ "$status" == "COMPLETE" ]] && color="$GREEN"
        [[ "$status" == "PARTIAL"  ]] && color="$YELLOW"
        [[ "$status" == "FAILED"   ]] && color="$RED"

        printf "  ${color}%-12s  %-10s  %-8s  %-6s  %-12s  %-10s  %-14s  %-8s  %-10s${NC}\n" \
            "$p" "$spec" "$status" "${t}s" "\$$cost" "${cr}cr" "$tok" "$acc" "$iss"
    done
    echo ""
}

# ── Main ───────────────────────────────────────────────────────────────────────
hdr "ExecuteSpec — Provider Eval (cost · time · accuracy · efficiency)"
auth_login

for PROVIDER in $PROVIDERS; do
    if [[ "$SPEC_TYPE" == "single" || "$SPEC_TYPE" == "both" ]]; then
        run_provider "$PROVIDER" "single-shot" "$SPEC_SINGLE"
    fi
    if [[ "$SPEC_TYPE" == "multi" || "$SPEC_TYPE" == "both" ]]; then
        run_provider "$PROVIDER" "multi-task" "$SPEC_MULTI"
    fi
done

print_comparison_table

# ── Write JSON results file ────────────────────────────────────────────────────
printf '[' > "$RESULTS_FILE"
first=1
for result in "${ALL_RESULTS[@]}"; do
    [[ $first -eq 0 ]] && printf ',' >> "$RESULTS_FILE"
    printf '%s' "$result" >> "$RESULTS_FILE"
    first=0
done
printf ']' >> "$RESULTS_FILE"

log "Results written to $RESULTS_FILE"
hdr "Done"
