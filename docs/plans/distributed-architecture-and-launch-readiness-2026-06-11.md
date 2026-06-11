# Distributed architecture + production launch readiness

> **Status:** PLAN — not started. Written 2026-06-11 from a full code audit (every claim below carries a file reference; the load-bearing ones were verified by hand, not summarized from docs).
> **Scope:** (1) every change needed to run ExecuteSpec on N ≥ 2 backend instances, (2) every production-launch gap that is independent of scaling, (3) the recommended execution order.
> **Related:** [`docs/DEPLOYMENT.md`](../DEPLOYMENT.md) (current single-VM topology), [`docs/operations/slos.md`](../operations/slos.md) (SLOs + acknowledged alerting gaps), [`docs/launch-readiness-2026-06-10.md`](../launch-readiness-2026-06-10.md), [`docs/pending.md`](../pending.md).

---

## 0. Executive summary

Today a run executes **entirely inside the JVM that accepted it**: in-process thread pool (`PipelineExecutorPool`), local-disk workspace (`<agent.workspace>/<runId>/`), and in-memory interrupt handles (`RunOrchestrator.activeRuns`). Everything else — SSE, plan gate, feature flags, rate limits, idempotent submit, half the scheduled jobs — is **already cross-instance safe via Redis/DB** (Section 1). The system is roughly 70% of the way to multi-instance.

The recommended strategy is **sticky run ownership, not workspace virtualization**: make "this instance owns this run" an explicit, leased fact in the database, add one Redis control channel so cancel/pause/tool-results reach the owning instance, and let local disk stay local. Full work distribution (a claim queue + API/worker node split) is a later, separable step.

Independent of scaling, four launch blockers exist today on the single VM: no graceful shutdown, no deployed alert rules, an undrilled backup story, and two billing schedulers that would double-send customer emails the moment a second JVM ever points at the prod DB.

Execution order: **Wave 0** (single-VM hardening, ~2–3 days) → **Wave 1** (correct at N ≥ 2 + rolling deploys, ~2 weeks) → **Wave 2** (true work distribution, ~3–4 weeks) → **Wave 3** (operational maturity, ongoing). Full sequencing in Section 4.

---

## 1. Inventory — already multi-instance safe (verified; do not rebuild)

| Component | Mechanism | Evidence |
| --- | --- | --- |
| Live SSE run events | Every `emit()` publishes to Redis pub/sub `sse:{runId}` with an `INSTANCE_ID` echo-suppression marker; every instance subscribes to pattern `sse:*` and fans out to its local emitters. A client connected to instance A receives events from a pipeline on instance B today. | `sse/SseBroadcaster.java` (emitters map, `SseRedisListener`, `INSTANCE_ID`) |
| SSE replay / reconnect | `sse:replay:{runId}` Redis list (24 h TTL, 500-event cap) + `Last-Event-ID`; any instance serves a reconnect or `/api/runs/{id}/event-log`. | `SseBroadcaster.replayMissedEvents`, `RunController.getEventLog` |
| **Plan-gate approval** | Pipeline thread waits on an in-memory future **plus** a 500 ms Redis `getAndDelete` poll, with a `gate_record` DB fallback; approval claims are CAS'd (`tryResolveIfPending` — `UPDATE … WHERE status='PENDING'`). Approving on the "wrong" instance works with ≤ 500 ms latency. Verified by hand. | `harness/GateStore.java:207` (`awaitPlanGate`) |
| Feature flags | In-memory cache + Redis pub/sub invalidation channel `executespec:feature-flag-update`; a flip on instance A propagates to instance B in ~100 ms. | `config/featureflag/FeatureFlagService.java` |
| Rate limiting | Redis counters (global across instances). In-memory fallback on Redis outage halves limits per instance — conservative fail-open. | `ratelimit/RateLimitFilter.java` |
| Run-submit idempotency | Redis `run:idem:{userId}:{key}` (24 h, fail-open), consulted before the quota increment. | `run/RunIdempotencyService.java` |
| Worker context snapshots | Redis `v2:agent-ctx:{runId}:{taskId}` (24 h TTL) — readable from any instance for crash-resume. | `context/ContextStore.java` |
| Project workspace lock | Redis `setIfAbsent` lock `project:ws-lock:{projectId}`, 5-min TTL, retry loop. | `project/workspace/ProjectWorkspaceService.java` |
| Preview concurrency limits | Redis counter `preview:count:{userId}` with 1 h TTL, tier-based caps. | `deployment/preview/GlobalPreviewLimiter.java` |
| ProgramBench container map | Redis-backed `runId → containerId`. (Container itself is on the owner node's Docker daemon — fine under sticky ownership, see D16.) | `programbench/ProgramBenchContainerStore.java` |
| Stripe webhooks | Event-ID dedup in DB (`findByGatewayAndEventId` → IGNORED on replay); retry-safe. | `billing/controller/BillingWebhookController.java` |
| Side-effect outbox | DB CAS claim (`markProcessing` `UPDATE … WHERE status=PENDING`; loser sees 0 rows and skips) — execution is exactly-once across instances even without a lock. | `saga/SideEffectExecutor.java` |
| Auth | Stateless JWT (no `HttpSession`); scoped SSE tokens are stateless JWTs; the CLI OAuth return-URL rides a cookie (`OAuthRedirectCookieFilter`). One residual check: D8. | `auth/security/` |
| In-app notifications | DB rows + frontend polling — any instance serves reads. No push channel to break. | `notification/` |
| 12 of 25 scheduled jobs | Already `@SchedulerLock` (ShedLock is wired and proven in this codebase). Census in Appendix A. | various |
| Flyway on concurrent boot | Flyway takes its own DB lock; two instances migrating simultaneously serialize safely. `ddl-auto: validate` everywhere. | `application.yml` |
| Workspace GCS shipping (partial) | `WorkspaceGcsUploadListener` (5-min ShedLocked upload of missing workspaces) + `workspaceRestoreService.ensureWorkspaceAvailable(runId)` exist for crash-resume within a 6 h staleness window. Foundation for D15. | `deployment/WorkspaceGcsUploadListener.java`, `RunLifecycleService.java:360` |

Postgres (Cloud SQL) and Redis (Memorystore) are already external shared services. The hard parts of going distributed were partially pre-paid; what follows is the remainder.

---

## 2. Part A — multi-instance work items (D-series)

Severity legend: **BLOCKER** (wrong behavior at N ≥ 2), **HIGH** (customer-visible degradation), **MED** (waste/staleness), **LOW** (cosmetic or deliberate).

### D1. Run ownership lease (foundation) — BLOCKER

- **Problem:** Nothing records *which* instance is executing a run. Every other D-item needs this fact.
- **Design:** Add `owner_instance_id` (string: hostname+PID, same derivation as `SseBroadcaster.INSTANCE_ID`) and `owner_lease_expires_at` (timestamptz) to `pipeline_run`. Stamp at pipeline start (inside `runPipeline`, before the first task). A single `@Scheduled(30s)` heartbeat per instance renews leases for all locally-active runs in one `UPDATE … WHERE run_id IN (…)`. Clear on terminal.
- **Migration:** next free `V{n}__run_owner_lease.sql` — **check `ls src/main/resources/db/migration/` first** (head is V237 at time of writing, so V238 — verify before creating).
- **Touch points:** `integration/RunOrchestrator.java` (stamp), new heartbeat component, `state/entity/PipelineRun`.
- **Acceptance:** every non-terminal V2 run row carries a live lease; lease visible via an admin/read endpoint; lease expires within 90 s of `kill -9` on the owner.
- **Effort:** ~1 day.

### D2. Redis run-control channel (cancel / pause / tool-results routing) — BLOCKER

- **Problem (verified by hand):** `RunLifecycleService.cancel()` ([RunLifecycleService.java:131](../../src/main/java/com/navneet/executespec/integration/RunLifecycleService.java)) writes `CANCELLED` to the DB first (good — the zombie pipeline's terminal writes become no-ops) but the actual stop is `controlPort.removeActiveRun(runId).cancel(true)` against the **local** `activeRuns` map (`RunOrchestrator.java:245`). If the LB routes the cancel to a non-owner instance, the interrupt is a silent no-op and the pipeline keeps burning LLM tokens until it finishes naturally. `pause()` is softer (sets `pause_requested` in the DB; the executor checks it cooperatively at task/iteration boundaries) but its immediate interrupt has the same gap. `haltAllActiveRuns` (M-010 incident response) inherits the same backstop gap.
- **Problem 2 (same fix):** `RemoteToolDispatcher.sessions` (`tool/remote/RemoteToolDispatcher.java:49`) is an in-memory `runId → {callId → CompletableFuture}` map. A Terminal-Bench / local-hands client POSTing `/api/runs/{id}/tool-results` to a non-owner instance gets a silent drop (`complete()` logs debug and returns) and the worker's tool call times out after 10 min.
- **Design:** One broadcast Redis pub/sub channel `run-control` (copy the `SseBroadcaster` listener + `INSTANCE_ID` echo-suppression pattern; do **not** invent a new mechanism). Message: `{runId, action: CANCEL|PAUSE|TOOL_RESULT, payload, from}`. Receiving controller does its DB writes exactly as today, then publishes. Every instance's subscriber checks `activeRuns.containsKey(runId)` (or `RemoteToolDispatcher.sessions`) and applies the local interrupt / future-completion only if it owns the run. DB status stays the source of truth; the channel is only the interrupt/delivery path. Degraded mode (Redis down) = today's behavior — DB-first semantics still bound the damage.
- **Touch points:** `RunLifecycleService` (publish on cancel/pause/halt), `RemoteToolController` (publish TOOL_RESULT when no local session), new `runcontrol/RunControlChannel` listener, `RunControlPort` seam (already exists).
- **Acceptance:** two local instances against one Postgres+Redis; cancel submitted to instance A stops a pipeline on instance B within ~1 s (assert via worker iteration log stopping); a TB tool-result POSTed to the non-owner completes the pending call on the owner.
- **Effort:** ~2 days including the two-instance test rig (Section 5).

### D3. Claim-based startup recovery — BLOCKER

- **Problem:** `StartupRunResumer.scanAndRecover()` (`recovery/StartupRunResumer.java:113`) scans for orphaned non-terminal runs on boot with **no locking**. Two instances booting simultaneously (any rolling deploy) both resume the same runs → double execution, double cost. It also can't distinguish "orphaned" from "owned by a healthy peer."
- **Design (depends on D1):** Only consider runs whose lease is expired (`owner_lease_expires_at < now()` or NULL). Claim via CAS: `UPDATE pipeline_run SET owner_instance_id=:me, owner_lease_expires_at=:t WHERE run_id=:id AND (owner_lease_expires_at < now() OR owner_instance_id IS NULL)` — proceed only if 1 row updated. Delay the boot scan by one lease TTL so a rolling deploy doesn't steal runs from a still-draining peer.
- **Acceptance:** boot two instances simultaneously against a DB with 3 orphaned runs → each run resumed exactly once (split between instances is fine); a run owned by a live instance is never touched.
- **Effort:** ~1 day.

### D4. ShedLock the remaining global scheduled jobs — BLOCKER (two of them), rest MED

Verified census in Appendix A. ShedLock is already a dependency with 12 annotated jobs — these are one-annotation fixes plus a judgment call each on `lockAtMostFor`.

**Must lock (side effects duplicate or race):**

| Job | Cadence | Failure at N ≥ 2 |
| --- | --- | --- |
| `billing/scheduler/RenewalReminderScheduler` | daily 09:15 | **Every renewal-reminder email sent twice.** Launch-blocking even at N=1 (an accidentally-started second JVM against prod DB double-sends). |
| `billing/scheduler/DunningScheduler` | daily 09:00 | **Every dunning email sent twice.** Same blast radius. |
| `billing/scheduler/PeriodEndDowngradeScheduler` | every 30 min | Downgrade writes are idempotent but emit duplicate notifications/audit rows. |
| `deployment/preview/PreviewService.cleanupOrphans` | every 30 min | Concurrent `gcloud run services delete` / compose `down` on the same orphan → races. **Split it:** the Cloud Run sweep needs the lock; the local docker-compose sweep is per-node and must stay unlocked (see "deliberately per-node" below). |
| `deployment/preview/vm/PreviewGarbageCollector` | every 10 min | Duplicate registry sweeps; idempotent but racy on delete. |
| `deployment/DeploymentCleanupJob` | hourly | Duplicate cleanup; idempotent but wasteful. |
| `compliance/DataRetentionService` | daily 03:00 | Duplicate retention sweep; idempotent deletes, double audit noise. |
| `integration/IntegrationEventLogCleanupJob` | daily 03:00 | Duplicate deletes; benign, lock for hygiene. |
| `integration/slack/SlackNotificationLogCleanupJob` | daily 04:00 | Duplicate deletes; benign, lock for hygiene. |
| `router/ProviderStatusService` | every 60 s | N× outbound polling of provider status pages; lock or accept the waste. |

**Optional lock (already correct, lock only reduces load):** `saga/SideEffectExecutor` (DB CAS makes execution exactly-once; lock just stops N-way polling), `config/featureflag/FeatureFlagService` 60 s reload (idempotent cache refresh — arguably *should* run on every instance; leave unlocked, it is per-instance cache hydration by design).

**Deliberately per-node — do NOT lock:** `deployment/preview/engine/BuildCachePruneJob` (prunes the **local** Docker buildkit cache — every node must run it), `PreviewService.cleanupExpired` (iterates the instance-local `active` preview map — every node reaps its own). Add a code comment on each so a future sweep doesn't "fix" them.

**False positives (no action):** `standards/override/OverridePatternDetector` and `StandardsOverrideController` mention `@Scheduled` only in javadoc.

- **Effort:** ~half a day including cadence/`lockAtMostFor` review.

### D5. `WorkspaceLockService` → Redis lock — HIGH

- **Problem:** `deployment/WorkspaceLockService.java` is a `ConcurrentHashMap<String, ReentrantLock>` — per-JVM only. It guards preview/deploy against concurrent operations on the same `workspace/{runId}/`. Two instances can both "acquire" it and interleave writes.
- **Design:** Mirror the `ProjectWorkspaceService` Redis `setIfAbsent` + TTL + retry pattern, key `workspace-lock:{runId}`. (Under strict sticky ownership the window is small, but preview/deploy endpoints are LB-routed and not owner-pinned, so this lock genuinely crosses instances.)
- **Effort:** ~half a day.

### D6. Atomic `ci_auto_fix_attempts` + GitHub webhook dedup — HIGH

- **Problem A:** `project/pr/CiFailureAutoFixService` increments `ci_auto_fix_attempts` via entity read-modify-write → two instances processing the same `check_run` event can both read 2, both write 3, and the 3-attempt cap is breached (unattended autofix loop risk — exactly what the cap exists to prevent).
- **Fix A:** `@Modifying @Query("UPDATE PullRequestRecord p SET p.ciAutoFixAttempts = p.ciAutoFixAttempts + 1 WHERE p.id = :id")` and gate on the returned value, or `@Version` optimistic locking on the entity.
- **Problem B:** `/webhooks/github` has no delivery-level dedup — GitHub redelivery or LB retry re-processes the same event (Stripe already has this; GitHub doesn't).
- **Fix B:** Dedup on the `X-GitHub-Delivery` header — Redis `SETNX webhook:gh:{deliveryId}` with 24 h TTL (cheap), or a `webhook_event_dedup` table if an audit trail is wanted. Return 200 on duplicate.
- **Effort:** ~1 day for both.

### D7. `JobRegistry` background-process cleanup under ownership — MED

- **Problem:** `tool/JobRegistry.java:41` holds OS `Process` handles in-memory. Fine under sticky ownership (jobs live and die with the owner node), but a crash-resume on another node leaks the original node's processes — `cleanup(runId)` on the new owner finds nothing.
- **Design:** Acceptable leak short-term (flag `worker.background-jobs.enabled` is OFF). Before flipping the flag at N ≥ 2: add a node-local sweeper that kills any `es-bgjob-*` process whose run is terminal-or-not-owned-here (the lease from D1 makes "not owned here" answerable). Note this is already a pre-flip TODO in CLAUDE.md (run-end cleanup wiring).
- **Effort:** ~half a day, deferred until the flag matters.

### D8. OAuth2 `AuthorizationRequestRepository` — MED (verify, then maybe fix)

- **Problem:** Spring Security's default OAuth2 client keeps the in-flight authorization request in `HttpSessionOAuth2AuthorizationRequestRepository`. The app is otherwise session-free, and the new `OAuthRedirectCookieFilter` only carries the *return URL*, not the authorization-request state. If the default repo is active, an OAuth callback landing on a different instance than the one that started the flow fails (`authorization_request_not_found`). With LB `CLIENT_IP` affinity this rarely fires — which makes it a flaky, hard-to-debug login failure rather than a loud one.
- **Action:** Verify what repository `SecurityConfig` actually uses. If default: replace with a cookie-based `AuthorizationRequestRepository` (stateless, standard pattern, ~80 LOC) — strictly better even at N=1.
- **Effort:** ~half a day including the verification.

### D9. BYOK key-rotation cache staleness — MED

- **Problem:** `LlmRouter.clientCache` (Caffeine, 1 h) and `RunKeyStore` L1 cache are per-instance. An org rotating its BYOK key on instance A leaves instance B serving the **old key** for up to 1 h — failed calls at best, "key I revoked is still in use" at worst.
- **Design:** Publish an invalidation message on key write (reuse the feature-flag pub/sub pattern: channel `executespec:key-invalidate`, payload orgId/userId), listeners evict. Alternatively drop TTLs to ≤ 5 min and accept the window.
- **Effort:** ~half a day.

### D10. Spec-chat SSE is instance-local — MED (flag-OFF today)

- **Problem:** `POST /api/spec/chat` (`SpecChatController`) creates an inline `SseEmitter` and runs the agentic loop synchronously in the request thread. No pub/sub, no replay. At N ≥ 2 this only works because request and stream share one HTTP connection — but it also pins a Tomcat thread for the whole loop (see L7).
- **Design:** Before `spec.chat.enabled` (V229) is ON at N ≥ 2 with real CLI usage: route events through `SseBroadcaster` keyed by a chat-session id (gets pub/sub + replay for free), and move the loop off the request thread. Until then: no action — the flag is OFF and prod is byte-identical.
- **Effort:** ~1–2 days when scheduled.

### D11. Infra: MIG, rolling deploys, health-based routing — BLOCKER (for N ≥ 2 to exist at all)

- **Current (from `docs/DEPLOYMENT.md` + terraform):** single GCE VM per env, GH Actions builds JAR → GCS → `systemctl restart` over SSH; GCP LB with `CLIENT_IP` affinity exists in terraform but fronts one VM; Cloudflare → Caddy → :8080.
- **Design:**
  - Managed Instance Group from an instance template (the existing `startup.sh` is most of the template already); min 2, max N.
  - LB health check → `/actuator/health` readiness (distinguish readiness from liveness — readiness must go DOWN during drain, see L1).
  - Rolling replacement deploys (maxSurge 1 / maxUnavailable 0) replacing `systemctl restart` over SSH; deploy workflow updates the template version.
  - Session affinity: **not required** for run SSE (pub/sub handles it); keep `CLIENT_IP` affinity until D10 lands (spec-chat) — it's harmless.
  - Caddy/origin-cert termination moves to the LB or stays per-node in the template — decide during implementation; either works.
- **Acceptance:** deploy with zero dropped requests (loadtest running during deploy); kill one VM → LB drains it, runs on it are reclaimed by D3 after lease expiry.
- **Effort:** ~3–4 days (terraform + workflow + verification).

### D12. Global admission control (run caps are per-JVM) — HIGH

- **Problem (verified):** `agent.max-runs: 20` ([application-prod.yml:104](../../src/main/resources/application-prod.yml)) is enforced in-JVM. N instances silently means N×20 concurrent runs — a 2× cost/capacity surprise the day a second instance starts.
- **Design (interim, pre-D13):** move the admission check to a Redis counter (`INCR`/`DECR` with reconciliation against the DB count of non-terminal runs, since crashed instances leak increments — or simply `SELECT count(*) FROM pipeline_run WHERE status IN (…)` at submit, which is cheap at this volume and crash-proof). D13 replaces this properly with queue depth.
- **Effort:** ~half a day for the DB-count variant.

### D13. Queue-based dispatch (true distribution) — Phase 2

- **Problem:** Submit = execute-on-the-accepting-instance. Load distribution is whatever the LB did to the *submit* request; a burst lands wherever Cloudflare/LB sent it; there is no backpressure, no global cap (D12), and API latency shares a JVM with builds.
- **Design:** Submission writes the run as `QUEUED` and returns 202 (it already returns 202). A claim loop on each worker-capable instance claims work with `SELECT … FOR UPDATE SKIP LOCked` (or reuses the D1/D3 lease-CAS — same primitive) bounded by local capacity. This makes D3 the *general* mechanism rather than a boot-time special case: a dead instance's runs are simply reclaimed work. Plan-gate waits should not hold a claimed slot — either release the claim while `AWAITING_APPROVAL` and re-claim on approval (workspace must then ship, see D15) or keep the gate-wait on the owner and accept the held slot (simpler; recommended first).
- **Acceptance:** 20 submits against 2 instances distribute by capacity, not by LB luck; killing an instance mid-run results in its QUEUED + leased-expired runs being picked up elsewhere.
- **Effort:** ~1 week including failure-path tests.

### D14. API / worker node-role split — Phase 2

- **Design:** A role env (`NODE_ROLE=api|worker|all`) gating: `PipelineExecutorPool` + claim loop (worker), LB membership (api). Workers stop serving customer traffic; API nodes stop running customer builds/Docker. This is also a **security** win — untrusted customer build/test execution stops sharing a failure domain with billing webhooks and auth. Single `all` role preserves today's topology for dev.
- **Effort:** ~2 days on top of D13.

### D15. Workspace shipping for cross-node resume/retry — Phase 2

- **Problem (verified):** resume rejects with `WORKSPACE_GONE` if `<workspace>/<runId>/` isn't local ([RunLifecycleService.java:279](../../src/main/java/com/navneet/executespec/integration/RunLifecycleService.java)); task retry throws `"retry is same-JVM only"` (line 620); "Update PR" needs the workspace too. Under sticky ownership these only fail when the owner is *gone* — at which point GCS restore is the answer anyway.
- **Design:** Extend the existing `WorkspaceGcsUploadListener` + `workspaceRestoreService.ensureWorkspaceAvailable` pair from "crash-resume within 6 h" to: upload on pause + on terminal-with-PR (retry/Update-PR need it), restore on resume/retry when local dir is missing, widen or remove the 6 h staleness cutoff for explicit user-initiated resume. This was already scoped as "chunk #3 of pause/resume" in the code comments.
- **Effort:** ~2–3 days.

### D16. Node-local couplings to document as constraints (no code) — LOW

- Cleanroom containers (SWE-bench/ProgramBench) and local Docker previews live on the owner node's Docker daemon — correct under sticky ownership; never route a `docker exec` for run X to a non-owner.
- The operator Telegram bridge (heartbeat + `getUpdates` poller, VM-side scripts) must run on **exactly one** node — two `getUpdates` consumers race the offset and break delivery. Pin to one node or migrate to webhook mode when N ≥ 2.
- Prod previews are Cloud Run (external) — unaffected.

---

## 3. Part B — production-launch gaps independent of scaling (L-series)

Cross-referenced against [`docs/launch-readiness-2026-06-10.md`](../launch-readiness-2026-06-10.md) and [`docs/operations/slos.md`](../operations/slos.md) §4 — items those docs already track are marked *(tracked)*.

### L1. Graceful shutdown — BLOCKER, untracked

- **Verified:** no `server.shutdown` in any profile; systemd unit hard-restarts (`Restart=always`, `RestartSec=30`). Every deploy today: in-flight HTTP gets RST, SSE streams drop mid-run, webhook deliveries are interrupted, then ~45–65 s of 503s (boot + Flyway + health gate).
- **Fix:** `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 60s` in the prod profile; systemd `TimeoutStopSec=90`; on shutdown, flip readiness DOWN first (so the LB/Caddy drains), `broadcaster.complete()` local emitters so clients reconnect-and-replay instead of hanging; verify the pipeline pool's drain behavior (in-flight runs should checkpoint via the existing pause path or be left for D3 reclaim — decide and document).
- **Effort:** ~half a day config + ~half a day verification. **Do this in Wave 0 — it pays off on the single VM immediately.**

### L2. Alert rules + destinations — BLOCKER, *(tracked as gap, still undone)*

- **Current:** Prometheus scrapes and 5 SLOs documented; **zero** rule files, zero notification channels. A stuck-run storm or 5xx spike is currently discovered by customers.
- **Fix (minimum viable, Wave 0):** three alerts wired to the existing Telegram bridge + email: (1) hot-path 5xx rate > 0.5% over 5 min (SLO 5), (2) runs non-terminal > 20 min (SLO 1 — the `executespec.v2.run.*` metrics exist), (3) webhook processing failures / scheduler exceptions. Prod alerts belong in Cloud Monitoring alert policies (terraform), local in `observability/rules.d/`.
- **Follow-ups (Wave 3):** SLO 2/3 need metrics that don't exist yet (verified-score nullness, cost-estimate accuracy at scrape time) — track as L9 companions.

### L3. Backup restore drill + stated RPO/RTO — BLOCKER, untracked

- **Current:** Cloud SQL automated backups (14 d prod, PITR with HA) exist; **no restore has ever been performed**, no RPO/RTO stated, no runbook. Memorystore: STANDARD_HA replicates but has no persistence configured — a full Redis loss drops SSE replay, idempotency keys, rate-limit counters, context snapshots, gate fallback keys. All are reconstructible or fail-safe, but nobody has written that down.
- **Fix:** one timed drill — restore yesterday's backup into a scratch instance, boot the app against it, record the wall-clock; write `docs/operations/disaster-recovery.md` with RPO/RTO, the restore commands, and the explicit "what Redis loss means" table (it's a good story — say it out loud).
- **Effort:** ~1 day.

### L4. Billing scheduler locks — BLOCKER (subset of D4, pulled into Wave 0)

`RenewalReminderScheduler` + `DunningScheduler` + `PeriodEndDowngradeScheduler` get `@SchedulerLock` **now**, independent of any scaling plan. The failure mode (double-billing emails) is reachable today by one accidental second JVM against the prod DB, and the fix is three annotations.

### L5. Secrets: wire the Terraform module + rotation runbook — HIGH, *(tracked)*

- `infra/modules/secrets` exists but is not in the root composition; operators bootstrap by hand via `seed-secrets.sh`; startup falls back to GCS plaintext. Wire the module, document rotation per provider key (LLM keys, JWT secret, webhook secrets), and schedule removal of the GCS plaintext fallback.

### L6. Prod deploy path in CI — HIGH, *(tracked)*

- Deploy workflow auto-fires on `dev` only; prod is a manual SSH procedure. Add a protected `workflow_dispatch` prod job (same JAR-by-SHA artifact, environment protection rule, manual approval). Becomes the rolling-deploy trigger after D11.

### L7. Capacity guards — HIGH, untracked

- **Connection arithmetic:** Hikari `maximum-pool-size: 30` × N instances vs Cloud SQL `max_connections=100` — at N=3 you're at the edge. Decide the per-instance pool size as a function of N (or add pgbouncer) **before** the second instance, not after the first connection storm.
- **Thread-pinning endpoints:** `POST /api/spec/chat` runs its agentic loop in the request thread (D10); project-chat "apply" is already on a dedicated executor — audit for any other long-synchronous handlers.
- **Disk:** 50 GB boot disk accumulates Docker images, workspaces, logs; `WorkspaceDiskCleanupJob` exists but there is no disk-usage alert. Add one (ties into L2).
- **Run-cap honesty:** D12 (per-JVM cap) is also a single-instance correctness item the day anyone runs a manual second JVM.

### L8. Frontend serving + quality — MED, *(bundle/tests tracked in `docs/tech-debt.md`)*

- No explicit `Cache-Control`/compression config for static assets; confirm Vite emits content-hashed filenames and set long-max-age + immutable for hashed assets, no-cache for `index.html` — otherwise Cloudflare caching is luck, and deploys can serve stale chunks.
- Known debt, restated for completeness: 802 KB single bundle (no code-splitting), zero frontend unit tests, warn-only eslint in CI.

### L9. Cost + score observability — MED, *(SLO gaps tracked)*

- Cost-anomaly alert (avg cost/run drifting > 2× baseline — catches runaway loops and pricing-table mistakes).
- Per-org spend caps are mentioned in the launch playbook but not wired — decide in or out for launch.
- SLO 2 (verified-score nullness) and SLO 3 (estimate accuracy) need real metrics.

### L10. Incident runbook + on-call basics — MED, untracked

- `haltAllActiveRuns` (M-010) exists as the big red button; nothing documents when/how to use it, who gets paged (the Telegram bridge is the de-facto pager), or the escalation path. One page in `docs/operations/` covering: provider outage, runaway cost, DB failover, Redis loss, bad deploy rollback (JAR-by-SHA relink is the rollback — write it down).

---

## 4. Recommended order

**Wave 0 — single-VM hardening (~2–3 days; do before/with launch, no architecture changes):**
1. L1 graceful shutdown
2. L4 billing scheduler locks (3 annotations)
3. L2 three starter alerts → Telegram + email
4. L3 backup restore drill + DR doc
5. L10 one-page incident runbook (cheap while L3 context is loaded)

*Rationale: removes the embarrassing failure modes (dropped runs on every deploy, double-billing emails, silent outages, untested backups) without touching architecture. Everything here also survives unchanged into the multi-instance world.*

**Wave 1 — correct at N ≥ 2 + zero-downtime deploys (~2 weeks):**
1. D1 ownership lease (foundation — do first)
2. D3 claim-based startup recovery (depends on D1; also makes deploys safe)
3. D2 run-control channel (cancel/pause/tool-results)
4. D4 remaining ShedLocks (with the per-node exemptions documented)
5. D5 Redis workspace lock, D6 atomic counter + GitHub webhook dedup, D8 OAuth repo verification — small, independent, parallelizable
6. D12 global run cap (DB-count variant)
7. D11 MIG + rolling deploys + L6 prod CI path (the payoff: second instance + zero-downtime deploys go live here)
8. L7 connection-pool arithmetic decided before the second instance starts

*Exit criterion: two instances in the MIG, rolling deploy with zero dropped requests, cancel/approve/resume correct from either instance, kill-one-VM chaos test passes (runs reclaimed, no duplicates).*

**Wave 2 — true distribution (~3–4 weeks, schedule when run volume or isolation demands it):**
1. D13 queue-based dispatch (subsumes D12)
2. D14 API/worker role split
3. D15 GCS workspace shipping (cross-node resume/retry/Update-PR)
4. D9 BYOK cache invalidation, D7 background-job sweeper (pre-flag-flip), D10 spec-chat broadcast (pre-CLI-chat GA)

**Wave 3 — operational maturity (ongoing):**
L5 secrets wiring + rotation, L8 frontend caching/splitting/tests, L9 cost anomaly + SLO 2/3 metrics + org spend caps, D16 constraint docs, Redis persistence decision follow-through.

**Dependency notes:** D2/D3 hard-depend on D1. D11 should not ship before D2+D3 (a MIG without them = wrong cancels and double resumes). D13 hard-depends on D1/D3 and is much safer after a few weeks of Wave-1 soak. Nothing in Wave 0 depends on anything.

---

## 5. Verification approach (local-first, per the standing rule)

- **Two-instance rig:** run two Spring Boot instances on `:8080`/`:8081` (`SERVER_PORT` override, same `local` profile) against one local Postgres + Redis, with a 10-line nginx round-robin in front. This exercises every D-item except D11 without GCP.
- **Wave-1 smoke matrix on the rig:** submit on A / approve on B; cancel on B for a run owned by A (assert worker stops ≤ 1 s); pause on the non-owner; kill -9 A mid-run → B reclaims after lease expiry, exactly once; TB tool-result POST to the non-owner; GitHub webhook replayed twice with the same `X-GitHub-Delivery`; both billing schedulers forced-fired on both instances (one email).
- **Deploy verification (D11/L1):** `hey`/`vegeta` load against the LB during a rolling replacement — zero non-2xx; an SSE client streaming a run across the deploy reconnects and backfills via `Last-Event-ID` with no gap.
- **Chaos check before calling Wave 1 done:** kill a VM mid-run with a customer-shaped spec; assert single resume, single PR, single charge.

---

## Appendix A — `@Scheduled` job census (verified 2026-06-11)

**Already `@SchedulerLock`ed (12):** `ActivityFeedPartitionJob`, `ActivityFeedRetentionJob`, `MonthlyRunCounterResetJob`, `WeeklyAdminSummaryJob`, `BatchPollService`, `StaleScmCleanupJob`, `WorkspaceDiskCleanupJob`, `WorkspaceArchiveJob`, `WorkspaceGcsUploadListener`, `AdminReconciliationService`, `SandboxCleanupJob`, `StackRefreshJob`.

**Unlocked — must lock (D4):** `RenewalReminderScheduler`, `DunningScheduler`, `PeriodEndDowngradeScheduler`, `DataRetentionService`, `DeploymentCleanupJob`, `PreviewGarbageCollector`, `IntegrationEventLogCleanupJob`, `SlackNotificationLogCleanupJob`, `ProviderStatusService`, `PreviewService.cleanupOrphans` (Cloud Run half only).

**Unlocked — optional:** `SideEffectExecutor` (DB CAS already exactly-once), `FeatureFlagService` reload (per-instance cache hydration by design — leave).

**Unlocked — deliberately per-node, never lock:** `BuildCachePruneJob` (local Docker cache), `PreviewService.cleanupExpired` (instance-local preview map).

**False positives:** `OverridePatternDetector`, `StandardsOverrideController` (`@Scheduled` in javadoc only).
