# Project View — End-to-End Test Plan

Exercises the v2.4.x project shell end-to-end: repo registry, multi-repo Launches,
solution decomposition, per-solution PRs, project memory build-up + compaction,
and project chat. Designed to be runnable manually against a live ENTERPRISE/MAX
account or wrapped by `scripts/project-view-e2e.sh`.

**Tier required:** ENTERPRISE or MAX (project shell + chat are gated on
`tier_config.execution_view_enabled`).

**Complexity ceiling:** every Launch in this plan is sized **SINGLE_SHOT** or
**MEDIUM** so the suite finishes in a workday. No HIGH-complexity work.

---

## Fixtures

### F1. Repos (12 simple microservices, ORG-scoped)

Created via `POST /api/repos { scope: "ORG", githubRepoFullName: ... }` then linked
via `POST /api/projects/{id}/repos/{repoId}`. Owner `executespec-test` (replace
with your test org). Default branch `main`. Each repo starts with a one-line
README so codemap detection has something to chew on.

| # | Repo                          | Stack         | Purpose for the test plan                  |
|---|-------------------------------|---------------|--------------------------------------------|
| 1 | exspec-test/auth-svc          | spring-boot   | Login, JWT issue/verify                    |
| 2 | exspec-test/users-svc         | spring-boot   | User CRUD                                  |
| 3 | exspec-test/billing-svc       | node-backend  | Subscriptions, invoices                    |
| 4 | exspec-test/notifications-svc | node-backend  | Email + in-app fan-out                     |
| 5 | exspec-test/search-svc        | python-flask  | Index + query                              |
| 6 | exspec-test/reports-svc       | python-flask  | Daily aggregates                           |
| 7 | exspec-test/gateway-svc       | go            | API gateway / routing                      |
| 8 | exspec-test/scheduler-svc     | go            | Cron + outbox                              |
| 9 | exspec-test/audit-svc         | spring-boot   | Append-only audit log                      |
|10 | exspec-test/metrics-svc       | node-backend  | Counter aggregator                         |
|11 | exspec-test/web-frontend      | node-frontend | React SPA                                  |
|12 | exspec-test/mobile-shell      | node-frontend | RN bridge / pure JS, no native deps        |

### F2. Project

`POST /api/projects { name: "ProjectViewE2E", aiProvider: "OPENAI" }`, then link
all 12 repos from F1.

---

## Test cases

Each TC names the **launch request** verbatim so the script can submit it
directly. Validation steps assume the dashboard at `/projects/{id}/dashboard`.

### Group A — Single-repo Launches (warm-up, SINGLE_SHOT or LOW)

#### TC-A1. Inline run detail expansion
- **Goal:** Verify `InlineRunDetail` shows full report inside the project view, not navigated away.
- **Launch:** "Add a `/health` endpoint to **auth-svc** returning `{status: 'ok'}`."
- **Expect:** Run completes COMPLETE, score ≥ 80. Click the row in the **Tasks** tab → row expands inline → `ValidationReportPanel` renders → no route change.

#### TC-A2. Provider override at Launch time
- **Goal:** Verify per-Launch provider override.
- **Launch:** "Add `/version` endpoint to **users-svc** returning the git sha." Override provider to `ANTHROPIC` in the Launch form.
- **Expect:** Run uses `claude-haiku-4-5-20251001` (verify in run detail Provider column). Project default (`OPENAI`) unchanged.

#### TC-A3. Confluence + Figma URL passthrough
- **Goal:** Verify external doc URLs reach the planner.
- **Launch:** "Add `/users/{id}/avatar` endpoint to **users-svc**." Confluence URL: `https://example.atlassian.net/wiki/spaces/X/pages/12345`. Figma URL: `https://www.figma.com/design/abc/Avatar`.
- **Expect:** Both URLs persisted on `execution_run` (verify in DB / `GET /api/projects/{id}/launches/{launchId}`). Planner receives them in the requirement context (sample one prompt from `prompt_audit`).

### Group B — Multi-repo Launches (MEDIUM)

#### TC-B1. Two-repo dependency
- **Goal:** Multi-repo Launch decomposes into per-repo solutions.
- **Launch:** "When a user is created in **users-svc**, send a welcome email via **notifications-svc**. Add the call in users-svc and the `/notifications/email` endpoint in notifications-svc."
- **Expect:** Plan has **2 solutions**, dependency edge `users-svc → notifications-svc`. Both COMPLETE. **2 PRs** open (one per repo). Project's **PRs tab** shows both linked.

#### TC-B2. Fan-out — one source, three consumers
- **Launch:** "Emit a `user.signed_in` event from **auth-svc**; consume it in **audit-svc** (write log entry) and **metrics-svc** (increment `signin_count`)."
- **Expect:** 3 solutions. auth-svc runs first; audit-svc + metrics-svc run in parallel layer. 3 PRs.

#### TC-B3. Cross-repo contract handover
- **Launch:** "Add `/search?q=...` endpoint to **search-svc** returning `{hits: string[]}`. Have **gateway-svc** proxy `/api/search` to it."
- **Expect:** `execution_context_handover` row written for the `search-svc → gateway-svc` interface. Gateway implementation matches the contract (response shape).

#### TC-B4. Three-repo chain
- **Launch:** "Add a daily report job to **scheduler-svc** that calls **reports-svc** `/generate-daily`, which reads from **billing-svc** `/invoices/yesterday`. Build all three endpoints."
- **Expect:** 3 solutions, dependency chain `billing → reports → scheduler`. Layered execution.

### Group C — Frontend + backend (MEDIUM)

#### TC-C1. Backend + UI
- **Launch:** "Add a **users page** to **web-frontend** that lists users from **users-svc** `/users`. Build the endpoint if missing."
- **Expect:** 2 solutions. Web-frontend solution opens preview with the page. Backend PR + frontend PR.

#### TC-C2. Mobile shell consumer
- **Launch:** "Have **mobile-shell** consume **users-svc** `/users` and render a JSON list. No native modules."
- **Expect:** Single mobile solution, build passes, no Java/Swift required.

### Group D — Failure + retry behaviour

#### TC-D1. Solution PARTIAL → retry
- **Goal:** Verify multi-run solution tracking.
- **Setup:** Pre-seed `notifications-svc` with a syntax error in `index.js` so the first build fails.
- **Launch:** "Add a `/healthz` endpoint to **notifications-svc**."
- **Expect:** First V2 run terminates `PARTIAL`. `SolutionExecutionService` triggers retry → second run COMPLETE. **SolutionSection** shows 2 attempts; both rows clickable to their `RunDetailPage`.

#### TC-D2. Solution FAILED → manual fix
- **Setup:** Same as D1 but with an unfixable scaffolding error.
- **Expect:** After `maxRetries` runs, solution stays FAILED. Launch enters PAUSED. `FailureDecisionModal` lets the user choose SKIP/RETRY/FIX.

### Group E — Project memory (CRITICAL)

The compaction job runs every 10 completed runs starting at run #20.

#### TC-E1. Ledger entry per completed run
- **After every TC above:** `GET /api/projects/{id}/memory/ledger` returns N rows (N = number of COMPLETE runs to date).
- **Validate:** Each row has `caveman` digest (~3-5 lines), `runNumber` monotonic, `compactedInto` NULL until E3.

#### TC-E2. Memory injection into planner
- **After ≥ 5 ledger entries:** Submit any new Launch and inspect `prompt_audit` for the planner call.
- **Expect:** System prompt contains `## PROJECT MEMORY` block with the latest 10 entries.

#### TC-E3. Compaction at run 20
- **Drive total to ≥ 20 completed runs.** A reasonable mix: 5 from Group A, 6 from Group B, 2 from Group C, 1 from D1 (counts the COMPLETE retry), then **6 filler runs** (e.g. "add `/ping` to <repo>" rotated across the 12 repos until you hit 20).
- **Expect:** `project_memory_snapshot` row created with `version=1`. `compactedInto` set on the first 10 ledger rows. `MemoryStats.compactedRuns = 10`, `MemoryStats.uncompactedRuns = 10`.

#### TC-E4. Compaction at run 30
- **Drive to 30 completed runs** (10 more filler runs).
- **Expect:** Snapshot `version=2` supersedes v1. Total compacted = 20. Snapshot length grows but stays bounded (planner context shouldn't balloon).

#### TC-E5. Memory survives project reload
- Hard-refresh the dashboard, hit the Memory tab.
- **Expect:** `ProjectMemoryPanel` shows the latest snapshot version + token-budget bar + last 10 uncompacted entries — no client-side caching needed to render.

### Group F — Project chat (v2.4.1)

#### TC-F1. Chat without prior runs
- **Setup:** Brand-new project, no Launches.
- **Ask:** "What does this project do?"
- **Expect:** Assistant says memory is empty / no prior context. Does **not** fabricate.

#### TC-F2. Chat reflects memory
- **After TC-E2 (≥ 5 runs):** Ask "Which repos has this project touched so far?"
- **Expect:** Assistant lists the repos that appear in the ledger (auth-svc, users-svc, etc.). Names match the actual ledger.

#### TC-F3. Chat reflects compacted memory
- **After TC-E3 (snapshot v1):** Ask "Summarise what's been built in the first 20 runs."
- **Expect:** Assistant draws from the snapshot, not just the last 10 entries.

#### TC-F4. Chat over architecture doc
- **Setup:** Generate an architecture doc via `POST /api/projects/{id}/architecture/generate`.
- **Ask:** "How do auth-svc and gateway-svc talk to each other?"
- **Expect:** Answer references the arch doc content. If the doc doesn't say, the assistant says so.

#### TC-F5. Chat respects provider override at project level
- **Setup:** Project provider = `GEMINI`.
- **Ask:** "Hello."
- **Expect:** Run uses Gemini PLANNING-tier model. Verify in server logs (`LlmRouterV2 -> gemini ...`).

#### TC-F6. Tier gate
- **Setup:** Log in as a PRO-tier user with access to the project.
- **Try:** `POST /api/projects/{id}/chat`.
- **Expect:** HTTP 403 — "Project chat requires ENTERPRISE or MAX tier".

#### TC-F7. History pagination behaviour
- **After 25+ chat turns:** Refresh chat tab.
- **Expect:** `GET /api/projects/{id}/chat` returns all messages (full history persisted). Sliding window of 20 fed back to LLM (verify via `prompt_audit`).

### Group G — Repo lifecycle

#### TC-G1. Add new repo mid-project
- **Steps:** Add 13th repo `exspec-test/feature-flags-svc` via the "+ Link repo" button. Select it for the next Launch.
- **Launch:** "Add `/flags/{key}` endpoint to **feature-flags-svc**."
- **Expect:** Repo cloned into workspace, solution executes against it.

#### TC-G2. Create new repo via Launch dry-run
- **Steps:** In the Launch form, choose "**+ Create new repo**" → name `exspec-test/onboarding-svc`, tech-hint `node-backend`, visibility `private`.
- **Expect:** Pre-flight dry-run validates GH access. On submit, repo created via `GitHubRepoService.createRepo`, linked to project, then included in the Launch.

#### TC-G3. Unlink (don't delete) a repo
- **Steps:** From Repos tab, click Unlink on `mobile-shell`.
- **Expect:** Link removed; repo still exists at org scope. Re-link works.

### Group H — Aesthetic / UX

#### TC-H1. Dark theme everywhere
- Walk every tab: Overview, Launches, PRs, Tasks, Repos, Architecture, Memory, Chat, Team.
- **Expect:** No white panels, no light-theme widgets. All surfaces use `--gray-100` / `--border-default` / accent tokens.

#### TC-H2. Inline expansion does not break scroll
- Open Tasks tab, expand 3 different runs.
- **Expect:** Page remains scrollable; `InlineRunDetail` capped at 480px with internal scroll.

---

## Pass criteria

- **Functional:** Groups A–D all pass; ≥ 1 PR per multi-repo Launch.
- **Memory:** E1, E3 pass (compaction triggers + snapshot persists). Score: stretch if E4 also passes.
- **Chat:** F1, F2, F6 pass (no fabrication, memory-grounded, tier-gated).
- **Score floor:** average review score across all completed runs ≥ 75.
- **No regressions:** existing Vibe + Pro views still render unchanged.

## Out of scope

- HIGH-complexity Launches.
- Real Confluence/Figma fetch (URL passthrough only).
- JIRA sync round-trip (covered by execution-view tests).
- Stripe/Razorpay billing flows.
