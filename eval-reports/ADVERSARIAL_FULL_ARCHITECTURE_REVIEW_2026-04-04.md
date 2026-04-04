# ExecuteSpec — Full Adversarial Architecture Review

> **Date:** 2026-04-04
> **Scope:** Complete codebase — Pipeline, State Management, Auth, Billing, Security, Preview, Deploy, Integrations, Test Execution
> **Method:** Three parallel adversarial review passes covering all major subsystems
> **Focus:** Accuracy first, speed second (per user request)

---

## Executive Summary

**Total issues found: 53** across 10 subsystems.

| Priority | Count | Description |
|----------|-------|-------------|
| **P0** | 7 | Production blockers — data loss, security breaches, unauthenticated code execution |
| **P1** | 21 | High severity — billing exploits, resource leaks, auth bypasses |
| **P2** | 20 | Medium — degraded experience, race conditions, edge cases |
| **P3** | 5 | Low — cosmetic, documentation, minor edge cases |

### Top 5 Most Dangerous Issues

| # | Severity | Issue | Subsystem |
|---|----------|-------|-----------|
| 1 | **P0** | Hardcoded JWT secret in default config — full platform compromise if not overridden in prod | Auth |
| 2 | **P0** | Container network not isolated — preview containers can reach Redis, PostgreSQL, Caddy, cloud metadata | Preview |
| 3 | **P0** | ServiceNow webhook has no signature verification + can skip plan approval — unauthenticated autonomous code execution | Integrations |
| 4 | **P0** | Run marked COMPLETE before background validation — crash permanently loses review/test artifacts | Pipeline |
| 5 | **P0** | `resolveMergeGate` treats DECLINE the same as MERGE — users who reject a merge still get code merged | Pipeline |

---

## Section 1: Pipeline & State Management

### P0 Issues

#### 1.1 Background Validation Cleanup Race
**Component:** `PipelineHarness.java`
**Scenario:** The `executeRunAsync` finally block checks `backgroundValidationRuns.contains(runId)` to decide whether to defer cleanup. The background virtual thread can complete and call `backgroundValidationRuns.remove(runId)` in the gap between the check and cleanup — causing `broadcaster.complete(runId)` and `runKeyStore.remove(runId)` to be called TWICE.
**Impact:** SSE stream errors, double resource cleanup, inconsistent state.
**Fix:** Consolidate all cleanup into a single idempotent `cleanupRun(runId)` method with `compareAndSet` semantics.

#### 1.2 resolveMergeGate Treats DECLINE = MERGE
**Component:** `PipelineHarness.java:601-604`
**Scenario:** Both `MERGE` and `DECLINE` branches call `doReviewAndMerge(run, false, false)`. A user who clicks "Decline merge" still gets their code merged and run completed.
**Impact:** User intent violated — code merged against explicit rejection.
**Fix:** Handle `MergeDecision.DECLINE` by marking run as FAILED or PARTIAL, not merging.

#### 1.3 Run Marked COMPLETE Before Background Validation — Not Recoverable on Crash
**Component:** `PipelineHarness.java:2049`
**Scenario:** Run is marked `COMPLETE` in DB before background validation thread starts. If server crashes during validation: run is COMPLETE in DB, no review/tests/report exist, `backgroundValidationRuns` is in-memory (lost on restart), and `StartupRunResumer` won't pick it up because status is COMPLETE.
**Impact:** Permanently stuck in COMPLETE without any validation artifacts.
**Fix:** Use a separate `VALIDATING` status, or persist a "needs background validation" flag in DB.

### P1 Issues

#### 1.4 `executeApplySuggestion` Bypasses runSlots Semaphore
**Component:** `PipelineHarness.java`
**Scenario:** Apply-suggestion operations bypass the concurrency limit entirely. A user could spam "Apply Suggestion" on multiple runs simultaneously, overwhelming LLM API limits.
**Fix:** Acquire `runSlots.acquire()` before submission.

#### 1.5 `resumeRun` Starts Virtual Thread Without Slot Acquisition
**Component:** `PipelineHarness.java:767`
**Scenario:** Virtual thread for resume doesn't acquire `runSlots` permit, bypassing concurrency limit.
**Fix:** Acquire permit before starting the virtual thread.

#### 1.6 Non-Atomic Token Counter Updates in StateStore
**Component:** `StateStore.java`
**Scenario:** `completeTask()` reads run, increments `totalInputTokens`, saves. Concurrent virtual threads use different DB connections, so both read same initial value — one increment lost.
**Fix:** Use atomic SQL: `UPDATE PipelineRun SET totalInputTokens = totalInputTokens + :inp`.

#### 1.7 Per-Request ChatModel Instances Never Closed
**Component:** `LlmRouter.java`
**Scenario:** `buildAnthropicModel()`/`buildOpenAiModel()`/`buildGeminiModel()` create new ChatModel instances per call with BYOK keys. These hold HTTP connection pools internally — leak under heavy BYOK load.
**Fix:** Cache per-key ChatModel instances with idle timeout.

#### 1.8 `testCompletionFutures` Never Cleaned on Run Failure
**Component:** `PipelineHarness.java`
**Scenario:** CompletableFuture entries accumulate if test gen pipeline crashes or run fails — never completed, never removed.
**Fix:** Remove entries in all failure/cleanup paths, or use TTL-based cache.

#### 1.9 Cost Check Is Pre-Task Only, Not Per-LLM-Call
**Component:** `RunCostBudgetService.java`, `SequentialTaskExecutor.java:283`
**Scenario:** Once a task starts, there is NO mid-task cost enforcement. A complex task with model escalation could consume 10x the expected cost.
**Fix:** Add cost check inside WorkerAgent's agentic loop every N iterations.

#### 1.10 In-Memory Maps Never Cleaned on Abnormal Exit
**Component:** `RunCostBudgetService.java`, `LlmRouter.java`
**Scenario:** ConcurrentHashMap entries for budget ceilings, tier caches, call counters leak if finally block is not reached (OOM, thread interrupt).
**Fix:** Add periodic cleanup task evicting entries older than 3 hours, or use Caffeine cache with TTL.

#### 1.11 Progressive Merge Creates Partially Mutated Main Branch on Failure
**Component:** `SequentialTaskExecutor.java`
**Scenario:** Tasks 1-2 already merged to main when task 3 fails. Run is PARTIAL but main branch has incomplete state. Retry operates on merged state, not original.
**Impact:** Retry behavior differs from fresh execution. Document as expected behavior.

### P2 Issues

#### 1.12 `doReviewAndMerge` Does NOT Update `activeRunThreads`
**Scenario:** `isRunActive()` returns false during review+merge, allowing orphan watchdog to spawn duplicate thread.

#### 1.13 Process Objects in `commitWorktreeIfDirty` Not Destroyed
**Scenario:** Process objects from `ProcessBuilder.start()` never call `destroy()`. Hung git processes persist.

#### 1.14 Skeleton Recovery Can Loop Until Time Deadline
**Scenario:** Stall detection injects recovery prompts that don't produce file writes, wasting entire time budget.

#### 1.15 Gate Polling Blocks Thread Indefinitely
**Scenario:** If gate key is never set (user abandons run), thread blocks until server restart. Slot is released but thread leaks.
**Fix:** Add maximum gate wait timeout (e.g., 24h).

#### 1.16 Exception in Tool Dispatch Silently Ignored in Salvage Path
**Scenario:** WorkerAgent line 384 — salvage failures swallowed with `log.debug`. Model told tools executed when they may have failed.

#### 1.17 Executor Uses Unbounded `newCachedThreadPool`
**Scenario:** Burst of submissions creates hundreds of OS threads waiting on semaphore.
**Fix:** Use `newFixedThreadPool(maxRuns + queueBuffer)`.

#### 1.18 FREE Tier Chain Resolution After Override
**Scenario:** FREE tier override changes `primaryProvider`/`primaryModel` but fallback chain still uses original model mapping.

---

## Section 2: Auth, Billing & Security

### P0 Issues

#### 2.1 Hardcoded JWT Secret in Default Config
**Component:** `application.yml:424`
**Scenario:** Default JWT secret is publicly visible in the repo. If prod deployment fails to override it, any attacker can forge SUPER_ADMIN JWTs with full platform access.
**Fix:** Fail startup if JWT secret matches default when profile includes `prod` or `gcp`.

### P1 Issues

#### 2.2 SSE Endpoint Token in URL — Log Exposure
**Component:** `SecurityConfig.java:126`, `JwtAuthenticationFilter.java`
**Scenario:** JWT passed as query parameter for SSE EventSource compatibility. Tokens get logged by reverse proxies, browser history. Full account access for 24h.
**Fix:** Issue scoped SSE-only tokens with short TTL (5 min).

#### 2.3 Actuator Endpoints Exposed Without Authentication
**Component:** `SecurityConfig.java:120`
**Scenario:** `/actuator/prometheus` reveals 30+ custom metrics including cost data, token counts, provider health. `/actuator/info` may expose build metadata.
**Fix:** Restrict to SUPER_ADMIN, or only expose `/health` publicly.

#### 2.4 JWT Claims Stale for 24 Hours After Role/Tier Change
**Component:** `JwtUtil.java:43-58`
**Scenario:** Admin downgrades user from ENTERPRISE to FREE. User's JWT still carries elevated tier for up to 24h. No per-user token invalidation.
**Fix:** Reduce access token to 1h, or add per-user invalidation.

#### 2.5 TOCTOU Race in Run Quota Enforcement
**Component:** `TierEnforcementService.java:58-72`
**Scenario:** `assertCanSubmitRun()` and `incrementRunCount()` are separate operations. 3 concurrent requests from FREE user (4/5 runs) all pass assertion, yielding 7 runs on 5-run plan.
**Fix:** Use atomic Redis INCR with compare, or DB `SELECT ... FOR UPDATE`.

#### 2.6 READ/WRITE Groups Skip Rate Limiting Entirely
**Component:** `RateLimitFilter.java:74-76`
**Scenario:** All authenticated requests except auth/runs-POST/webhooks are unrate-limited. Attacker can spam preview/deploy endpoints thousands of times per minute, spinning up Cloud Run containers.
**Fix:** Extend rate limiting to all groups, especially resource-intensive endpoints.

#### 2.7 No Refresh Token Rotation
**Component:** `JwtUtil.java:63-76`
**Scenario:** Stolen refresh token remains valid for 7 days regardless of use. Attacker has persistent access alongside legitimate user.
**Fix:** Implement refresh token rotation — invalidate old token when new one is issued.

#### 2.8 Stripe Webhook Parse Discrepancy
**Component:** `BillingWebhookController.java:83-101`
**Scenario:** Stripe signature verified with `Webhook.constructEvent()`, but then raw payload re-parsed with `objectMapper.readTree()`. Potential discrepancy in JSON parsing.
**Fix:** Use the already-verified Event object from `constructEvent()`.

### P2 Issues

#### 2.9 Integration Webhook Wildcard Pattern
**Scenario:** `/api/integrations/*/webhook` permits POST to any integration webhook without auth. Weak adapter verification = unauthenticated entry.

#### 2.10 DEVELOPER Role Sees All Org Runs
**Scenario:** No project-level scoping. Any developer sees all runs in org including other teams' sensitive business logic.

#### 2.11 Subscription Downgrade Failure Swallowed
**Scenario:** `subscription.halted` webhook processing fails, BillingService returns 200, org keeps elevated tier indefinitely.

#### 2.12 Bonus Run Consumption Not Atomic
**Scenario:** Concurrent requests can consume the same bonus run twice.

#### 2.13 InputSanitizer Bypasses
**Scenario:** Zero-width characters break pattern matching. Base64-encoded injections, non-English injections not caught.

#### 2.14 CodeAbuseScanner Gaps
**Scenario:** PowerShell reverse shells, variable indirection, DNS exfiltration not detected.

#### 2.15 XFF Proxy Bypass
**Scenario:** No configurable trusted-proxy-count. Attacker sets custom XFF header for unlimited rate limit buckets.

#### 2.16 Stripe Key in Global Static Field
**Scenario:** `Stripe.apiKey` is a mutable global. May be exposed via debugger, actuator, or error handlers.

#### 2.17 No JWT Audience or Issuer Claims
**Scenario:** Cross-environment token replay possible if staging/prod share signing key.

#### 2.18 Access Token Duration 24 Hours
**Scenario:** Industry standard is 15-60 min. 24h maximizes damage window for stolen tokens.

#### 2.19 Razorpay Webhook Always Returns 200
**Scenario:** Failed processing silently lost. Subscription not downgraded. No automated reprocessing.

### P3 Issues

#### 2.20 Orphaned OrgMember Fallback Grants Broad Access
#### 2.21 Webhook Replay Timing (Dedup Bypass)
#### 2.22 Run ID Enumeration via 403 vs 404
#### 2.23 Redis Failure Multi-Instance Rate Limit Inflation

---

## Section 3: Preview, Deploy & Integrations

### P0 Issues

#### 3.1 Container Network Not Isolated from Host Services
**Component:** `ComposeFileGenerator.java:135-141`
**Scenario:** Bridge network without `internal: true`. Preview containers have full outbound network access �� can reach Redis (6379), PostgreSQL (5432), Caddy admin (2019), and cloud metadata (169.254.169.254).
**Impact:** Free-tier attacker submits spec that generates code accessing `host.docker.internal:5432` to read user data, billing records, JWT secrets.
**Fix:** Use iptables rules or custom bridge network blocking private ranges (169.254.0.0/16, 10.0.0.0/8, 172.16.0.0/12, host.docker.internal).

#### 3.2 Cloud Run Previews Are World-Readable with No Authentication
**Component:** `CloudRunClient.java:72-74, 241-267`
**Scenario:** `--allow-unauthenticated` + `allUsers -> roles/run.invoker`. Preview URL is predictable (`es-preview-{8chars}`). Attacker can craft spec generating phishing pages hosted on trusted `*.run.app` domain.
**Fix:** Add IAM authentication or at minimum a random token in the URL path.

#### 3.3 ServiceNow Webhook Has No Signature Verification
**Component:** `ServiceNowAdapter.java:38-42`
**Scenario:** `verifySignature()` is a TODO no-op. Combined with `skipApproval` from webhook events, this enables unauthenticated autonomous code execution — full pipeline runs without any human review.
**Impact:** Complete autonomous code execution pipeline accessible to unauthenticated attackers.
**Fix:** Implement HMAC or token-based verification. NEVER allow `skipApproval` from unauthenticated sources.

### P1 Issues

#### 3.4 No Disk Quota on Docker Previews
**Scenario:** CPU/memory limits set but no disk space limit. Generated code or `npm install` fills host disk, crashing Docker daemon.
**Fix:** Add `--storage-opt size=2G` or use tmpfs mounts.

#### 3.5 Docker Compose Inherits Host Environment Variables
**Component:** `ComposeExecutor.java:207`
**Scenario:** `pb.environment().putAll(System.getenv())` copies ALL env vars including API keys. If LLM generates Dockerfile with `ARG GITHUB_PAT`, secret baked into image layer.
**Fix:** Whitelist only necessary env vars (PATH, HOME, DOCKER_HOST).

#### 3.6 No Tier-Based Deployment Limits
**Component:** `DeploymentService.java:118-172`
**Scenario:** No check on user tier, billing status, or deployment count. Free-tier user deploys unlimited Cloud Run services.
**Fix:** Add tier-based deployment limits (FREE: 0, PRO: 5, ENTERPRISE: unlimited).

#### 3.7 Integration Webhook Can Skip Plan Approval
**Component:** `IntegrationManager.java:86`
**Scenario:** `skipApproval` flag passed directly from webhook event. Combined with ServiceNow no-auth bug, enables unauthenticated auto-execution.
**Fix:** Never allow `skipApproval` from external webhooks. Force approval for all integration-triggered runs.

#### 3.8 Test Code Executes Arbitrary Java on Host
**Component:** `TestExecutionService.java:60-79`
**Scenario:** Tests run via `mvn test` on host machine with full filesystem access, all env vars, and network access. LLM could generate malicious test code via prompt injection.
**Fix:** Run tests in a container with resource limits and no host network access.

#### 3.9 Cloud Run TTL Enforcement Fragile
**Scenario:** Cleanup relies on @Scheduled job + gcloud CLI. Auth expiration or network failure → services run (and bill) indefinitely.
**Fix:** Add dead-letter mechanism with exponential backoff retry for failed deletions.

### P2 Issues

#### 3.10 In-Memory Session State Lost on Restart
**Scenario:** `ConcurrentHashMap<String, PreviewSession>` lost on JVM restart. Running previews continue billing for up to 30 min until orphan cleanup.

#### 3.11 Concurrent Preview Limit Check Race Condition
**Scenario:** Two concurrent `start()` calls pass limit check before either inserts into `active`. Free-tier user gets 2 previews.

#### 3.12 Caddy Route ID Collision
**Scenario:** Route IDs use first 8 chars of UUID. Two runs with same prefix → second overwrites first.

#### 3.13 Slack Bot Token Stored Without Encryption
**Scenario:** Plaintext in DB. Database compromise → Slack bot token → channel access.

#### 3.14 Test Not Sandboxed
**Scenario:** No chroot, container, seccomp, or AppArmor. No resource limits on test Maven process.

#### 3.15 Test Results Trusted Without Verification
**Scenario:** Compromised test process can write fake surefire reports showing 100% pass rate.

#### 3.16 Preview Start Counter Non-Idempotent
**Scenario:** Counter increments before async build. Build failure + retry → user locked out of previews.

#### 3.17 User-Influenced Dockerfile Content
**Scenario:** If workspace contains pre-existing `Dockerfile`, it's used as-is without sanitization. Worker could be prompted to create malicious Dockerfile.

### P3 Issues

#### 3.18 Caddy Admin API Has No Authentication
#### 3.19 Build Output Logged at INFO Level (May Contain Secrets)
#### 3.20 Test Workspace Path Uses Raw RunId

---

## Priority Matrix — Recommended Fix Order

### Immediate (Before Production)

| # | Issue | Effort | Impact |
|---|-------|--------|--------|
| 2.1 | Hardcoded JWT secret — fail startup if default in prod | 1h | Platform compromise |
| 3.3 | ServiceNow webhook signature verification | 2h | Unauthenticated code execution |
| 3.7 | Block `skipApproval` from external webhooks | 30min | Autonomous execution bypass |
| 3.1 | Container network isolation (iptables/network rules) | 4h | Host compromise |
| 1.2 | Fix MERGE/DECLINE gate bug | 30min | User intent violated |
| 2.3 | Restrict actuator endpoints | 30min | Information disclosure |
| 2.6 | Extend rate limiting to all endpoint groups | 2h | Resource exhaustion |

### High Priority (Week 1)

| # | Issue | Effort |
|---|-------|--------|
| 1.3 | VALIDATING status for background validation | 3h |
| 3.2 | Cloud Run preview authentication | 4h |
| 3.5 | Whitelist env vars in ComposeExecutor | 1h |
| 3.8 | Containerize test execution | 8h |
| 2.5 | Atomic run quota enforcement | 2h |
| 2.4 | Reduce access token to 1h | 1h |
| 1.6 | Atomic SQL for token counters | 1h |
| 1.9 | Mid-task cost check in WorkerAgent | 2h |
| 3.6 | Tier-based deployment limits | 2h |

### Medium Priority (Week 2-3)

| # | Issue | Effort |
|---|-------|--------|
| 1.1 | Idempotent cleanup with compareAndSet | 3h |
| 1.4/1.5 | Semaphore for apply-suggestion + resume | 1h |
| 1.7 | Cache per-key ChatModel instances | 4h |
| 2.7 | Refresh token rotation | 4h |
| 2.13 | InputSanitizer zero-width + base64 bypasses | 4h |
| 2.14 | CodeAbuseScanner PowerShell + DNS exfil | 2h |
| 3.4 | Docker disk quota | 2h |
| 3.14 | Test sandbox (seccomp + resource limits) | 4h |

---

## Architecture Observations

### Strengths
1. **Multi-layer security defense** — 8 layers from request validation to container hardening
2. **Redis-backed circuit breaker** for LLM provider failover is well-designed
3. **Progressive merge** pattern enables task-level recovery without full pipeline restart
4. **Feature flag system** with pub/sub invalidation provides runtime control
5. **Structured observability** with 30+ custom metrics and trace propagation

### Systemic Weaknesses
1. **In-memory state** — Multiple subsystems rely on ConcurrentHashMaps that are lost on restart (preview sessions, budget monitors, active run tracking, background validation flags)
2. **Inconsistent authorization granularity** — Org-level access control without project-level scoping
3. **Generated code trust boundary** — No containment for LLM-generated code in previews, tests, or deployments
4. **Cleanup dependency chains** — Cleanup relies on finally blocks reaching completion; any gap creates orphaned resources
5. **Rate limiting gaps** — The majority of authenticated endpoints (READ/WRITE groups) bypass rate limiting entirely
