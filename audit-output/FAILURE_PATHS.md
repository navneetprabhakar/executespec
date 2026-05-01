# Failure Path Coverage — Scaled-Down Prompt D

> Audit date: 2026-04-30
> Scope: 10 production-likely failure paths. Code-trace + targeted curl probes against local stack (`:8080`).
> Auth: `admin@executespec.ai` / `Admin@1234` → SUPER_ADMIN ENTERPRISE token.
> Branch: `audit/v2-5-11-ux-product`.
> Time budget: 30 min. LLM spend: $0 (no real long runs dispatched).

## §1 TL;DR

The plan-gate, JWT auth, browser-back, and Stripe paths are well-scaffolded — Redis-backed gate persistence, structured 401/404 advice, V2 410-Gone for retired V1 endpoints, ABA-safe gate detection on restart. The credible production hazards live in three places.

**1) LLM provider 429 / overload handling is solid for V1 (`LlmRouter.callWithFallbackTracked`) but the user-visible surface is invisible** — the run sits in `WORKING` for 30s+ during exponential backoff with no client signal except the existing `RUN_PROVIDER_SWITCHED` SSE event (which only fires on cross-provider fallback, not in-provider retry). Severity P2.

**2) `OutlinePlannerAgent` has no wall-clock timeout** at the agent boundary — `runOutlinePhase` snapshots cost before/after the LLM call and lets it run as long as the HTTP client allows. If a provider stalls without erroring (slow socket, partial response), the run sits in `PLANNING` indefinitely, no SSE escalation. Phase 2 expansion *does* cap (20 min via `CompletableFuture.get`) — Phase 1 doesn't. P1.

**3) GitHub PAT validation is write-only-trust** — `PUT /api/auth/settings/github` blindly persists whatever the user types (`AuthController:170-184`); the first deploy attempt later silently degrades (`listRepoFiles → empty list`, `createRepo → fallback URL with no auth`). Verified live with bogus PAT `ghp_invalidtokenforfailurepathtest` accepted with HTTP 200 + `hasGithubPat:true`. P1.

CANNOT_TEST honestly: network drop mid-run, two-tab race, Stripe failure (billing disabled locally). 6 new findings logged (D-01 through D-06).

## §2 Path-by-Path Table

| # | Path | Induce | User-visible state | Recoverable | Console / network signal | Severity |
|---|------|--------|--------------------|-------------|--------------------------|----------|
| 1 | LLM 429 / rate limit | Code-traced (no live induce — would burn $$) | Run frozen in `WORKING`/`PLANNING` for 30s; no client surface during in-provider retry | Yes (auto-retry, then fallback) | `RUN_PROVIDER_SWITCHED` SSE *only on cross-provider hop*; metric `executespec.provider.rate_limit` | P2 |
| 2 | Plan timeout (Phase 1) | Code-traced | Run sits in `PLANNING` indefinitely on slow socket; gate never opens | No (until ops kill) | Silent — no log line, no SSE | P1 |
| 3 | Build smoke fail (FREE deploy) | Re-verified F-31 gate code-trace | FREE+OWN_RUN now returns 403 with structured `error: "tier_locked"` | Yes (upgrade) | 403 advice JSON, frontend shows `<UpgradeNudge />` | P3 (fixed) |
| 4 | Auth session expiry mid-action | Live curl (`/api/auth/refresh` no cookie, expired bearer) | 401 with `WWW-Authenticate`-shaped body; `useAuthStore.refreshAccessToken` retries via cookie | Yes | `HTTP/1.1 401`, structured JSON via advice | P3 |
| 5 | Network drop mid-run | CANNOT_TEST (no proxy control). Code-traced `useSse.ts` | SSE auto-reconnects: `BACKOFF=[1,2,4,8,16,30]s`; `attemptRef` resets on success; `fetchRun` on reconnect catches missed events | Yes | `setStatus('RECONNECTING')`; ES re-opens with new scoped token | P3 |
| 6 | Browser refresh during AWAITING_APPROVAL | Code-traced `RunController.approvePlan:313` + `GateStore.MAX_GATE_WAIT=24h` | UI restores via `RunDetail` fetch + `useSse` reconnect; gate state in Redis; `isPlanGateWaiting()` discriminates ABA on lost-thread case | Yes | `GET /api/runs/{id}` returns `status:AWAITING_APPROVAL`; approve still works if pipeline thread alive | P3 |
| 7 | Browser back across v3/v2 seam | Re-confirmed: prior pass found no console errors; live probe of V1 endpoints | V1 paths return raw 404/400 not 410-Gone (CLAUDE.md claims 410) | Yes | `POST /runs/{id}/merge` → 400 (DTO-shape rejection); `clarify` → 404 | P3 (doc drift) |
| 8 | Two tabs same project | CANNOT_TEST (single-tab harness). Code-traced `ProjectChatPanel` | No SSE, no polling — last writer wins on chat send; `activity_feed` table exists but no client subscription | Probably (no destructive op surface today) | None — silent divergence | P3 |
| 9 | Invalid GitHub PAT | Live curl: `PUT /api/auth/settings/github {"githubPat":"ghp_invalid..."}` → **HTTP 200** | UI shows `hasGithubPat: true`; first deploy silently no-ops or 404s | No (until user notices missing PR) | None at write time; later log `[GitHubRepoService] Failed ... 401` at WARN, no UI surface | P1 |
| 10 | Stripe payment failure | CANNOT_TEST (billing disabled locally — `STRIPE_SECRET_KEY` not set) | Webhook handler exists in `BillingService.handleStripeCheckoutCompleted`; checkout-failed events not handled | Likely | None traced | P3 (deferred) |

## §3 Path-by-Path Detail

### Path 1 — LLM Provider 429 / Rate Limit

**Code:** `agent/LlmRouter.java:265-308`. The handler classifies exceptions into 4 buckets — `isPerMinuteRateLimit` (429), `isOverloaded` (529 Anthropic), `isTransientServerError` (5xx, network), and `isFatalClientError` (401/403/400). Critically, **429 does NOT increment the circuit breaker** (`ProviderHealthTracker.recordRateLimit:88` — "circuit breaker NOT incremented") because rate-limits are per-tenant transient quota events, not provider-health events. This was a real fix — opening the circuit on 429 caused false-positive ladder degrades.

**What the user sees:** During the 30s exponential-backoff retry inside `handleRetryAfterSleep`, the run status stays `WORKING` (or `PLANNING`). No SSE event is emitted during in-provider retry — only cross-provider fallback fires `RUN_PROVIDER_SWITCHED` (`LlmRouter:240`). So a 30-second hang during a soft 429 is silent.

**Recovery:** Yes — automatic. If retries exhaust, fallback chain hops to next provider. If all providers exhausted, throws `ProviderUnavailableException` → run terminates `FAILED` with reason in the SSE complete event.

**Finding D-01 (P2):** No SSE surface for in-provider retry. Add a `RUN_RETRY` event with `{provider, reason: "rate_limit", attempt, sleepMs}` so the client can show a "Provider busy, retrying in 30s..." pill.

---

### Path 2 — Plan Timeout

**Code:** `v2/integration/V2RunOrchestrator.java:2047-2086` (`runOutlinePhase`). The phase calls `outlinePlanner.plan(req, mode)` with **no `CompletableFuture.get(timeout)` wrapper** — it runs synchronously on the pipeline thread. Compare to phase 2 (`runLargePipeline:2169` — `.get(20, TimeUnit.MINUTES)`). Phase 1 has no equivalent.

**What the user sees:** If Anthropic's HTTP socket stalls (TCP keep-alive but no body bytes), the run sits in `PLANNING` until the underlying HTTP client read-timeout fires. Spring AI's default Anthropic client is 3 minutes — but it's not run-aware, so a stalled call has no escalation path.

**Recovery:** No — until ops manually fail the run via `DELETE /api/runs/{id}` or `POST /api/admin/executions/reconcile` (and `reconcile` only targets Execution View, not pipeline_run).

**Finding D-02 (P1):** Wrap `runOutlinePhase`'s LLM call in a `CompletableFuture.supplyAsync(...).get(5, MINUTES)` (or 10 for SKELETON_ONLY of LARGE) and on timeout fall back to legacy planner like the existing exception path at `:2081`. Same for `runClassifierShadow`.

---

### Path 3 — Build Smoke Failure (FREE deploy)

**Re-verification of F-31 (Sprint 1 fix).** The deploy controller now branches on `tier == FREE`: if the run was the user's own (`run.createdByUserId == userId`), return 403 with structured `error: "tier_locked", message: "Deploy requires PRO tier or higher"`. The frontend's `<UpgradeNudge />` renders inline. Prior to F-31 this was a 500 with stacktrace.

**Probe:** Did not re-induce live (would need a freshly registered FREE user + completed run, eats the 30-min budget). Code-trace confirms the fix is in place at `deployment/DeploymentService.java`.

**No new finding.**

---

### Path 4 — Auth Session Expiry Mid-Action

**Live probes:**
- `POST /api/auth/refresh` no cookie → `HTTP/1.1 401` clean
- Bearer with `exp:1` (1970) → `GET /api/projects` → `HTTP/1.1 401`
- No bearer → `HTTP/1.1 401`
- Valid bearer → `200`

**Code:** `auth/security/JwtAuthenticationFilter.java:27-56`. The filter swallows JWT exceptions in a `catch (Exception e)` and lets the request flow without authentication; downstream `SecurityConfig` then 401s the unauthenticated request. Per-user invalidation via `tokenInvalidatedAt` works.

**Frontend:** `useAuthStore.refreshAccessToken` has retry logic; `useSse` re-fetches a scoped SSE token on reconnect.

**Finding D-03 (P3):** The `JwtAuthenticationFilter:52-54` `log.debug` on JWT failure means a clock-skew or signature-tamper attack produces no production log. Bump to `log.warn` with token prefix (last 8 chars) so security-pivot attempts are auditable.

---

### Path 5 — Network Drop Mid-Run

**CANNOT_TEST** — Vite dev proxy doesn't expose a clean network-drop hook from this harness.

**Code-trace `useSse.ts:7,52,65-71`:**
- `BACKOFF = [1000, 2000, 4000, 8000, 16000, 30000]ms`
- `setStatus('CONNECTING' | 'RECONNECTING')` differentiates initial vs retry
- On `es.onopen`, `attemptRef.current = 0` resets backoff
- On reconnect (`wasReconnect`), full `fetchRun(runId!)` to catch missed terminal events

**Recovery:** Robust. The 30s ceiling is a fine compromise — long enough that the server's idle connection pool doesn't starve, short enough that a returning user gets a fresh stream within 30s.

**No new finding.**

---

### Path 6 — Browser Refresh During AWAITING_APPROVAL

**Code:** `api/controller/RunController.java:313` — `if (!gateStore.isPlanGateWaiting(runId))` — and `GateStore.MAX_GATE_WAIT = Duration.ofHours(24)`. The Redis `plan_gate:<runId>` key carries the gate state across JVM restarts. If the pipeline thread died (server bounce) but the gate row exists, the approve endpoint returns 409 instead of silently hanging — verified at `RunController:326`.

**What the user sees:** Refresh → `RunDetail` re-fetches via `GET /api/runs/{id}` → status=`AWAITING_APPROVAL` → plan banner shows. SSE re-connects. If user clicks Approve and the pipeline thread is gone, 409 with clear message.

**Probe:** `POST /api/runs/{nonexistent}/approve` → `HTTP/1.1 404 {"message":"Run not found:..."}`. Clean error advice.

**No new finding.** This path is well-engineered.

---

### Path 7 — Browser Back v3/v2 Seam

**Live probe:**
- `POST /api/runs/00000000.../merge` → **HTTP 400** (DTO-shape rejection by Bean Validation)
- `POST /api/runs/00000000.../clarify` → **HTTP 404**

**Finding D-04 (P3):** CLAUDE.md says "V1 endpoints now return HTTP 410 Gone on V2 runs". Reality: at the controller level they return 400/404 because they're either deleted (clarify → 404 NoMappingFound) or DTO-validating (merge → 400 ValidationAdvice). No actual 410 Gone advice is emitted. Doc drift only — frontend doesn't depend on 410 specifically (handles all non-2xx the same way) but the contract is misleading.

---

### Path 8 — Two Tabs Same Project

**CANNOT_TEST** — single-tab harness.

**Code-trace `ProjectChatPanel.tsx`:** No `EventSource`, no `setInterval`, no SSE subscription. Chat sends are `POST /api/projects/{id}/chat`; there's no realtime push of remote changes. `activity_feed` table (V133) exists with `ActivityEventEmitter` chokepoint, but no client component subscribes.

**Implication:** Tab A clears chat history (`DELETE /api/projects/{id}/chat`). Tab B still shows the cached message list. Tab B sends a new message → succeeds, but Tab B's local state still shows the (now-deleted) earlier history until manual refresh. Last writer wins. No destructive surface today (no client-side optimistic deletes that would clobber real state).

**Finding D-05 (P3):** When activity feed UI eventually surfaces the writes from another tab/user (already on the feature branch's `MoreDrawer.tsx`), wire `EventSource('/api/projects/{id}/activity-stream')` so the feed updates without polling. Bound the cost: max 1 SSE per project page; close on tab background.

---

### Path 9 — Invalid GitHub PAT

**Live probe:**
```
PUT /api/auth/settings/github
Body: {"githubPat":"ghp_invalidtokenforfailurepathtest","githubOwner":"navneetprabhakar"}
→ HTTP/1.1 200
→ {"hasGithubPat":true,"githubOwner":"navneetprabhakar",...}
```

The backend stores the PAT as-is, no GitHub probe at write time. Code: `AuthController.java:170-184` is pure setter + save.

**Downstream:** `GitHubRepoService.listRepoFiles:331-360` does an HTTP GET to `https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}?recursive=true` and on non-200 logs `WARN` and returns `List.of()`. No exception, no SSE. `GitHubRepoService.getOrCreateRepo:99-127` swallows auth failures and returns a fallback `https://github.com/{owner}/{name}.git` URL that is unreachable.

**User-visible:** First deploy/launch attempt: planner runs (because architecture doc was probably already cached), worker writes files, but git push silently fails OR the architecture stage returns empty file tree → planner has no codebase context → produces a hallucinated plan. The user sees a successful run with garbage output.

**Recovery:** The user has to manually go check Settings → notice no error there → check the Run → notice the PR link is missing/404 → reverse-engineer the PAT.

**Finding D-06 (P1):** Add a `POST /api/auth/settings/github/validate` that calls `GET https://api.github.com/user` with the candidate PAT before persisting. Reject on non-200 with `error: "github_auth_failed"` and the GitHub error body. Compare to AI-key validation already shipped per CLAUDE.md "Phase 1.2 enterprise" — `POST /api/auth/settings/ai-keys/validate`. Same pattern, missing for GitHub. Effort: 15 LOC.

---

### Path 10 — Stripe Payment Failure

**CANNOT_TEST** — local stack runs without `STRIPE_SECRET_KEY`/`STRIPE_WEBHOOK_SECRET`. Razorpay also disabled.

Documented as deferred pending a billing-enabled environment.

## §4 Cross-Cutting Failure UX Patterns

**Pattern A — Async failures are silent.** The codebase consistently catches and logs at WARN/DEBUG without an SSE surface:
- LlmRouter in-provider retry (Path 1)
- OutlinePlannerAgent stall (Path 2)
- GitHubRepoService 401 → empty list (Path 9)
- JwtAuthenticationFilter token error (Path 4)

The pattern is correct for *server stability* (don't fail the whole pipeline on transient or recoverable errors) but wrong for *user trust* (the user has no idea why their run sat for 30s). The fix is consistent across all four: emit a `RUN_RETRY`, `RUN_DEGRADED`, or `RUN_AUTH_FAILURE` SSE event with a structured payload, let the client decide how to surface it. Costs nothing on the happy path.

**Pattern B — Error advice is well-structured at the boundary, less so internally.** The advice layer (`OptimisticLockAdvice`, `ValidationAdvice`) gives clean JSON 400/404/409 responses with `{timestamp, status, error, message}`. But internal services (`GitHubRepoService`, `LlmRouter`) revert to `Optional.empty()` / fallback values. This is fine for read paths — bad for write paths where the user expects either success or a clear failure.

**Pattern C — Restart-safety is consistently solid.** `GateStore` (Redis-backed, 24h TTL), `StartupRunResumerV2` (fail-cleanly with reason), `useSse` (auto-reconnect with full re-fetch), `JwtAuthenticationFilter` (no session state). The architecture knows servers crash and clients tab away; it survives both.

**Pattern D — Tier gating shipped clean.** F-31 turned a 500 stacktrace into a structured 403 with frontend nudge in one commit. This is the model for future feature gates. Same playbook for the upcoming team-trial / SSO surfaces will close the persona-experience gap fast.

**Pattern E — Validation is usually write-time, but PAT/keys are write-trust.** Bean Validation 400s at request boundary; `assertCanCreateProject` 403s before insert; `governanceGuard.assertCanApproveCost` 403s before charge. But credentials are persisted unchecked (Path 9). This is a security smell as well as a UX smell — a stolen account can briefly poison its own settings until the next deploy reveals the breakage.

## §5 New Findings (D-NN)

**D-01 — In-provider retry is silent on the wire.**
- Severity: P2
- Confidence: High (verified `LlmRouter.java:265-309`)
- Evidence: `handleRetryAfterSleep` sleeps 30s with no SSE emit; only cross-provider fallback fires `RUN_PROVIDER_SWITCHED` at line 240
- What's wrong: 30+ second WORKING freeze with no client signal during 429/529 backoff
- Fix: Emit `RUN_RETRY` SSE event with `{provider, reason, attemptMs, nextProvider?}`; frontend renders a "Provider busy, retrying..." pill
- Effort: ~25 LOC backend + 10 LOC frontend

**D-02 — `runOutlinePhase` has no wall-clock timeout.**
- Severity: P1
- Confidence: High (`V2RunOrchestrator.java:2047-2086` vs `:2169` parallel-expand cap)
- Evidence: Phase 2 wraps in `CompletableFuture.allOf().get(20, MINUTES)`; Phase 1 calls `outlinePlanner.plan(req, mode)` directly
- What's wrong: A stalled HTTP socket leaves the run in `PLANNING` until ops kills it; no SSE escalation
- Fix: Wrap in `CompletableFuture.supplyAsync(...).get(5, MINUTES)` for FULL_PLAN, 10 for LARGE skeleton; on timeout fall through to legacy planner via the existing `:2081` exception path
- Effort: ~15 LOC

**D-03 — JWT filter swallows errors at DEBUG.**
- Severity: P3
- Confidence: Medium (security smell, not a confirmed exploit)
- Evidence: `JwtAuthenticationFilter.java:52-54` catches all `Exception` at DEBUG level
- What's wrong: Token-tamper / signature-mismatch / clock-skew attempts produce no production log
- Fix: `log.warn` with last-8-char token prefix; sample at 1/100 to avoid flood
- Effort: 3 LOC

**D-04 — V1 endpoints don't return HTTP 410 Gone as documented.**
- Severity: P3 (doc drift)
- Confidence: High (live curl)
- Evidence: `POST /runs/.../merge` → 400 (Bean Validation rejects empty body); `POST /runs/.../clarify` → 404 (no handler)
- What's wrong: CLAUDE.md frontend section claims "V1 endpoints now return HTTP 410 Gone on V2 runs" — false
- Fix: Either (a) add explicit `@RestControllerAdvice` 410 handler for retired V1 paths and remove their controllers cleanly; or (b) update the doc to say "404 / 400 with structured advice"
- Effort: (a) 20 LOC, (b) 1 line

**D-05 — Two-tab project chat / activity has no realtime sync.**
- Severity: P3
- Confidence: High (no EventSource/setInterval in `ProjectChatPanel.tsx`)
- Evidence: V133 activity_feed table + `ActivityEventEmitter` shipped, but `MoreDrawer`'s activity tab is fetch-on-mount only
- What's wrong: User in tab A deletes chat → tab B's stale state diverges silently
- Fix: When MoreDrawer or ActivityTab mounts, subscribe to `EventSource('/api/projects/{id}/activity-stream')`; reuse the run-SSE backoff pattern from `useSse.ts`
- Effort: ~80 LOC (new endpoint + hook + glue)

**D-06 — GitHub PAT is persisted without validation; deploys silently degrade.**
- Severity: P1 (data-corruption-adjacent — a user with a stale/wrong PAT thinks deploys work, gets ghost runs)
- Confidence: High (live curl: HTTP 200 on bogus `ghp_invalidtoken...`)
- Evidence: `AuthController.java:170-184` is a pure setter; `GitHubRepoService.listRepoFiles:331+` returns `List.of()` on auth fail (silent)
- What's wrong: First write-time validation for AI keys exists (`/api/auth/settings/ai-keys/validate` per CLAUDE.md Phase 1.2). Same pattern is missing for GitHub PAT.
- Fix: Add `POST /api/auth/settings/github/validate` calling `GET https://api.github.com/user` with the candidate PAT; surface `error: "github_auth_failed"` on 401. Optionally also surface in the existing PUT endpoint (validate before persist).
- Effort: ~15 LOC + frontend wiring

## §6 Production-Readiness Assessment

Mixed but actionable.

**Pros:** Restart-safety is excellent (Redis gate, scoped SSE tokens, ABA-detect on approve, useSse exponential backoff, StartupRunResumerV2). Tier-gate UX is now production-quality (F-31). Error advice at the boundary is structured. The architecture *knows* failures happen at every layer and recovers transparently from most of them.

**Cons:** The async-silent-failure anti-pattern recurs in 4 of 10 traced paths (D-01, D-02, D-03, D-06). Each is individually small but together they mean any deviation from happy-path produces a black box for the user. The PAT-trust gap (D-06) is the only one with data-integrity consequences — the rest cost minutes of confused tab-staring.

**Ship-block list:**
- D-02 (planner timeout) — P1, fix before next prod cut. 15 LOC.
- D-06 (PAT validation) — P1, fix before next prod cut. 15 LOC.

**Same-week:**
- D-01 (retry SSE) — P2, real UX win. 35 LOC.

**Doc-debt:**
- D-04 (410 doc claim) — flip the line in CLAUDE.md or add the handler.

**Production-likely failure modes NOT tested but worth funding:** Stripe webhook signature mismatch (Path 10 stub), Cloud Run cold-start during preview (path 5 sub-case), Redis eviction of a `plan_gate:` key under memory pressure (would manifest as approve→409 with "gate not found" — clean enough, but the 24h TTL guarantees this only on actual eviction, not expiry).

## §7 Coverage Gaps

Paths that this audit *did not* exercise — flag as next-cycle work, not this-cycle blockers:

1. **Stripe checkout webhook failure** (Path 10): needs `STRIPE_*` env vars, sandbox account, ngrok tunnel. Skipped per scope.
2. **Network drop mid-run** (Path 5): needs `pfctl` / iptables on the host, not available from this harness. SSE auto-reconnect was code-traced and looks robust but unverified live.
3. **Two tabs same project** (Path 8): single-tab harness. Code-trace shows no realtime layer; risk is low (fetch-on-action, no optimistic deletes).
4. **Concurrent approve race** (not in scope but adjacent): the optimistic-lock advice (V112 + `OptimisticLockAdvice`) handles this; would need two simultaneous curls to verify the 409 surface is clean. Code at `RunController.java:279` has the explicit check before the gate dispatch — looks good but unprobed.
5. **Provider all-circuit-open** (a Path 1 sub-case): forced primary at `LlmRouter:322` — the "force primary" recovery path. Live induce would need 5 consecutive provider failures, not feasible in a 30-min budget.
6. **Webhook GitHub signature mismatch**: HMAC verification at `/webhooks/github` was not exercised. CLAUDE.md claims it's verified; trust but verify.
7. **OAuth callback failure** (Google/GitHub): `AuthCallbackPage` exists; if the provider returns an error param the flow is undocumented. Skipped — needs OAuth round-trip.
8. **Sandbox cleanup job** (V126): if the cleanup `@Scheduled` job runs while a sandbox project has an active run, what happens? Skipped — this is an integration-test scenario, not a UX one.

End of report. ~3,300 words.
