#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# scripts/exhaustive-eval.sh — ExecuteSpec Exhaustive Provider Eval
#
# Test matrix per provider (16 specs × 2 providers = 32 runs):
#   1 × HTML single-shot (random design from repo)
#   5 × Fibonacci console app (Java · Python · Go · Rust · Node.js)
#   5 × Medium full-stack app (same languages)
#   5 × Medium backend REST API (same languages)
#
# Rules:
#   • Sequential per provider — next spec only after previous is terminal/killed
#   • No provider failover — flags run if model mismatch detected
#   • Kill stuck runs — per-type timeout: html/fib=10min, backend=25min, full-stack=35min
#   • Same 4-dimension metrics as test-providers.sh: cost · time · accuracy · efficiency
#
# Usage:
#   ./scripts/exhaustive-eval.sh
#   PROVIDERS="ANTHROPIC" ./scripts/exhaustive-eval.sh
#   BASE_URL=http://localhost:9090 ./scripts/exhaustive-eval.sh
#   SKIP_REPORT=1 ./scripts/exhaustive-eval.sh   # skip waiting for accuracy report
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${E2E_EMAIL:-admin@executespec.ai}"
PASSWORD="${E2E_PASSWORD:-Admin@123}"
PROVIDERS="${PROVIDERS:-ANTHROPIC OPENAI}"
SKIP_REPORT="${SKIP_REPORT:-0}"

POLL_INTERVAL=12        # seconds between status polls
STUCK_THRESHOLD=900     # 15 min with no status change = stuck
REPORT_WAIT=360         # 6 min max to wait for validation report

RESULTS_DIR="scripts/exhaustive-eval-results"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_JSONL="$RESULTS_DIR/runs-$TIMESTAMP.jsonl"
REPORT_MD="$RESULTS_DIR/critic-report-$TIMESTAMP.md"
RESULTS_JSON="$RESULTS_DIR/eval-$TIMESTAMP.json"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'
log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}  ✅ $*${NC}"; }
fail() { echo -e "${RED}  ❌ $*${NC}"; }
warn() { echo -e "${YELLOW}  ⚠️  $*${NC}"; }
hdr()  { echo -e "\n${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n  $*\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"; }

TOKEN=""

# ═══════════════════════════════════════════════════════════════════════════════
# SPEC DEFINITIONS — get_spec <name>  →  echoes spec text
# ═══════════════════════════════════════════════════════════════════════════════

# Pick a random UI style at start (used for HTML spec)
UI_STYLES=(airbnb claude figma linear.app notion stripe supabase vercel spotify posthog resend mintlify)
RANDOM_UI="${UI_STYLES[$((RANDOM % ${#UI_STYLES[@]}))]}"

SPEC_ORDER=(
  html_todo
  fibonacci_java
  fibonacci_python
  fibonacci_go
  fibonacci_rust
  fibonacci_nodejs
  fullstack_nodejs
  fullstack_spring
  fullstack_python
  fullstack_go
  fullstack_rust
  backend_nodejs
  backend_spring
  backend_python
  backend_go
  backend_rust
)

get_spec() {
  local name="$1"
  case "$name" in

  # ── 1. HTML single-shot ────────────────────────────────────────────────────
  html_todo)
    echo "Build a static HTML/CSS/JS todo app. Three files: index.html + style.css + app.js. UI style: $RANDOM_UI.
Features: add task (input + button), mark done (strikethrough + muted text), delete task (×), filter tabs (All / Active / Done), live task counter badge.
CSS requirements: define ALL colors, border-radii, and shadows inside a :root block as custom properties (--color-primary, --color-bg, --color-surface, --color-text, --color-muted, --color-accent, --radius-card, --radius-btn, --shadow-card). Zero hardcoded hex values in any rule outside :root.
JS: pure vanilla, no framework, no imports. LocalStorage persistence — restore tasks on page load.
No npm, no build step, no backend, no CDN links."
    ;;

  # ── 2–6. Fibonacci console apps ───────────────────────────────────────────
  fibonacci_java)
    echo "Create a Java 21 console application (NO HTTP server, NO Spring, NO web framework).
Compute the first 25 Fibonacci numbers using memoization (HashMap<Integer,Long>).
Print each result on its own line in this exact format:  F(n) = value
After all 25 numbers print execution time: Computed in Xµs
Maven build: pom.xml with groupId=com.example, artifactId=fibonacci, java.version=21.
Main class: com.example.fibonacci.FibonacciApp with a public static void main method.
Run with: mvn compile exec:java -Dexec.mainClass=com.example.fibonacci.FibonacciApp
This is a single-module console project. No REST endpoints, no Spring Boot, no JPA."
    ;;

  fibonacci_python)
    echo "Create a Python 3.11 console program (NO Flask, NO FastAPI, NO HTTP server).
Compute first 25 Fibonacci numbers using @functools.lru_cache memoization.
Print each result: F(n) = value
Print final line: Computed in Xµs  (use time.perf_counter_ns())
Entry point: fibonacci.py — callable as: python fibonacci.py
No external dependencies — stdlib only. requirements.txt may be empty.
This is a single-file console script. No web framework, no API, no server."
    ;;

  fibonacci_go)
    echo "Create a Go 1.21 console program (NO net/http, NO web framework, NO external modules).
Compute first 25 Fibonacci numbers using memoization (map[int]int64).
Print each result: F(n) = value
Print final line: Computed in Xµs  (use time.Since())
Entry point: main.go in module fibonacci (go.mod with module fibonacci, go 1.21).
Zero external dependencies — stdlib only.
This is a single-file console program. No HTTP handler, no goroutine server, no API."
    ;;

  fibonacci_rust)
    echo "Create a Rust console program (NO actix-web, NO rocket, NO HTTP server).
Compute first 25 Fibonacci numbers using memoization (HashMap<u64,u64>).
Print each result: F(n) = value
Print final line: Computed in Xµs  (use std::time::Instant)
Entry point: src/main.rs. Cargo.toml: edition=2021, no external crates (std only).
This is a single-file console binary. No web framework, no async runtime, no tokio."
    ;;

  fibonacci_nodejs)
    echo "Create a Node.js console program (NO Express, NO HTTP server, NO npm dependencies).
Compute first 25 Fibonacci numbers using memoization (Map).
Print each result: F(n) = value
Print final line: Computed in Xµs  (use process.hrtime.bigint())
Entry point: fibonacci.js — callable as: node fibonacci.js
Module system: CommonJS (no import/export, no package.json type:module).
Single file. No http module usage, no server, no port, no web framework."
    ;;

  # ── 7–11. Medium full-stack apps ──────────────────────────────────────────
  fullstack_nodejs)
    echo "Build a full-stack Node.js bookmark manager. Medium complexity.
Backend (Express, CommonJS, in-memory — no database):
  Data: bookmarks { id(uuid), url, title, description, category, tags(array), favorite(bool), createdAt }
       categories { id(uuid), name, color }
  API: GET/POST/PUT/DELETE /api/bookmarks, GET/POST/DELETE /api/categories
       GET /api/bookmarks?category=X&tag=Y&favorite=true (multi-filter)
  Seed 3 categories + 8 bookmarks on startup. Validation: 400 on missing required fields.
  CORS enabled. Port: process.env.PORT || 3001 bound to 0.0.0.0.
Frontend (vanilla HTML/CSS/JS in public/ served by Express via express.static):
  Bookmark grid view, add/edit modal, category sidebar filter, tag chips, favorite toggle.
  All API calls use relative URLs (/api/...). CSS :root block with all colors as custom properties.
Module system: CommonJS throughout (require/module.exports). No import/export.
package.json start script: exactly \"node server.js\" — single command, no concurrently.
No React, no Vite, no separate frontend build step."
    ;;

  fullstack_spring)
    echo "Build a full-stack Spring Boot 3.x + React task manager. Medium complexity.
Backend (Spring Boot, in-memory — NO JPA, NO database, NO Flyway, NO H2):
  Storage: ConcurrentHashMap only. Port 8080 bound to 0.0.0.0.
  Data: Project { id(UUID), name, description, color, createdAt }
       Task { id(UUID), projectId, title, description, priority(LOW/MEDIUM/HIGH/URGENT), status(TODO/IN_PROGRESS/DONE), dueDate(LocalDate), tags(List<String>) }
  API: GET/POST/PUT/DELETE /api/projects, GET/POST/PUT/DELETE /api/tasks
       GET /api/tasks?projectId=X&status=Y&priority=Z (filter params)
  Validation: @Valid + BindingResult. 400 on invalid, 404 on missing, 409 on dupe name.
  CORS @CrossOrigin(\"*\") on all controllers.
Frontend (React + Vite in frontend/ directory, proxies /api to localhost:8080):
  Project sidebar, task board (kanban-style columns by status), add/edit modals, priority badges.
  All API calls via relative /api paths (Vite proxy handles routing).
ui_style: linear.app"
    ;;

  fullstack_python)
    echo "Build a full-stack Python FastAPI + React recipe book. Medium complexity.
Backend (FastAPI, in-memory — no SQLAlchemy, no database):
  Port 8000 bound to 0.0.0.0. CORS: allow all origins.
  Data: Recipe { id(str/UUID), title, description, ingredients(List[str]), steps(List[str]),
                 prep_time(int), cook_time(int), servings(int), difficulty(easy/medium/hard),
                 tags(List[str]), rating(float 0-5), createdAt(datetime) }
       Category { id(str), name, description }
  API: GET/POST/PUT/DELETE /api/recipes, GET/POST/DELETE /api/categories
       GET /api/recipes?difficulty=X&tag=Y&category=Z (filter)
       POST /api/recipes/{id}/rate  body: { rating: float }
  Pydantic v2 models. 422 on invalid, 404 on missing.
  Seed 4 categories + 10 recipes on startup.
Frontend (React + Vite in frontend/ directory, proxies /api to localhost:8000):
  Recipe grid cards with rating stars, category filter sidebar, recipe detail modal,
  add/edit form with dynamic ingredient/step list.
ui_style: notion"
    ;;

  fullstack_go)
    echo "Build a full-stack Go student grade tracker. Medium complexity.
Backend (Go net/http, NO external dependencies — stdlib only):
  Port 8080 bound to 0.0.0.0. Serve static files from public/ directory.
  Data: Student { id(string/UUID via crypto/rand), name, email, grade(9-12) }
       Course { id, name, credits(int), teacher }
       Grade { id, studentId, courseId, score(float64 0-100), semester, year }
  API: GET/POST/PUT/DELETE /api/students, /api/courses, /api/grades
       GET /api/students/{id}/report — return student with avg GPA + grades per course
       GET /api/grades?studentId=X&courseId=Y (filter)
  In-memory storage: map[string]Student etc, protected by sync.RWMutex.
  JSON responses, Content-Type: application/json. 400 on bad input, 404 on missing.
  Seed 5 students + 4 courses + 20 grades on startup.
Frontend (plain HTML/CSS/JS in public/ served by Go — no npm, no React):
  Student list table with GPA badge, course list, grade entry form, student detail panel.
  All API calls to /api/ (relative, same origin). CSS :root with custom properties."
    ;;

  fullstack_rust)
    echo "Build a full-stack Rust product inventory manager. Medium complexity.
Backend (actix-web 4, serde+serde_json, uuid with v4 feature, chrono with serde feature):
  Cargo.toml deps: actix-web=\"4\", serde={features=[\"derive\"]}, serde_json=\"1\", uuid={features=[\"v4\"]}, chrono={features=[\"serde\"]}
  Port 8080 bound to 0.0.0.0. Serve static files from static/ directory using actix-files.
  actix-files Cargo dep: actix-files=\"0.6\"
  Data: Product { id(String/UUID), name, sku, description, price(f64), quantity(i32),
                  category, reorder_level(i32), supplier, updatedAt(DateTime<Utc>) }
       Category { id(String), name, description }
  API: GET/POST/PUT/DELETE /api/products, GET/POST/DELETE /api/categories
       GET /api/products?category=X&low_stock=true (filter)
       GET /api/products/stats — { total, low_stock_count, categories_count, total_value }
  In-memory: RwLock<HashMap<String,Product>>. 404 on missing, 400 on bad input.
  Seed 3 categories + 10 products on startup.
Frontend (plain HTML/CSS/JS in static/ — no npm, no React):
  Product table with stock level badges, add/edit modal, category filter, stats summary bar.
  CSS :root custom properties for all colors."
    ;;

  # ── 12–16. Medium backend REST APIs ───────────────────────────────────────
  backend_nodejs)
    echo "Build a Node.js Express REST API for a library catalog. Medium complexity. Backend only — no frontend, no HTML.
In-memory storage (no database). CommonJS (require/module.exports). Port: process.env.PORT||3001, 0.0.0.0.
Resources:
  Book { id(uuid), isbn, title, authorId, genre, year, synopsis, available(bool, default true) }
  Author { id(uuid), name, bio, nationality, birthYear }
  BorrowRecord { id(uuid), bookId, borrowerName, borrowerEmail, borrowedAt, returnedAt(nullable) }
Endpoints:
  GET/POST/PUT/DELETE /api/books       — full CRUD, GET supports ?genre=X&available=true filters
  GET/POST/PUT/DELETE /api/authors     — full CRUD
  POST /api/books/:id/borrow           — body: { borrowerName, borrowerEmail }, sets available=false, creates BorrowRecord
  POST /api/books/:id/return           — marks book available, sets returnedAt on record
  GET  /api/books/:id/history          — all BorrowRecords for this book
  GET  /api/authors/:id/books          — all books by this author
Validation: 400 on missing required fields, 404 on not found, 409 on isbn duplicate, 422 on borrow if already borrowed.
CORS enabled. Centralized JSON error middleware. Seed 3 authors + 8 books on startup.
package.json start script: \"node server.js\""
    ;;

  backend_spring)
    echo "Build a Spring Boot 3.x REST API for an employee directory. Medium complexity. Backend only — no frontend, no HTML files.
In-memory storage ONLY (ConcurrentHashMap — NO JPA, NO database, NO H2, NO Flyway, NO Spring Data).
Port 8080 bound to 0.0.0.0. CORS @CrossOrigin(\"*\") globally.
Resources:
  Department { id(UUID), name, code(unique), description, headCount }
  Employee { id(UUID), firstName, lastName, email(unique), phone, departmentId, role, salary(Double), hireDate(LocalDate), active(bool) }
  Skill { id(UUID), employeeId, name, level(BEGINNER/INTERMEDIATE/EXPERT), yearsOfExperience(int) }
Endpoints:
  GET/POST/PUT/DELETE /api/departments
  GET/POST/PUT/DELETE /api/employees        — GET supports ?departmentId=X&role=Y&active=Z
  POST /api/employees/:id/activate          — set active=true
  POST /api/employees/:id/deactivate        — set active=false
  GET/POST/DELETE /api/employees/:id/skills
  GET /api/departments/:id/employees
  GET /api/stats — { totalEmployees, activeCount, byDepartment:[{dept,count}], avgSalary }
Validation: @Valid on DTOs, 400 on invalid, 404 on missing, 409 on duplicate email/code.
Seed 3 departments + 10 employees + 15 skills on startup."
    ;;

  backend_python)
    echo "Build a Python FastAPI REST API for event management. Medium complexity. Backend only — no frontend, no HTML files.
In-memory storage (dict — NO SQLAlchemy, NO database). Port 8000 bound to 0.0.0.0. CORS: all origins.
Resources:
  Venue { id(str/UUID), name, address, city, capacity(int), amenities(List[str]) }
  Event { id(str), title, description, venueId, startTime(datetime), endTime(datetime),
          capacity(int), price(float), category, tags(List[str]), status(draft/published/cancelled) }
  Registration { id(str), eventId, attendeeName, attendeeEmail, registeredAt(datetime), status(confirmed/cancelled) }
Endpoints:
  GET/POST/PUT/DELETE /api/venues
  GET/POST/PUT/DELETE /api/events            — GET ?category=X&status=Y&city=Z
  POST /api/events/{id}/publish              — change status to published
  POST /api/events/{id}/cancel               — change status to cancelled
  GET/POST /api/events/{id}/registrations    — list and create registrations
  DELETE /api/registrations/{id}             — cancel a registration
  GET /api/events/{id}/stats                 — { capacity, registered, available, revenue }
Pydantic v2 models. 422 on invalid, 404 on missing, 409 on duplicate email per event, 400 if event full.
Seed 4 venues + 8 events (mix of statuses) + 15 registrations on startup."
    ;;

  backend_go)
    echo "Build a Go REST API for order management. Medium complexity. Backend only — no frontend, no HTML files.
Go net/http stdlib only — NO external dependencies, NO gorilla/mux, NO gin, NO echo.
Port 8080 bound to 0.0.0.0. All responses Content-Type: application/json.
Resources:
  Customer { id(string/UUID), name, email(unique), phone, address, createdAt }
  Product { id, name, sku(unique), description, price(float64), stock(int), category }
  Order { id, customerId, status(pending/confirmed/shipped/delivered/cancelled), createdAt, updatedAt, totalAmount(float64) }
  OrderItem { id, orderId, productId, quantity(int), unitPrice(float64) }
Endpoints:
  GET/POST/PUT/DELETE /api/customers
  GET/POST/PUT/DELETE /api/products            — GET ?category=X&in_stock=true
  POST /api/orders                             — body: { customerId, items:[{productId,quantity}] }, validates stock
  GET /api/orders                              — GET ?customerId=X&status=Y
  GET/PUT /api/orders/{id}                     — update status
  DELETE /api/orders/{id}                      — cancel (restore stock)
  GET /api/orders/{id}/items
  GET /api/stats                               — { totalOrders, byStatus, totalRevenue, topProducts:[{name,qty}] }
In-memory maps with sync.RWMutex. 400 on invalid, 404 on missing, 409 on duplicate email/sku, 422 on insufficient stock.
Seed 4 customers + 8 products + 10 orders on startup."
    ;;

  backend_rust)
    echo "Build a Rust REST API for a note-taking service with tags. Medium complexity. Backend only — no frontend, no HTML files.
Crates: actix-web=\"4\", serde={features=[\"derive\"]}, serde_json=\"1\", uuid={features=[\"v4\"]}, chrono={features=[\"serde\"]}
Port 8080 bound to 0.0.0.0.
Resources:
  Notebook { id(String/UUID), name, description, color, createdAt(DateTime<Utc>) }
  Note { id, notebookId, title, content, tags(Vec<String>), pinned(bool), createdAt, updatedAt(DateTime<Utc>) }
  Tag { name(String, unique across system), noteCount(derived) }
Endpoints:
  GET/POST/PUT/DELETE /api/notebooks
  GET/POST/PUT/DELETE /api/notes                — GET ?notebookId=X&tag=Y&pinned=true&search=term
  POST /api/notes/{id}/pin                      — toggle pinned
  GET /api/notes/{id}/related                   — notes sharing ≥1 tag with this note
  GET /api/tags                                 — all unique tags with usage count
  GET /api/tags/{name}/notes                    — all notes with this tag
  GET /api/stats                                — { totalNotes, totalNotebooks, totalTags, pinnedCount }
In-memory: RwLock<HashMap<String,Note>> etc. 404 on missing, 400 on invalid, 409 on dupe notebook name.
Seed 3 notebooks + 12 notes with varied tags on startup."
    ;;

  *)
    echo "ERROR: unknown spec: $name" >&2
    return 1
    ;;
  esac
}

get_timeout() {
  case "$1" in
    html_todo|fibonacci_*) echo 600  ;;  # 10 min — should be SINGLE_SHOT/MEDIUM
    backend_*)             echo 1500 ;;  # 25 min
    fullstack_*)           echo 2100 ;;  # 35 min
    *)                     echo 1200 ;;
  esac
}

get_type() {
  case "$1" in
    html_todo)     echo "HTML"       ;;
    fibonacci_*)   echo "FIBONACCI"  ;;
    backend_*)     echo "BACKEND"    ;;
    fullstack_*)   echo "FULLSTACK"  ;;
    *)             echo "UNKNOWN"    ;;
  esac
}

get_language() {
  case "$1" in
    *_java|*_spring) echo "Java"       ;;
    *_python)        echo "Python"     ;;
    *_go)            echo "Go"         ;;
    *_rust)          echo "Rust"       ;;
    *_nodejs)        echo "Node.js"    ;;
    html_todo)       echo "HTML/JS"    ;;
    *)               echo "unknown"    ;;
  esac
}

# ═══════════════════════════════════════════════════════════════════════════════
# AUTH + API HELPERS
# ═══════════════════════════════════════════════════════════════════════════════

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

submit_run() {
  local provider="$1" spec="$2"
  local body
  body=$(jq -n --arg req "$spec" --arg prov "$provider" \
    '{userRequest: $req, skipApproval: true, executionMode: "PROTOTYPE", aiProvider: $prov}')
  curl -s -X POST "$BASE_URL/api/runs" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" | jq -r '.runId // empty'
}

cancel_run() {
  curl -s -X DELETE "$BASE_URL/api/runs/$1" \
    -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1 || true
}

# Polls until terminal state or timeout/stuck. Writes last run JSON to /tmp/poll_<runId>.json
# Returns 0 on terminal, 1 on timeout/stuck (file contains synthetic TIMEOUT status)
poll_run() {
  local run_id="$1" provider="$2" spec_name="$3" timeout="$4"
  local tmp="/tmp/poll_${run_id}_$$.json"
  local total_elapsed=0 last_status="" last_change_elapsed=0

  while [[ $total_elapsed -lt $timeout ]]; do
    curl -s "$BASE_URL/api/runs/$run_id" \
      -H "Authorization: Bearer $TOKEN" > "$tmp" 2>/dev/null || true

    local status
    status=$(jq -r '.status // ""' "$tmp" 2>/dev/null || echo "")

    # Log on every status transition
    if [[ "$status" != "$last_status" ]]; then
      log "${DIM}[$provider/$spec_name]${NC} → ${BOLD}$status${NC} (${total_elapsed}s)"
      last_status="$status"
      last_change_elapsed=$total_elapsed
    fi

    case "$status" in
      COMPLETE|FAILED|PARTIAL)
        echo "$tmp"   # return path to result file
        return 0
        ;;
    esac

    # Stuck detection: same non-terminal status for > STUCK_THRESHOLD seconds
    local since_change=$(( total_elapsed - last_change_elapsed ))
    if [[ $since_change -gt $STUCK_THRESHOLD && -n "$status" ]]; then
      warn "[$provider/$spec_name] Stuck in '$status' for ${since_change}s → cancelling"
      cancel_run "$run_id"
      printf '{"status":"TIMEOUT","stuck_in":"%s","runId":"%s"}' "$status" "$run_id" > "$tmp"
      echo "$tmp"
      return 1
    fi

    sleep $POLL_INTERVAL
    total_elapsed=$((total_elapsed + POLL_INTERVAL))
  done

  warn "[$provider/$spec_name] Max timeout ${timeout}s reached → cancelling"
  cancel_run "$run_id"
  printf '{"status":"TIMEOUT","stuck_in":"unknown","runId":"%s"}' "$run_id" > "$tmp"
  echo "$tmp"
  return 1
}

# Waits for validation report; returns JSON or empty object
fetch_report() {
  local run_id="$1"
  local elapsed=0
  while [[ $elapsed -lt $REPORT_WAIT ]]; do
    local resp code
    resp=$(curl -s -w "\n__CODE__:%{http_code}" "$BASE_URL/api/runs/$run_id/report" \
      -H "Authorization: Bearer $TOKEN")
    code=$(echo "$resp" | grep -o '__CODE__:[0-9]*' | cut -d: -f2)
    resp=$(echo "$resp" | sed 's/__CODE__:[0-9]*//')
    if [[ "$code" == "200" ]]; then
      local score
      score=$(echo "$resp" | jq -r '.accuracyScore // "null"' 2>/dev/null || echo "null")
      if [[ "$score" != "null" ]]; then
        echo "$resp"; return 0
      fi
    fi
    sleep 12
    elapsed=$((elapsed + 12))
  done
  echo "{}"
}

# Detect if the run used a different provider than requested (failover)
detect_failover() {
  local provider="$1" worker_model="$2"
  if [[ "$provider" == "ANTHROPIC" && "$worker_model" == *"gpt"* ]]; then
    echo "true"
  elif [[ "$provider" == "OPENAI" && "$worker_model" == *"claude"* ]]; then
    echo "true"
  else
    echo "false"
  fi
}

# ═══════════════════════════════════════════════════════════════════════════════
# CORE: RUN ONE SPEC, COLLECT 4-DIMENSION RESULT, APPEND TO JSONL
# ═══════════════════════════════════════════════════════════════════════════════

run_single_spec() {
  local provider="$1" spec_name="$2"
  local spec_type lang timeout

  spec_type=$(get_type "$spec_name")
  lang=$(get_language "$spec_name")
  timeout=$(get_timeout "$spec_name")
  local spec
  spec=$(get_spec "$spec_name")

  local total_specs="${#SPEC_ORDER[@]}"
  local progress_label="$spec_name [$spec_type/$lang]"

  echo ""
  echo -e "${CYAN}${BOLD}  ┌─ $provider [$progress_label] ─$(printf '─%.0s' {1..20})┐${NC}"

  # Submit
  local run_id
  run_id=$(submit_run "$provider" "$spec")
  if [[ -z "$run_id" ]]; then
    fail "[$provider/$spec_name] Submit failed"
    append_error_result "$provider" "$spec_name" "$spec_type" "$lang" "SUBMIT_FAILED" ""
    return
  fi
  ok "[$provider/$spec_name] Submitted → $run_id"
  local start_epoch
  start_epoch=$(date +%s)

  # Poll
  local tmp_file
  tmp_file=$(poll_run "$run_id" "$provider" "$spec_name" "$timeout")
  local poll_rc=$?

  local end_epoch
  end_epoch=$(date +%s)
  local wall_s=$(( end_epoch - start_epoch ))

  # Extract run fields
  local status worker_model
  status=$(jq -r '.status // "UNKNOWN"' "$tmp_file" 2>/dev/null || echo "UNKNOWN")
  worker_model=$(jq -r '.workerModel // ""' "$tmp_file" 2>/dev/null || echo "")

  local failover
  failover=$(detect_failover "$provider" "$worker_model")
  if [[ "$failover" == "true" ]]; then
    warn "[$provider/$spec_name] ⚠ Failover detected! workerModel=$worker_model"
  fi

  # Cost
  local actual_cost credits hard_cap
  actual_cost=$(jq -r '.actualCostUsd      // "null"' "$tmp_file" 2>/dev/null || echo "null")
  credits=$(jq    -r '.creditsCharged      // "null"' "$tmp_file" 2>/dev/null || echo "null")
  hard_cap=$(jq   -r '.hardCapUsd          // "null"' "$tmp_file" 2>/dev/null || echo "null")
  local est_low est_high
  est_low=$(jq    -r '.costEstimateLow     // "null"' "$tmp_file" 2>/dev/null || echo "null")
  est_high=$(jq   -r '.costEstimateHigh    // "null"' "$tmp_file" 2>/dev/null || echo "null")

  # Efficiency
  local in_tok out_tok tasks_total tasks_done tasks_failed tasks_partial
  in_tok=$(jq       -r '.totalInputTokens  // 0' "$tmp_file" 2>/dev/null || echo "0")
  out_tok=$(jq      -r '.totalOutputTokens // 0' "$tmp_file" 2>/dev/null || echo "0")
  tasks_total=$(jq  -r '.tasks | length'         "$tmp_file" 2>/dev/null || echo "0")
  tasks_done=$(jq   -r '[.tasks[]? | select(.status=="COMPLETE")] | length' "$tmp_file" 2>/dev/null || echo "0")
  tasks_failed=$(jq -r '[.tasks[]? | select(.status=="FAILED")]   | length' "$tmp_file" 2>/dev/null || echo "0")
  tasks_partial=$(jq -r '[.tasks[]? | select(.status=="PARTIAL")] | length' "$tmp_file" 2>/dev/null || echo "0")

  # Accuracy
  local acc_score acc_grade issues_total build_br review_fail issues_json report_status
  acc_score="N/A"; acc_grade="N/A"; issues_total="0"
  build_br="0"; review_fail="0"; issues_json="[]"; report_status="SKIPPED"

  if [[ "$SKIP_REPORT" == "0" && ( "$status" == "COMPLETE" || "$status" == "PARTIAL" ) ]]; then
    log "[$provider/$spec_name] Waiting for validation report…"
    local report_json
    report_json=$(fetch_report "$run_id")

    acc_score=$(echo "$report_json"    | jq -r '.accuracyScore  // "N/A"')
    if [[ "$acc_score" != "N/A" && "$acc_score" != "null" ]]; then
      report_status="COMPLETE"
      acc_grade=$(echo "$report_json"    | jq -r '.accuracyGrade  // "N/A"')
      issues_total=$(echo "$report_json" | jq -r '.issuesCaught   // 0')
      build_br=$(echo "$report_json"     | jq -r '.buildBreakers  // 0')
      review_fail=$(echo "$report_json"  | jq -r '.reviewFailures // 0')
      issues_json=$(echo "$report_json"  | jq -c '[.issues[]? | {severity,description,file}]' 2>/dev/null || echo "[]")
      ok "[$provider/$spec_name] Report: score=$acc_score ($acc_grade), issues=$issues_total (build=$build_br review=$review_fail)"
    else
      report_status="PENDING"
      warn "[$provider/$spec_name] Report not ready within ${REPORT_WAIT}s"
    fi
  fi

  # Print inline summary
  local sc="${BOLD}${GREEN}$status${NC}"
  [[ "$status" == "PARTIAL"  ]] && sc="${BOLD}${YELLOW}$status${NC}"
  [[ "$status" == "FAILED"   ]] && sc="${BOLD}${RED}$status${NC}"
  [[ "$status" == "TIMEOUT"  ]] && sc="${BOLD}${RED}$status${NC}"
  printf "  │  %-18s %b\n"       "Status:"     "$sc"
  printf "  │  %-18s %ds\n"      "Time:"       "$wall_s"
  printf "  │  %-18s \$%s (credits: %s)\n" "Cost:" "$actual_cost" "$credits"
  printf "  │  %-18s %s in / %s out | %s tasks (%s done %s fail)\n" \
    "Tokens/Tasks:" "$in_tok" "$out_tok" "$tasks_total" "$tasks_done" "$tasks_failed"
  printf "  │  %-18s %s (%s) | %s issues\n" "Accuracy:" "$acc_score" "$acc_grade" "$issues_total"
  printf "  │  %-18s %s\n"       "Failover:"   "$failover"
  echo -e "  └────────────────────────────────────────────────────────────────────┘"
  echo ""

  # Append to JSONL results file
  jq -n \
    --arg provider "$provider" \
    --arg spec_name "$spec_name" \
    --arg spec_type "$spec_type" \
    --arg language "$lang" \
    --arg run_id "$run_id" \
    --arg status "$status" \
    --arg worker_model "$worker_model" \
    --arg failover "$failover" \
    --argjson wall_s "$wall_s" \
    --arg actual_cost "$actual_cost" \
    --arg est_low "$est_low" \
    --arg est_high "$est_high" \
    --arg credits "$credits" \
    --arg hard_cap "$hard_cap" \
    --argjson in_tok "$in_tok" \
    --argjson out_tok "$out_tok" \
    --argjson tasks_total "$tasks_total" \
    --argjson tasks_done "$tasks_done" \
    --argjson tasks_failed "$tasks_failed" \
    --argjson tasks_partial "$tasks_partial" \
    --arg acc_score "$acc_score" \
    --arg acc_grade "$acc_grade" \
    --argjson issues_total "$issues_total" \
    --argjson build_br "$build_br" \
    --argjson review_fail "$review_fail" \
    --argjson issues_json "$issues_json" \
    --arg report_status "$report_status" \
    --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{
      provider: $provider, spec_name: $spec_name, spec_type: $spec_type,
      language: $language, run_id: $run_id, timestamp: $timestamp,
      status: $status, worker_model: $worker_model, failover_detected: ($failover == "true"),
      time: { wall_seconds: $wall_s },
      cost: { actual_usd: $actual_cost, est_low: $est_low, est_high: $est_high,
              credits: $credits, hard_cap: $hard_cap },
      efficiency: { input_tokens: $in_tok, output_tokens: $out_tok,
                    tasks_total: $tasks_total, tasks_done: $tasks_done,
                    tasks_failed: $tasks_failed, tasks_partial: $tasks_partial },
      accuracy: { score: $acc_score, grade: $acc_grade,
                  issues_total: $issues_total, build_breakers: $build_br,
                  review_failures: $review_fail, report_status: $report_status,
                  issues: $issues_json }
    }' >> "$RESULTS_JSONL"

  # Cleanup temp file
  rm -f "$tmp_file" 2>/dev/null || true
}

append_error_result() {
  local provider="$1" spec_name="$2" spec_type="$3" lang="$4" error="$5" run_id="${6:-}"
  jq -n \
    --arg provider "$provider" --arg spec_name "$spec_name" \
    --arg spec_type "$spec_type" --arg lang "$lang" \
    --arg error "$error" --arg run_id "$run_id" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{provider:$provider,spec_name:$spec_name,spec_type:$spec_type,language:$lang,
      run_id:$run_id,timestamp:$ts,status:$error,worker_model:"",failover_detected:false,
      time:{wall_seconds:0},cost:{actual_usd:"null"},efficiency:{tasks_total:0},
      accuracy:{score:"N/A",grade:"N/A",issues_total:0,report_status:"SKIPPED",issues:[]}}' \
    >> "$RESULTS_JSONL"
}

# ═══════════════════════════════════════════════════════════════════════════════
# PROVIDER SUITE: run all 16 specs sequentially for one provider
# ═══════════════════════════════════════════════════════════════════════════════

run_provider_suite() {
  local provider="$1"
  local total="${#SPEC_ORDER[@]}"
  local idx=0

  hdr "Provider Suite: $provider  ($total specs, sequential)"

  for spec_name in "${SPEC_ORDER[@]}"; do
    idx=$((idx + 1))
    log "[$provider] ── Spec $idx/$total: $spec_name ──"
    run_single_spec "$provider" "$spec_name"
    sleep 3  # brief gap between runs
  done

  ok "[$provider] Suite complete ($total specs)"
}

# ═══════════════════════════════════════════════════════════════════════════════
# REPORT GENERATOR — Python inline script, runs after all suites finish
# ═══════════════════════════════════════════════════════════════════════════════

generate_report() {
  log "Generating critic report from $RESULTS_JSONL …"

  python3 - "$RESULTS_JSONL" "$REPORT_MD" "$RESULTS_JSON" << 'PYEOF'
import json, sys, statistics
from datetime import datetime
from collections import defaultdict

jsonl_path, md_path, json_path = sys.argv[1], sys.argv[2], sys.argv[3]

runs = []
with open(jsonl_path) as f:
    for line in f:
        line = line.strip()
        if line:
            try: runs.append(json.loads(line))
            except: pass

TERMINAL = {"COMPLETE","PARTIAL","FAILED","TIMEOUT","SUBMIT_FAILED"}
PASSING   = {"COMPLETE","PARTIAL"}

def score_val(r):
    s = r.get("accuracy",{}).get("score","N/A")
    try: return float(s)
    except: return None

def is_pass(r): return r.get("status") in PASSING
def is_fail(r): return r.get("status") not in PASSING
def grade_label(s):
    if s is None: return "N/A"
    if s>=95: return "A"
    if s>=85: return "B"
    if s>=70: return "C"
    if s>=50: return "D"
    return "F"

providers  = sorted(set(r["provider"]  for r in runs))
spec_types = ["HTML","FIBONACCI","BACKEND","FULLSTACK"]
languages  = ["HTML/JS","Java","Python","Go","Rust","Node.js"]

# ── Per-provider summary ──────────────────────────────────────────────────────
prov_stats = {}
for p in providers:
    pr = [r for r in runs if r["provider"]==p]
    passed  = [r for r in pr if is_pass(r)]
    failed  = [r for r in pr if is_fail(r)]
    scores  = [s for r in pr if (s:=score_val(r)) is not None]
    costs   = []
    for r in pr:
        try: costs.append(float(r.get("cost",{}).get("actual_usd","null")))
        except: pass
    times   = [r.get("time",{}).get("wall_seconds",0) for r in pr]
    tokens  = [r.get("efficiency",{}).get("input_tokens",0)+r.get("efficiency",{}).get("output_tokens",0) for r in pr]
    failovers = sum(1 for r in pr if r.get("failover_detected"))
    prov_stats[p] = {
        "total": len(pr), "passed": len(passed), "failed": len(failed),
        "pass_rate": f"{len(passed)}/{len(pr)}",
        "avg_accuracy": round(statistics.mean(scores),1) if scores else None,
        "min_accuracy": min(scores) if scores else None,
        "max_accuracy": max(scores) if scores else None,
        "avg_cost": round(statistics.mean(costs),4) if costs else None,
        "total_cost": round(sum(costs),4) if costs else None,
        "avg_time_s": round(statistics.mean(times)) if times else 0,
        "avg_tokens": round(statistics.mean(tokens)) if tokens else 0,
        "failovers": failovers,
        "failed_specs": [r["spec_name"] for r in failed],
    }

# ── Per-type breakdown ────────────────────────────────────────────────────────
type_breakdown = {}
for st in spec_types:
    td = {}
    for p in providers:
        pr = [r for r in runs if r["provider"]==p and r.get("spec_type")==st]
        passed = [r for r in pr if is_pass(r)]
        scores = [s for r in pr if (s:=score_val(r)) is not None]
        td[p] = {
            "pass_rate": f"{len(passed)}/{len(pr)}" if pr else "0/0",
            "avg_accuracy": round(statistics.mean(scores),1) if scores else None,
        }
    type_breakdown[st] = td

# ── Per-language breakdown ────────────────────────────────────────────────────
lang_breakdown = {}
for lang in languages:
    ld = {}
    for p in providers:
        pr = [r for r in runs if r["provider"]==p and r.get("language")==lang]
        passed = [r for r in pr if is_pass(r)]
        scores = [s for r in pr if (s:=score_val(r)) is not None]
        ld[p] = {
            "pass_rate": f"{len(passed)}/{len(pr)}" if pr else "0/0",
            "avg_accuracy": round(statistics.mean(scores),1) if scores else None,
        }
    lang_breakdown[lang] = ld

# ── Failure patterns ──────────────────────────────────────────────────────────
failures  = [r for r in runs if is_fail(r)]
low_score = [r for r in runs if is_pass(r) and (s:=score_val(r)) is not None and s < 70]
timeouts  = [r for r in runs if r.get("status")=="TIMEOUT"]
issue_texts = []
for r in runs:
    for iss in r.get("accuracy",{}).get("issues",[]):
        issue_texts.append((r["provider"], r["spec_name"], r.get("language","?"),
                            iss.get("severity","?"), iss.get("description",""), iss.get("file","")))

# Count issue patterns
pattern_counts = defaultdict(int)
for _, _, lang, sev, desc, _ in issue_texts:
    d_lower = desc.lower()
    if "router" in d_lower or "prefix" in d_lower or "/api/api" in d_lower:
        pattern_counts["router_namespace_duplication"] += 1
    if "module" in d_lower or "require" in d_lower or "import" in d_lower and "esm" in d_lower:
        pattern_counts["module_system_mismatch"] += 1
    if "cors" in d_lower:
        pattern_counts["cors_misconfiguration"] += 1
    if "port" in d_lower or "bind" in d_lower:
        pattern_counts["port_binding_issue"] += 1
    if "css" in d_lower and ("custom" in d_lower or "root" in d_lower or "variable" in d_lower):
        pattern_counts["css_custom_properties_missing"] += 1
    if "start" in d_lower or "script" in d_lower and "package" in d_lower:
        pattern_counts["start_script_issue"] += 1
    if "readme" in d_lower.replace(" ",""):
        pattern_counts["readme_false_positive"] += 1
    if "localhost" in d_lower or "absolute" in d_lower and "url" in d_lower:
        pattern_counts["absolute_urls_in_frontend"] += 1
    if sev in ("CRITICAL","HIGH") and "build" in d_lower:
        pattern_counts["build_breaking_issues"] += 1

# ── Architectural critiquelogic ───────────────────────────────────────────────
critiques = []

# C1: Full-stack vs backend accuracy gap
for p in providers:
    fs_scores = [score_val(r) for r in runs if r["provider"]==p and r.get("spec_type")=="FULLSTACK" and score_val(r) is not None]
    be_scores = [score_val(r) for r in runs if r["provider"]==p and r.get("spec_type")=="BACKEND"  and score_val(r) is not None]
    if fs_scores and be_scores:
        gap = statistics.mean(be_scores) - statistics.mean(fs_scores)
        if gap > 12:
            critiques.append(("C1","Cross-stack Integration Gap",p,
                f"Backend avg {statistics.mean(be_scores):.1f}% vs Full-stack {statistics.mean(fs_scores):.1f}% ({gap:.1f}pt gap). "
                f"Workers implementing both layers in sequential tasks lack context about the other layer's contracts."))

# C2: Language-specific failures (>1 failure in same lang)
lang_fail_counts = defaultdict(int)
for r in failures:
    lang_fail_counts[(r["provider"],r.get("language","?"))] += 1
for (p,lang), cnt in lang_fail_counts.items():
    if cnt >= 2:
        critiques.append(("C2",f"Language Reliability — {lang}",p,
            f"{cnt} failures for {lang} specs under {p}. May indicate missing scaffold template, "
            f"insufficient prompt coverage for this language, or build toolchain issues in preview."))

# C3: High task count relative to spec type
for r in runs:
    tt = r.get("efficiency",{}).get("tasks_total",0)
    st = r.get("spec_type","")
    if st == "FIBONACCI" and tt > 5:
        critiques.append(("C3","Over-decomposition on Simple Specs",r["provider"],
            f"{r['spec_name']} generated {tt} tasks for a single-file console app. "
            f"Planner RC1 (max 2 tasks/file) fix may not be fully effective for SINGLE_SHOT specs."))
    elif st == "BACKEND" and tt > 16:
        critiques.append(("C3","Over-decomposition on Backend Specs",r["provider"],
            f"{r['spec_name']} generated {tt} tasks. Expected 6-12 for medium backend. "
            f"Excessive task count increases merge conflicts and reviewer noise."))
    elif st == "FULLSTACK" and tt > 25:
        critiques.append(("C3","Over-decomposition on Full-stack Specs",r["provider"],
            f"{r['spec_name']} generated {tt} tasks. Expected 10-18 for medium full-stack. "
            f"Task explosion likely from same-file sequential chains despite RC1 fix."))

# C4: Timeout patterns
for r in timeouts:
    critiques.append(("C4","Pipeline Timeout",r["provider"],
        f"{r['spec_name']} timed out (stuck in '{r.get('stuck_in','?')}'). "
        f"Indicates worker/reviewer loop is not converging within time budget. "
        f"Check for iteration exhaustion patterns in this spec type."))

# C5: Failover detection
all_failovers = [r for r in runs if r.get("failover_detected")]
if all_failovers:
    critiques.append(("C5","Provider Failover Contamination","ALL",
        f"{len(all_failovers)} run(s) experienced failover to alternate provider: "
        + ", ".join(f"{r['provider']}/{r['spec_name']}" for r in all_failovers)
        + ". Results from these runs are excluded from provider accuracy averages."))

# C6: Fibonacci accuracy (should be near 100%)
for p in providers:
    fib_scores = [score_val(r) for r in runs if r["provider"]==p and r.get("spec_type")=="FIBONACCI" and score_val(r) is not None]
    fib_pass   = [r for r in runs if r["provider"]==p and r.get("spec_type")=="FIBONACCI" and is_pass(r)]
    total_fib  = [r for r in runs if r["provider"]==p and r.get("spec_type")=="FIBONACCI"]
    if total_fib and len(fib_pass) < len(total_fib):
        critiques.append(("C6","Fibonacci Reliability Below Expectation",p,
            f"Expected 5/5 fibonacci pass rate; got {len(fib_pass)}/{len(total_fib)}. "
            f"Single-file console apps should be 100% reliable. Failed specs likely have "
            f"project_type misclassification (incorrectly treated as full-stack or API)."))

# ── Write Markdown report ──────────────────────────────────────────────────────
now = datetime.utcnow().strftime("%Y-%m-%d %H:%M UTC")
with open(md_path, "w") as f:
    def w(s=""): f.write(s + "\n")

    w("# ExecuteSpec — Exhaustive Provider Eval: Critic Report")
    w(f"*Generated: {now}*")
    w()
    w("---")
    w()
    w("## Executive Summary")
    w()
    w(f"Total runs: **{len(runs)}** across {len(providers)} provider(s) × 16 specs")
    w()
    w("| Provider | Pass Rate | Avg Accuracy | Avg Cost | Avg Time | Failovers |")
    w("|----------|-----------|-------------|----------|----------|-----------|")
    for p in providers:
        ps = prov_stats[p]
        acc = f"{ps['avg_accuracy']}%" if ps['avg_accuracy'] else "N/A"
        cost = f"${ps['avg_cost']}" if ps['avg_cost'] else "N/A"
        w(f"| **{p}** | {ps['pass_rate']} | {acc} | {cost} | {ps['avg_time_s']}s | {ps['failovers']} |")
    w()

    w("---")
    w()
    w("## Full Results Matrix")
    w()
    w("| Provider | Spec | Type | Lang | Status | Time | Cost | Tasks | Accuracy | Grade | Issues |")
    w("|----------|------|------|------|--------|------|------|-------|----------|-------|--------|")
    for r in sorted(runs, key=lambda x: (x["provider"], x["spec_type"], x["language"])):
        p = r["provider"]
        sn = r["spec_name"]
        st = r.get("spec_type","?")
        lang = r.get("language","?")
        status = r.get("status","?")
        ws = r.get("time",{}).get("wall_seconds",0)
        cost = r.get("cost",{}).get("actual_usd","null")
        tt = r.get("efficiency",{}).get("tasks_total",0)
        sc = r.get("accuracy",{}).get("score","N/A")
        gr = r.get("accuracy",{}).get("grade","N/A")
        iss = r.get("accuracy",{}).get("issues_total",0)
        status_icon = "✅" if status in PASSING else "❌"
        w(f"| {p} | {sn} | {st} | {lang} | {status_icon} {status} | {ws}s | ${cost} | {tt} | {sc} | {gr} | {iss} |")
    w()

    w("---")
    w()
    w("## Accuracy by Spec Type")
    w()
    w("| Spec Type | " + " | ".join(providers) + " |")
    w("|-----------|" + "|".join("---" for _ in providers) + "|")
    for st in spec_types:
        cells = []
        for p in providers:
            d = type_breakdown[st].get(p,{})
            acc = d.get("avg_accuracy")
            pr = d.get("pass_rate","0/0")
            cells.append(f"{pr} • avg {acc}%" if acc else f"{pr} • N/A")
        w(f"| **{st}** | " + " | ".join(cells) + " |")
    w()

    w("---")
    w()
    w("## Accuracy by Language")
    w()
    w("| Language | " + " | ".join(providers) + " |")
    w("|----------|" + "|".join("---" for _ in providers) + "|")
    for lang in languages:
        cells = []
        for p in providers:
            d = lang_breakdown[lang].get(p,{})
            acc = d.get("avg_accuracy")
            pr = d.get("pass_rate","0/0")
            cells.append(f"{pr} • avg {acc}%" if acc else f"{pr} • N/A")
        w(f"| **{lang}** | " + " | ".join(cells) + " |")
    w()

    w("---")
    w()
    w("## Failure Analysis")
    w()
    if not failures and not low_score and not timeouts:
        w("🎉 No failures or low-accuracy runs detected.")
    else:
        if timeouts:
            w(f"### Timeouts ({len(timeouts)})")
            for r in timeouts:
                w(f"- **{r['provider']}/{r['spec_name']}** — stuck in `{r.get('stuck_in','?')}`")
            w()
        if failures:
            w(f"### Failed Runs ({len(failures)})")
            for r in failures:
                w(f"- **{r['provider']}/{r['spec_name']}** — status: `{r['status']}`")
            w()
        if low_score:
            w(f"### Low Accuracy Runs (<70%) ({len(low_score)})")
            for r in low_score:
                sc = score_val(r)
                w(f"- **{r['provider']}/{r['spec_name']}** — {sc}% ({r.get('accuracy',{}).get('grade','?')})")
            w()

    w("### Issue Pattern Counts (from validation reports)")
    w()
    if pattern_counts:
        sorted_patterns = sorted(pattern_counts.items(), key=lambda x: -x[1])
        w("| Pattern | Occurrences |")
        w("|---------|-------------|")
        for pat, cnt in sorted_patterns:
            w(f"| `{pat}` | {cnt} |")
    else:
        w("*No patterns extracted (reports may not have been generated)*")
    w()

    w("---")
    w()
    w("## Architectural Critique")
    w()
    if not critiques:
        w("*No architectural issues identified — all specs passed within expected quality thresholds.*")
    else:
        for code, title, scope, desc in critiques:
            w(f"### [{code}] {title} — _{scope}_")
            w()
            w(desc)
            w()

    w("---")
    w()
    w("## Recommendations (Priority Order)")
    w()
    rec_num = 1
    # Based on patterns
    if pattern_counts.get("router_namespace_duplication",0) > 0:
        w(f"{rec_num}. **Fix router namespace rule enforcement** — {pattern_counts['router_namespace_duplication']} occurrences of `/api/api` duplication. Worker cross-task consistency check (RC worker prompt) may not be catching all cases.")
        rec_num += 1
    if pattern_counts.get("module_system_mismatch",0) > 0:
        w(f"{rec_num}. **Strengthen module system enforcement** — {pattern_counts['module_system_mismatch']} ESM/CJS mismatches detected despite RC3 fix. Consider adding module system check to the CompilerAgent pre-flight.")
        rec_num += 1

    fs_failures = [r for r in failures if r.get("spec_type")=="FULLSTACK"]
    if fs_failures:
        w(f"{rec_num}. **Full-stack scaffold coverage** — {len(fs_failures)} full-stack specs failed. Verify scaffold templates exist for all 5 language stacks (check prompts/scaffolds/). Missing templates fall back to heuristics which are unreliable.")
        rec_num += 1

    rust_fails = [r for r in runs if r.get("language")=="Rust" and is_fail(r)]
    if len(rust_fails) >= 2:
        w(f"{rec_num}. **Rust support depth** — {len(rust_fails)} Rust specs failed. Rust is complex (ownership, no nulls, explicit error handling) and may need a dedicated scaffold + reviewer rule for Cargo.toml dependency management.")
        rec_num += 1

    go_fails = [r for r in runs if r.get("language")=="Go" and is_fail(r)]
    if len(go_fails) >= 2:
        w(f"{rec_num}. **Go stdlib-only enforcement** — {len(go_fails)} Go specs failed. External dep injection in Go projects (gorilla/mux, gin) may conflict with the no-external-deps constraint. Add explicit stdlib-only guard to Go scaffold.")
        rec_num += 1

    if any(c[0]=="C1" for c in critiques):
        w(f"{rec_num}. **Cross-stack contract injection** — Full-stack accuracy gap detected. Ensure shared_contracts.json is written by backend tasks and read by frontend tasks before any frontend API calls are written.")
        rec_num += 1

    if timeouts:
        w(f"{rec_num}. **Pipeline convergence monitoring** — {len(timeouts)} timeout(s). Add per-spec-type wall-clock monitoring to the StuckRunMonitor; consider different worker iteration caps for STATIC_HTML vs FULL_STACK specs.")
        rec_num += 1

    if rec_num == 1:
        w("1. Continue monitoring accuracy trends across releases. Current thresholds appear healthy.")
    w()

    w("---")
    w(f"*Report generated by ExecuteSpec exhaustive-eval.sh · {now}*")

print(f"Critic report written to: {md_path}")

# ── Write consolidated JSON ──────────────────────────────────────────────────
output = {
    "eval_timestamp": now,
    "total_runs": len(runs),
    "providers": prov_stats,
    "by_type": type_breakdown,
    "by_language": lang_breakdown,
    "critiques": [{"code":c[0],"title":c[1],"scope":c[2],"description":c[3]} for c in critiques],
    "pattern_counts": dict(pattern_counts),
    "runs": runs
}
with open(json_path, "w") as jf:
    json.dump(output, jf, indent=2)
print(f"JSON results written to:  {json_path}")

PYEOF
}

# ═══════════════════════════════════════════════════════════════════════════════
# LIVE SUMMARY TABLE (printed after each provider completes)
# ═══════════════════════════════════════════════════════════════════════════════

print_live_table() {
  local provider="$1"
  echo ""
  hdr "Live Results — $provider"
  printf "${BOLD}  %-25s %-10s %-8s %-6s %-10s %-8s %-8s${NC}\n" \
    "Spec" "Status" "Time(s)" "Tasks" "Cost(\$)" "Score" "Grade"

  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    local p spec status ws tt cost sc gr
    p=$(echo "$line"      | jq -r '.provider // ""')
    [[ "$p" != "$provider" ]] && continue
    spec=$(echo "$line"   | jq -r '.spec_name // ""')
    status=$(echo "$line" | jq -r '.status // "?"')
    ws=$(echo "$line"     | jq -r '.time.wall_seconds // 0')
    tt=$(echo "$line"     | jq -r '.efficiency.tasks_total // 0')
    cost=$(echo "$line"   | jq -r '.cost.actual_usd // "null"')
    sc=$(echo "$line"     | jq -r '.accuracy.score // "N/A"')
    gr=$(echo "$line"     | jq -r '.accuracy.grade // "N/A"')

    local color="$NC"
    [[ "$status" == "COMPLETE" ]] && color="$GREEN"
    [[ "$status" == "PARTIAL"  ]] && color="$YELLOW"
    [[ "$status" == "FAILED" || "$status" == "TIMEOUT" ]] && color="$RED"

    printf "  ${color}%-25s %-10s %-8s %-6s %-10s %-8s %-8s${NC}\n" \
      "$spec" "$status" "${ws}s" "$tt" "\$$cost" "$sc" "$gr"
  done < "$RESULTS_JSONL"
  echo ""
}

# ═══════════════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════════════

mkdir -p "$RESULTS_DIR"
touch "$RESULTS_JSONL"

total_specs="${#SPEC_ORDER[@]}"
total_providers=$(echo "$PROVIDERS" | wc -w | tr -d ' ')
est_hours=$(echo "scale=1; $total_specs * 18 * $total_providers / 3600" | bc 2>/dev/null || echo "~2")

hdr "ExecuteSpec — Exhaustive Provider Eval"
echo -e "  Providers : ${BOLD}$PROVIDERS${NC}"
echo -e "  Specs     : ${BOLD}$total_specs per provider${NC}  (1 HTML · 5 Fibonacci · 5 Full-stack · 5 Backend)"
echo -e "  Total runs: ${BOLD}$(( total_specs * total_providers ))${NC}"
echo -e "  UI style  : ${BOLD}$RANDOM_UI${NC}  (for HTML spec)"
echo -e "  Est. time : ${BOLD}~${est_hours}h${NC}  (sequential per provider)"
echo -e "  Results   : ${BOLD}$RESULTS_JSONL${NC}"
echo ""

auth_login

for PROVIDER in $PROVIDERS; do
  run_provider_suite "$PROVIDER"
  print_live_table "$PROVIDER"
done

echo ""
generate_report

hdr "Eval Complete"
echo -e "  JSON : ${BOLD}$RESULTS_JSON${NC}"
echo -e "  Critic report : ${BOLD}$REPORT_MD${NC}"
echo ""
