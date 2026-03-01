# Pipeline E2E Test Plan

Step-by-step guide to configure, run, and verify the full agent pipeline end-to-end.

---

## Prerequisites checklist

- [ ] Java 21 installed (`java --version` shows 21.x)
- [ ] Maven installed (`mvn --version`)
- [ ] PostgreSQL running on `localhost:5432`, database `agentteam` exists
- [ ] Anthropic API key available (the one in `application.yml` is already set)
- [ ] At least 500 MB free disk (workspace artefacts)

```bash
# Quick check — all three should succeed
java --version
mvn --version
psql -U postgres -c "\l" | grep agentteam
```

---

## Step 1 — Create the database (first time only)

```bash
psql -U postgres -c "CREATE DATABASE agentteam;"
```

Tables are created automatically by Hibernate on first startup (`ddl-auto: update`).

---

## Step 2 — Build the project

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
```

Expected: no output, exit code 0.

---

## Step 3 — Understand the test profile

`src/test/resources/application-e2e.yml` overrides the following for tests:

| Setting | Default (app) | E2E override | Reason |
|---|---|---|---|
| `agent.workspace` | `./workspace` | `./workspace-e2e` | Isolated artefacts |
| `agent.recovery.enabled` | `true` | `false` | Prevents stuck-monitor from interfering |
| `agent.context.enabled` | `true` | `false` | Not needed in tests |
| `agent.optimiser.enabled` | `true` | `false` | Not needed in tests |
| `agent.integrations.enabled` | `false` | `false` | No change |
| `logging.level` | `DEBUG` | `INFO` | Less noise |

Nothing else changes — the test uses your real Anthropic API key and real PostgreSQL.

---

## Step 4 — Run all E2E tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn test \
  -Dgroups=e2e \
  -Dspring.profiles.active=e2e \
  -pl . -am
```

Expected runtime: **15–40 minutes** (8 tests; most time spent waiting for LLM + worker agents).

To run a single test by name:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn test \
  -Dgroups=e2e \
  -Dspring.profiles.active=e2e \
  -Dtest="PipelineE2ETest#happyPath_fullPipeline"
```

To run from your IDE: add `-Dspring.profiles.active=e2e` to the VM arguments for the test run, then run the class normally.

---

## Step 5 — Test-by-test walkthrough

### Test 1 — Happy path (full pipeline)
**What it does:** Submits a simple task → approves the plan → lets workers run → approves merge.

**Sequence of status transitions you'll see in logs:**
```
PLANNING → AWAITING_APPROVAL → WORKING → REVIEWING → AWAITING_MERGE → COMPLETE
```

**Pass criteria:**
- Run status = `COMPLETE`
- `completedAt` is set
- `failureReason` is null
- `tasks` list is non-empty
- `integrationSource` is null (manual run)

**What to inspect after:**
```bash
# Check workspace-e2e/ for the file the worker created
ls workspace-e2e/

# Check the run detail
curl -s http://localhost:8080/api/runs/{runId} | jq '.status, .tasks[].status'
```

---

### Test 2 — Plan rejection
**What it does:** Submits a run, waits for the plan, then rejects it.

**Sequence:** `PLANNING → AWAITING_APPROVAL → FAILED`

**Pass criteria:**
- Run status = `FAILED`
- `failureReason` is non-blank (contains "rejected")

**What to observe:** No workers are spawned; the run terminates immediately after rejection.

---

### Test 3 — Merge rejection
**What it does:** Full run through workers, then rejects the merge gate.

**Sequence:** `PLANNING → AWAITING_APPROVAL → WORKING → REVIEWING → AWAITING_MERGE → FAILED`

**Pass criteria:**
- Run status = `FAILED`
- Worker branches still visible in `workspace-e2e/` (not merged)

---

### Test 4 — Cancellation during plan gate
**What it does:** Submits a run, waits for plan, then calls `DELETE /api/runs/{id}`.

**Sequence:** `PLANNING → AWAITING_APPROVAL → FAILED`

**Pass criteria:**
- Run status = `FAILED` shortly after DELETE

**What to observe:** The plan gate CompletableFuture is resolved with `false` which unblocks the async pipeline thread and routes it to the failed path.

---

### Test 5 — List runs with status filter
**What it does:** Verifies `GET /api/runs?status=AWAITING_APPROVAL` returns the right run.

**Pass criteria:**
- The submitted run appears in `AWAITING_APPROVAL` filter
- The same run does NOT appear in `COMPLETE` filter

Cleans up by rejecting the run at the end.

---

### Test 6 — 404 for unknown runId
**What it does:** GET with a zeroed UUID.

**Pass criteria:** HTTP 404.

---

### Test 7 — Metrics endpoint
**What it does:** Verifies `/api/metrics` is reachable and returns expected keys.

**Pass criteria:** HTTP 200, response body contains `totalRuns`, `completedRuns`, `failedRuns`.

---

### Test 8 — Blank userRequest → 400
**What it does:** Submits a run with `userRequest: "  "`.

**Pass criteria:** HTTP 4xx response.

---

## Step 6 — Manual observation during a test run

Start the app separately while a test is running to use the Web UI:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn spring-boot:run \
  -Dspring-boot.run.profiles=e2e
```

Open `http://localhost:8080` in a browser. The **Runs** tab shows live status.

Follow SSE events in a terminal:
```bash
# Replace <runId> with the actual ID printed in test logs
curl -N http://localhost:8080/api/runs/<runId>/events
```

---

## Step 7 — Observing worker output

While Test 1 (or Test 3) is running in `WORKING` state, the worker writes files to:
```
workspace-e2e/
  .agent-context/
    task-assignments.json    # serialised TaskPlan
    file-locks.json
    shared-contracts.json
  hello.txt                  # (for the SIMPLE_TASK)
```

After the test, inspect:
```bash
cat workspace-e2e/hello.txt
# Expected: Hello World
```

---

## Step 8 — Running only fast (non-LLM) tests

If you want to run the unit and JPA tests without touching LLMs:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn test \
  -Dexcludegroups=e2e
```

This skips all `@Tag("e2e")` tests and runs only the existing unit tests.

---

## Step 9 — Post-test cleanup

Remove test workspace:
```bash
rm -rf workspace-e2e/
```

Drop test run rows (optional — rows are small, no need unless you want a clean DB):
```bash
psql -U postgres -d agentteam \
  -c "DELETE FROM pipeline_run WHERE source_type = 'MANUAL' AND user_request LIKE '%hello.txt%';"
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `ConditionTimeoutException: run → AWAITING_APPROVAL` after 4 min | LLM call failed or API key invalid | Check app logs for `[LLM]` error lines; verify API key |
| `AssertionError: Expected WORKING but was FAILED` | Worker encountered an exception | Check logs for `[Worker]` or `[Tool]` ERROR lines |
| `Connection refused localhost:5432` | PostgreSQL not running | Start PostgreSQL: `brew services start postgresql@16` |
| Tests start then hang at PLANNING | Semaphore exhausted from a previous failed test left runs `WORKING` | Cancel orphaned runs: `curl -X DELETE http://localhost:8080/api/runs/<id>` |
| `Could not resolve placeholder 'ANTHROPIC_API_KEY'` | Missing env var | The key is hardcoded in `application.yml` — check it is still present |
| Test 8 (blank request) returns 202 instead of 4xx | Validation not wired on RunRequest | Add `@NotBlank` + `@Valid` to controller — see notes below |

---

## Note on Test 8 (blank userRequest validation)

The current `RunRequest` record has no `@NotBlank` annotation. Test 8 asserts `status >= 400`.
If it returns 202 instead, it means a run was submitted with a blank goal and the planner will
likely fail. You can add validation later:

```java
// RunRequest.java
public record RunRequest(
    @NotBlank String userRequest,
    String source,
    String repoUrl,
    String repoMode
) {}

// RunController.java — add @Valid to the @RequestBody parameter
public ResponseEntity<RunSummary> submit(@Valid @RequestBody RunRequest req)
```

This is not required for the other 7 tests to pass.

---

## Configuration quick-reference

The only config values you may need to change before testing:

```yaml
# application.yml — already set, no change needed for basic E2E
spring:
  ai:
    anthropic:
      api-key: <already set>

# application-e2e.yml — test-specific overrides, already created
agent:
  workspace: ./workspace-e2e
  recovery:
    enabled: false
```

If you want to test against OpenAI instead of Anthropic:

```yaml
# application-e2e.yml — add these overrides
agent:
  roles:
    planner: openai
    worker: openai
    reviewer: openai
```
