# ExecuteSpec — Pending plans

> Consolidated 2026-05-12. Replaces the per-plan files previously under
> `docs/plans/` (8 separate files → this one). Each section captures the
> minimum needed to pick up the work cold: status, what's shipped, what
> remains, owner / branch / dependencies.
>
> Removed in the same pass:
> - `pending-ga-readiness-90-day-execution-plan.md` — to be rewritten.
> - `pending-complimentary-pro-promo-2026-04-30-plan.md` — to be rewritten.
> - `pending-agent-builder-plan.md` — parked 2026-04-29 ("wrong product
>   shape" per founder); historical only.
> - `phase-2-3-deferred.md`, `ux-overhaul-v4.md`, `pre-deploy-open-items.md`,
>   `wave-7-issue-list.md`, `eval-2026-04-26-partial-and-openai-degradation.md`
>   — stale; work either shipped or superseded by newer audits.

## Status snapshot

| # | Plan | Status | Branch / owner |
|---|------|--------|----------------|
| 1 | Partial / follow-up work (Mobile, Figma v2, Harness V2 gaps, PARTIAL-status removal) | Mixed — partials shipped in v2.5.13; follow-ups not scheduled | various |
| 2 | Backlog deferred items (M-035/036/037/040/041/048/050/052) | Deferred — owner-needed | n/a |
| 3 | V2 batch execution | Not started; unblocked | n/a |
| 4 | Distributed run processing (Phase A NFS + Phase B worker pool) | Draft for review | n/a |
| 5 | VS Code extension | Deferred to external repo `executespec-vscode` | n/a (separate repo) |
| 6 | Project-surface Vite split (Phase 2 of v2.7 product split) | Pending; multi-week + 14-day customer notice | n/a |
| 7 | Readymade Solutions library | W1–W5 shipped end-to-end functional; W6–W9 reserved | `feature/readymades-and-migrations` |
| 8 | Migrations product | W1–W7 shipped on branch; W8–W10 deferred | `feature/readymades-and-migrations` |

---

## 1. Partial / follow-up work

Four sub-plans where the primary work shipped but follow-ups remain.

### 1.1 Mobile codegen — RN+Expo follow-ups

**Shipped (v2.5.13, PR #46):** `projectType=react-native-expo`,
`runtimeSurface=MOBILE_APP`, scaffold + BuildCommandResolver, shell-sandbox
expo/eas/npx verb families, `DevDockerfileGenerator.reactNativeExpoDockerfile()`
(nginx-served `dist`), frontend `MobilePreviewPanel.tsx` with Expo Go QR
pairing, feature flag `codegen.react_native.enabled` (V118). Verified live
on Calorie Tracker (`521236da`) + Habit Tracker (`49f8a507`).

**Pending:**
1. **Deploy story (EAS Build → GCS)** — `DeploymentService` REACT_NATIVE
   branch not implemented. Plan: `eas build --platform <t> --profile production --non-interactive --no-wait`,
   org-scoped EAS token (`org.eas_token`, new migration), poll EAS REST,
   download signed APK/AAB/IPA, re-upload to
   `gs://executespec-mobile-builds/<orgId>/<runId>/<platform>.<ext>`,
   return 24h-signed URL.
2. **iOS/Android signing custody** — out of scope v1; users upload p12 +
   provisioning profile + keystore to EAS via their own dashboard.
3. **`expo-doctor` static-analysis gate** in test planner.
4. **Native Android (Kotlin + Compose) path** — explicitly v2; eject via
   `npx expo prebuild`.
5. **In-browser preview via `react-native-web`** — v2.
6. **`eas submit` (Store direct upload)** — needs per-org service-account
   key management + track/rollout UX.
7. **Connected device tests (Detox / Maestro)** — needs emulator or
   device-farm story; v2.

**Open questions:** EAS billing pass-through vs absorb vs BYO-EAS-token
(stopgap: BYO); bundle-size budget warn at >8 MB gzipped; iOS gate to
require Apple Developer account before accepting `target: BOTH` in
classifier.

**Pending metrics:** `executespec.codegen.rn.eas.minutes_used{platform,profile}`,
`bundle.size_bytes{platform}`, `expo_doctor.findings{severity}`.

### 1.2 Figma integration — v2

**Shipped (basic):** `RunRequest.figmaUrl` + `pipeline_run.figma_url` column;
optional URL drives the design-compliance dimension on `ReviewScoringService`
(heuristic, no real Figma fetch).

**Pending (entire integration):**
- OAuth + credential storage: `FigmaOAuthService` + `FigmaOAuthController`
  + `figma_credential` table.
- `FigmaIntegrationGate.enforce(orgId, runRequest)` from run submission +
  `ToolRegistry.bind(ctx)`.
- `FigmaExtractionService` (MCP-primary, REST-fallback), Resilience4j
  circuit breaker (5 fail / 60s), Caffeine cache (30 min TTL by
  `(orgId, fileKey, nodeId, format)`).
- Clients: `FigmaMcpClient` (lazy `McpSyncClient` per org, cached) +
  `FigmaRestClient` (WebClient, exp 429 backoff, 10MB JSON truncation).
- V2 tool `FigmaDesignContextTool` conditionally bound; JSON schema
  `figmaUrl` / `outputFormat` / `includeScreenshot`. Cost via `CostHook` →
  `RunCostTracker` phase `INTEGRATION`.
- Prompt augmentation: section in `prompts/outline-planner.md` +
  `worker.md` when figma URLs present.
- Validation report: `designComplianceJson` (color/typography/layout-match %).
- Schema: new `figma_credential` + `pipeline_run.figma_urls TEXT[]` +
  `figma_context_path TEXT`. Renumber to current V-sequence.
- Flags: `integration.figma.{enabled, mcp_enabled, rest_enabled, max_screenshots_per_run, max_calls_per_run}`.
- Frontend: Settings → Integrations Figma card + NewRunModal Figma URL
  field + ValidationReportPanel Design Compliance row.

**Blocking open question:** MCP server-to-server auth — Spring AI 1.1.4's
`HttpClientSseClientTransport` takes a fixed URL at construction; per-user
bearer injection unclear. Three options to decide after P0 probe:
(a) per-user singleton client w/ Caffeine TTL — simple, loses cross-org
cache; (b) per-request client construction — clean isolation, higher cost;
(c) custom transport accepting `HttpHeaders` from `RequestAttributes` — most
work, best fit.

**Phased plan when scheduled:** P0 manual probe (1 day, throwaway) → P1
OAuth + table + gate → P2 REST + tool + tests → P3 MCP primary path + CB →
P4 prompt integration → P5 UI + docs → P6 asset export (SVG via `/v1/images`,
deferred).

**Est size:** ~22 new files + 10 modified, ~2400 LOC.

### 1.3 Harness V2 — remaining gaps

V2 is the only harness — V1 deleted in deprecation P3 (2026-04-13). All
core surfaces (tool layer, LlmRouter, WorkerAgent, OutlinePlannerAgent,
SequentialExecutor, ReviewerAgent, ContextStore, metrics, RunOrchestrator,
RunCostTracker, ScmPushService extraction, V2 cancel) **done**.

**Gap 1 — Phase 6: batch execution in V2** — not implemented. See §3.

**Gap 2 — Startup resume (full replay)** — Phase 1.5 shipped (fail-cleanly
+ resume_attempts cap of 3 + 6h stale cutoff). Phase 2 pending: for
`WORKING` / `REVIEWING` runs with a fresh Redis snapshot, load
`v2:agent-ctx:{runId}:{taskId}` and resume `WorkerAgent` at the saved
iteration instead of failing. Acceptance: kill JVM mid-run, restart, run
reaches terminal without intervention. Est ~400 LOC.

**Gap 3 — Endpoints returning 410 Gone in V2:**

| Endpoint | Status | Unblock by |
|---|---|---|
| `POST /runs/{id}/retry` (full-run) | 410 | Gap 2 Phase 2 |
| `POST /runs/{id}/pause` + `resume` | 410 | Gap 2 Phase 2 |
| `POST /runs/{id}/rework` | 410 | V2 plan-iteration flow (not specced) |
| `POST /runs/{id}/reopen-merge` | 410 | Permanent — V2 auto-merges |
| `POST /runs/{id}/tasks/{taskId}/retry` | wired baseline | Full replay arrives with Gap 2 Phase 2 |
| `POST /runs/{id}/implement-suggestions` | 410 | V2 chat/iterate flow (separate spec) |
| `POST /runs/{id}/retry-validation` | wired | — |
| `POST /runs/{id}/edit-plan` | 410 | Needs planner re-entry — defer until after batch |
| `POST /runs/{id}/clarify` + `chat` + `apply` + `iterate` | 410 | V2 ConversationAgent integration (TBD) |
| `POST /runs/{id}/run-tests` + `apply-test-fixes` | 410 | Wire once V2 test-gen plumbed through `TestGenOrchestrator` |
| `POST /runs/{id}/merge` | 410 permanent | V2 auto-merges; remove from frontend |
| `POST /api/feasibility` | 410 permanent | Use plan-approval estimate |

**Architectural principles V2 holds:** sequential over parallel; native
tool calling over XML; ground truth via `buildProject` smoke; upfront
context not search-first; provider-agnostic at agent layer; Redis-persisted
context every 5 iters (replay via `ContextStore.load()`). No V1
progressive-merge; no parallel tasks in sequential path; no XML tools.

### 1.4 Removing PARTIAL as a run status (proposal)

**The problem:** `PARTIAL` covers reviewer timeouts, UNRESOLVED issues,
smoke-test fails, iter-cap hits with usable code, and legacy pre-v2.5.10
rows. Users treat PARTIAL ≡ broken and re-run, wasting credits.

**Proposal:** replace with three outcomes from two independent signals.

| codeBuildPassed | reviewerOutcome | New status |
|---|---|---|
| YES | CLEAN \| FIXED | **COMPLETE** (green) |
| YES | UNRESOLVED \| INCOMPLETE | **COMPLETE_WITH_CONCERNS** (amber) |
| NO | any | **FAILED** (red) |
| SKIPPED | any | **FAILED** (red) |

**Data migration:** historical PARTIAL rows → COMPLETE_WITH_CONCERNS when
`final_build_passed=true`, else FAILED. Constraint pins 9 values. `PARTIAL`
stays as `@Deprecated` synonym → COMPLETE_WITH_CONCERNS for one major
version, then deleted.

**Backend:** rewrite `RunOrchestrator.resolveTerminalStatus(exec, review)`
to the buildPassed × reviewerClean matrix. `status_reason` stays (drives
tooltip copy). `REVIEWER_UNRESOLVED` migrates to surfacing on
COMPLETE_WITH_CONCERNS.

**UI:** `StatusBadge` drops PARTIAL, adds COMPLETE_WITH_CONCERNS. List
colour coding: COMPLETE=green, COMPLETE_WITH_CONCERNS=amber, FAILED=red.

**Credit semantics:** COMPLETE / COMPLETE_WITH_CONCERNS → no refund;
FAILED → refund (clearer than today, where PARTIAL doesn't refund even on
unusable output).

**Rollout:** flag `status.tri-state.enabled` default off → 2-week staging
soak → flip globally → drop flag + deprecated enum at next major. Est ~1
day backend + 0.5 day FE + 0.5 day QA.

---

## 2. Backlog deferred items (M-035..M-052)

Eight items deferred because of multi-month engineering scope, pending
product decision, or off-repo compliance work. Each has a first-slice
path so the next engineer can pick it up cold.

### M-035 — Multi-org support

Single `AppUser` belongs to multiple orgs with role independence, header
context switch, separate billing per org. **First slice:** shadow-table
migration `app_user_org_membership` (UUID, user_id, org_id, role,
joined_at, PK on user_id+org_id). Backfill from `AppUser.orgId`. Don't
read yet — dual-write on org-add only. De-risks data model before
touching auth. **Owner:** engineer with auth-layer experience + security
review buddy. **Effort:** 4–6 weeks + security review.

### M-036 — Custom roles

ORG_ADMIN defines custom roles beyond fixed set; each has permission
bitmap. **First slice:** `Permission` enum + `OrgRolePermission` join
table (orgRoleName → permission). Map existing 5 roles. SUPER_ADMIN tool
to mint roles (no ORG_ADMIN exposure yet). Cut @PreAuthorize sites over
once stable. **Owner:** same as M-035. **Effort:** ~3 weeks grep-and-patch
+ new UI.

### M-037 — SCIM provisioning

Okta / Azure AD push user lifecycle via SCIM 2.0. Blocks on M-016 (SAML)
being live. **First slice:** stub `/scim/v2/Users` + `/scim/v2/Groups`
returning 501 "SCIM coming Q3 2026". Document customer workaround in
[`sso-saml-wiring.md`](../sso-saml-wiring.md). **Owner:** engineer
post-SAML; ~2 weeks.

### M-040 — Per-team RBAC (within an org)

Sub-divide org members into "teams" gating project access. Depends on
M-036. **First slice:** wait for M-036. **Owner:** same as M-036.

### M-041 — Security questionnaire templates

ENT customers send SIG / CAIQ / custom during procurement. **First
slice:** add pointer in [`enterprise-one-pager.md`](../enterprise-one-pager.md):
"email admin@executespec.ai with the template — we maintain SIG, CAIQ,
and a custom template internally". Compliance team owns storage.
**Owner:** legal/compliance, not engineering.

### M-048 — Conversational refinement default

Default NewRunModal opens chat tab vs compose tab. Parked: A/B test
required. **First slice:** wire PostHog flag `ux.compose-default` =
`compose | chat | A/B`. Run 4 weeks; pick winner. **Owner:** product /
growth.

### M-050 — Multi-tenant seed data

Flyway seed migration for demo orgs / users / runs. Depends on M-035
(data shape settles first). **First slice:** wait for M-035.
**Owner:** any engineer post-M-035; 1 day.

### M-052 — ENTERPRISE floor public pricing

Publish hard floor on `/plans` ENTERPRISE card (e.g. "from $24,000/year").
Parked until 5 paying ENT customers. **First slice:** copy +
`billing_plan` row. **Owner:** sales/finance.

**When to re-open this section:** after M-035 ships → M-040/M-050 unblock;
after M-036 → M-040 unblocks separately; after M-016 (SAML) live → M-037
priority; after 5 ENT → M-052; after 4-week A/B → M-048.

---

## 3. V2 batch execution

**Status:** ready to execute. **Sequencing unblocked** — V1 `batch/*`
deleted in deprecation P3 (commit `154169d9`, 2026-04-13). P0 probe + P1
scaffolding can start immediately.

**Goal:** port V1 batch path to V2 primitives. BATCH-mode approval pays
50% provider discount; worker tasks run async against Anthropic / OpenAI /
Gemini Batch APIs; reviewer + test-gen + preview stay realtime. Functional
parity with V1 batch, not a new user-facing capability.

**Non-goals:** changing billing model (still 50% credit savings at plan
approval); batching reviewer / test-gen / test-planner / accuracy-assessment
(stays realtime); mixed real-time + batch in one run (a run is wholly one
or the other, chosen at plan approval); user-visible provider-specific
features.

**Open questions to resolve in P0:**
1. **Native tool calling in batch mode** — all three batch APIs accept the
   same body as realtime (incl. `tools` / `tool_choice`) but verify per
   provider. Fall back to XML-tool-calling only if broken.
2. **Multi-turn in batch** — workers are multi-turn; batch APIs are
   single-shot. Options: (a) batch only the FIRST turn per task, remainder
   realtime; (b) serialize each turn as separate batch submission (high
   latency); (c) plan-only batch; (d) single-shot workers (force
   SINGLE_SHOT classifier).  **Proposed v1: (a)** — batch first turn
   (usually reads + first writes); remaining turns go realtime. Document
   the tradeoff; revisit after measuring discount capture.
3. **Retry semantics** — same as V1: auto-retry failed batch tasks in
   realtime via `SequentialExecutor`.
4. **Credit refund on partial batch failure** — keep V1 behaviour: no
   refund on partial; retry cost absorbed by 50% headroom.

**Architecture:**
```
[plan approval: executionMode=BATCH]
    ▼
RunOrchestrator.submit() → V2BatchCoordinator.submit(plan)
    → V2BatchRequestBuilder.build(task, plan, ctx)   # one BatchRequest per task's first turn
    → V2BatchClientRouter.submit(provider, requests)
        ├─ V2AnthropicBatchClient
        ├─ V2OpenAiBatchClient
        └─ V2GeminiBatchClient
    → BatchJob{runId, providerJobId, state=QUEUED}
    → Redis index v2:batch:{runId} → provider + jobId
    → [@Scheduled 30s] V2BatchPollService
        → on terminal: V2BatchResumeService.resume(runId, results)
            → parse → write files → append history
            → SequentialExecutor.continueFromBatch(runId, perTaskHistory)
                  if first-turn hit completeTask → SUCCESS
                  else resume realtime (WorkerAgent)
    → Normal V2 post-exec: reviewer → report → testgen → preview
```

**New classes (~12 files):** `batch/V2BatchClient.java` (interface),
`V2BatchClientRouter`, `V2AnthropicBatchClient`, `V2OpenAiBatchClient`,
`V2GeminiBatchClient`, `V2BatchRequestBuilder`, `V2BatchCoordinator`,
`V2BatchPollService` (@Scheduled 30s + @SchedulerLock),
`V2BatchResumeService`, `V2BatchJob` / `V2BatchRequest` /
`V2BatchTaskResult` records. **Modified:** `RunOrchestrator` (BATCH
branch), `SequentialExecutor.continueFromBatch(...)`,
`WorkerAgent.resumeFromBatch(history, ctx)`, `scripts/v2-eval.sh`
(`--mode batch` flag). **Est size:** ~1200 LOC net.

**Persistence:** reuse existing `PipelineRun` columns
(`batchAvailable`, `batchProgressTotal`, `batchProgressCompleted`,
`batchProviderJobId`). No new schema needed.

**Phased plan:**
- **P0 spike (1 day, throwaway):** probe each provider with single-turn
  native-tool request; measure submit→terminal latency; document
  native-tool verdict.
- **P1 skeleton (1 PR, low risk):** stubs + DI + flag
  `harness.v2.batch_enabled` (default false) + unit tests for builder +
  router.
- **P2 Anthropic end-to-end (1 PR, medium risk):** Anthropic batch full;
  wire Coordinator → Poll → Resume; implement
  `WorkerAgent.resumeFromBatch(history, ctx)`.
- **P3 OpenAI + Gemini (1 PR each, medium):** OpenAI file-upload
  (JSONL) + batch create + fetch; Gemini inline mode then file mode +
  thought-signature round-trip.
- **P4 retest / eval (1 PR, low):** `scripts/v2-eval.sh --mode batch` full
  9-cell matrix vs realtime baseline (accuracy ±5%, credit 50% delta,
  latency expected order-of-magnitude slower).
- **P5 flag flip + docs (1 PR, low):** flip in staging, CHANGELOG,
  CLAUDE.md V2 section, USER_MANUAL batch flow.

**Risks:**

| Risk | Mitigation |
|---|---|
| Native tool calling unsupported in one batch API | P0 detects; fall back to XML-tool-calling for that provider |
| Discount capture < 50% | Explicitly "first turn only" v1; measure in P4; follow-up ticket for (b)/(c) |
| Server crash mid-batch loses jobs | Persist `batchProviderJobId` on submit; PollService resumes from DB on startup |
| Provider outage | 24h timeout; auto-fallback retries remaining tasks realtime; emits `BATCH_FAILED` |
| Rate-limit divergence vs realtime | Per-provider confirm in P0; add per-run queuing in Coordinator if tight |

---

## 4. Distributed run processing

**Status:** draft for review (2026-04-13). Depends on §1.3 Gap 2 (full
StartupRunResumerV2 replay).

**Goal:** move from single-VM run executor to horizontally-scalable
cluster without dropping in-flight runs on deploys or node failures.
Two phases — **A** decouples storage; **B** distributes compute.

**Non-goals:** parallelising tasks within a run (V2 stays sequential);
re-implementing previews (separate plan); migrating Postgres/Redis (both
already external); bespoke job queue (reuse Postgres + Redis lease
coordination).

**Baseline today:** workspace `./workspace/<runId>/` on local disk;
GCS archive exists but flag-disabled; single JVM runs full pipeline; no
run lease; `PipelineRun.status` is only ownership signal — second JVM
pointed at same DB races. JVM crash mid-run → run stuck WORKING; rolling
deploy = run dies with the JVM.

### Phase A — Decouple storage (single compute VM retained)

GCP regional Filestore (NFS) replaces local disk for `<agent.workspace>`.
All `writeFile` / `readFile` / `listFiles` / git ops continue to use
`java.nio.file.Path` — no code change in tools. Archive lifecycle: on
terminal, compress + upload to GCS; delete Filestore workspace after 24h.

**Why Filestore over GCS:** every V2 tool uses `Path`; swapping in GCS
means `gcsfuse` (fragile) or rewriting 10 tools to object APIs. Filestore
is drop-in. Cost at scale: 100 GB hot = ~$30/mo Filestore vs ~$2/mo GCS;
trivial vs engineering cost. Filestore Enterprise tier gives regional HA.

**Phase A PRs:**
- **A1 Filestore provisioning** (ops, low) — Terraform: regional 1 TB,
  VPC-peered; mount `/mnt/workspaces`; systemd unit + health check; flag
  `agent.workspace=/mnt/workspaces` with local-disk fallback.
- **A2 Archival hardening** (medium) — enable `gcs-enabled: true`; on
  terminal zip + upload to GCS; cleanup job deletes Filestore after 24h;
  download-zip prefers Filestore, falls back to GCS redirect.
- **A3 Smoke + cost observation** (no code) — full eval matrix on
  Filestore; p50/p95 tool latency before/after; acceptable ≤20%
  regression.

### Phase B — Worker pool with lease-based assignment

```
Gateway (Cloud Run, stateless) — /api/runs, /api/auth, /api/billing, SSE
    ▼  (POST /runs)
Postgres pipeline_run row created; no lease
    ▼
Worker pool (N × GCE VMs or Cloud Run jobs)
  every 2s: SELECT runId WHERE status IN (QUEUED, RESUMABLE)
            AND NOT EXISTS (lease WHERE heartbeat > now() - 30s)
            LIMIT 1 FOR UPDATE SKIP LOCKED
  on acquire: INSERT run_lease, start heartbeat
  on terminal / SIGTERM / crash: DELETE lease (or TTL expires)
  all file ops → Filestore mount
    ▲ every 10s → run_lease.heartbeat_at
```

**Deliverables:**
- Postgres lease, 30s TTL, 10s heartbeat, FOR UPDATE SKIP LOCKED
  assignment, fencing_token (monotonic bigint) to prevent split-brain
  writes.
- Graceful shutdown: SIGTERM → stop picking up new runs → finish current
  iteration → flush checkpoint → release lease. Max grace 2 min.
- Pool sizing: 3 workers initial (4 vCPU / 16 GB each); autoscale rule
  `active_runs / pool_size ∈ [0.5, 2]`.
- Gateway stateless; SSE via Redis pub/sub topic `run:{runId}:events` so
  any pod relays.

**Checkpoint granularity: per iteration** (sweet spot — ≤1 iter lost on
crash, 0 on graceful deploy; per-tool-call has partial-side-effect risk,
per-task loses a full task's LLM work).

Tool semantics on resume: `writeFile` / `editFile` / `gitCommit` /
`listFiles` / `readFile` safe to re-execute. `shell` NOT idempotent —
mark iterations that invoked shell as "replay-from-iteration-start" and
rerun. Rare in practice (mostly build invocations).

**Recovery flow (StartupRunResumerV2, §1.3 Gap 2 Phase 2):**
```
for run in status ∈ {PLANNING, WORKING, REVIEWING, AWAITING_APPROVAL}:
    if AWAITING_APPROVAL: re-arm GateStore; continue
    snapshot = contextStore.load(runId, currentTaskId)
    if snapshot null (TTL expired, >24h): mark FAILED reason="snapshot expired"; continue
    optional: git reset --hard checkpoint/<runId>/iter<N>
    worker.resumeFromIteration(snapshot.history, snapshot.iter)
    orchestrator continues from post-worker path
```

**Phase B PRs:**
- **B1 Lease table + heartbeat** (low, no behaviour change) — Flyway
  `V_run_lease.sql`, `RunLeaseService`, single JVM still.
- **B2 StartupRunResumerV2** (medium — Gap 2 delivery) — boot scan +
  resume; wire `ContextStore.load()` → `WorkerAgent.resumeFromIteration()`;
  iteration-boundary save (`CONTEXT_PERSIST_EVERY_N_ITERATIONS=1` at high
  change).
- **B3 Graceful shutdown + drain** (medium) — `ApplicationShutdownHook` →
  `RunOrchestrator.drain()`; integration test kills JVM mid-run.
- **B4 Multi-pod deployment** (high, gated on B1–B3 soak) — Terraform MIG
  with 3 workers; gateway separate (Cloud Run stateless); SSE via Redis
  pub/sub; canary 1 pod for 48h.
- **B5 Autoscaling rule** (low) — custom metric
  `executespec.v2.active_runs`; 5-min windows; cooldown 10 min between
  scale events.

### Phase C — Observability & runbooks (1 PR)

Dashboards: lease occupancy, resume count, iteration-save latency,
Filestore p95 read/write, checkpoint replay outcome. Runbook: stuck-run >
30min with no heartbeat. Alerts: lease-stealing spikes, resume failure
rate, workspace quota.

**Cost envelope:** Filestore 1 TB ~$300/mo + 3× n2-standard-4 24/7
~$400/mo + Cloud Run gateway ~$50/mo + GCS nearline ~$10/mo per TB. Delta
vs today (~$120/mo single VM) **~+$650/mo**. Headroom: ~30 concurrent
runs (current peak ~5).

**Files:** new — `infra/{filestore,workers-mig,gateway-cloudrun}.tf`;
`lease/RunLeaseService` + `RunLease` entity + `LeaseAcquirer`;
`recovery/StartupRunResumerV2`; `orchestration/GracefulDrainService`;
Flyway `V_run_lease.sql` + `V_run_lease_fencing.sql`;
`config/DistributedModeConfig`. **Modified:** `RunOrchestrator`,
`WorkerAgent`, `ContextStore`, `SseBroadcaster` (Redis pub/sub backend).
**Est size:** ~3000 LOC new + ~500 modified + ~400 Terraform.

**Exit criteria:** workspace on Filestore; SIGTERM kills no run (zero
data loss over 100-run chaos test); multi-pod default, old single-VM
topology archived in Terraform; autoscaler responds inside 5-min window;
dashboards green.

---

## 5. VS Code extension

**Status:** **DEFERRED to external repo.** Captures scope + integration
contract so in-repo API stays aligned.

**Repo:** `executespec-vscode` (to be created under `navneetprabhakar`
org). Ships via VS Code Marketplace; not part of this monorepo.

**v1 feature set — three commands:**

| Command | Behavior |
|---|---|
| `ExecuteSpec: Submit spec` | Webview with spec input; targets current workspace folder's git repo; submits `POST /api/runs` with `source=vscode`, `repoUrl=<git remote>` |
| `ExecuteSpec: Show last run` | Webview read-only run detail; polls `GET /api/runs/{id}` + SSE for live updates |
| `ExecuteSpec: Open verified report` | Launches verified-report PDF in OS default PDF viewer via `child_process.exec(open <url>)` |

**Authentication:** OAuth device-code flow. Backend work (when extension
starts): new `POST /api/auth/device-code/start` returns short user code +
verification URL; user opens URL in browser, grants access; extension
polls `POST /api/auth/device-code/poll` until JWT issued. New
`DeviceCodeController` + `DeviceCodeService` + `oauth_device_code` table
(8-char user code, 15-min expiry, one-shot). Issued JWTs carry
`clientType=vscode` claim for audit.

**Why separate repo:** VS Code extensions ship via Marketplace not our
CI/CD (separate release cadence); TypeScript + `vscode` API has no shared
build infra with web app; issue tracking lives on the extension repo so
monorepo issues stay focused on platform.

**Anti-scope:** no local preview (runs on Cloud Run / preview-engine
infra; extension just links); no inline code suggestions (Cursor / Copilot
territory; ExecuteSpec is spec-first); no AI chat (web app only;
extension is run-submission + results-viewing).

**Non-goals:** SSO integration (v1 uses OAuth device-code — works for
MAX tier SSO users because underlying JWT flow is the same); multi-workspace
support (v1 = one workspace = one run at a time); status-bar run indicator
(Wave 5 polish — v1 ships without).

**Success criteria:** installable from Marketplace as "ExecuteSpec"; three
commands visible after install; OAuth device-code completes on first use
(cached token after); submit from git-initialized workspace lands a real
run on `app.executespec.ai`.

---

## 6. Project-surface Vite split (Phase 2 of v2.7 product split)

**Status:** PENDING. Multi-week effort, not yet scheduled.

**Companion to:** [`docs/cookie-domain-switch.md`](../cookie-domain-switch.md)
(operator runbook for the cookie scope flip).

**Why this exists:** v2.7 (`feature/v2.7-foundation`) shipped backend
plumbing for the two-product split — `product_id` discriminator (V143),
`ProductSurfaceFilter`, `OAuthSurfaceResolver`, `TierProductSurfaceMapper`,
CORS allow-list pre-extension, Stripe per-product price-ID env slots.
v2.7.1 + v2.7.2 then shipped the first user-visible Project IA win
(Repos promoted to top-level project tab).

**What v2.7 did NOT ship — what this plan adds:**
- A second Vite app under `frontend/build/` served at
  `build.executespec.ai`.
- DNS for `build.executespec.ai` + dev clone `build.dev.executespec.ai`.
- Cookie-domain switch to `.executespec.ai` (shared login across
  `app.executespec.ai` and `build.executespec.ai`).
- Backend repository reads filtered by `currentProduct`.
- Subscribe-time write of `org.product_id`.

**Phase 3 (consumer surface `app.executespec.ai` for FREE/PRO/MAX)
depends on Phase 2 landing first.**

### What gets built

**Frontend:** new `frontend/build/` standalone Vite app consuming
`api.executespec.ai`. Shared primitives + lib + stores via TS path
aliases (`@/lib/*`, `@/stores/*`, `@/components/shared/*`) — no design
system fork. Project-centric IA: Repos / Architecture / Standards / ADRs
/ Invariants / Contracts as top-level nav. Pulse / Runs stay. Drop
consumer affordances (sample-spec gallery, `/onboarding`, vibe view-mode
toggle, view-mode toggle in header, "Missions" rename — Project uses
"Runs" verbatim). Sidebar: Pulse / Repos / Runs / Code / Settings / Audit
/ Org admin only. Drop Pricing / Integrations marketing; promote
Standards / Contracts. Build output to `src/main/resources/static/build/`
(not `static/`) so existing single-app deploy keeps working unchanged
during cutover.

**Backend:** `WebMvcConfigurer` Host-aware static handler:
`build.*executespec.ai` → `static/build/index.html` for non-`/api/**`;
`app.*executespec.ai` (Phase 3) → `static/app/index.html`; default →
`static/index.html`. Implementation rewrites forwarded path before
resource resolution; must not change `/api/**`. `JwtUtil.issueRefreshTokenCookie`
accepts `auth.cookie.domain` knob (empty/null = single-host, `.executespec.ai`
= parent-domain). `BillingService.handleStripeCheckoutCompleted` + Razorpay
equivalent call `TierProductSurfaceMapper.defaultSurfaceFor(tier)` and
persist `org.product_id`. Repository read filtering by
`ProductSurfaceFilter.ATTR_CURRENT_PRODUCT` on hot paths
(`PipelineRunRepository.findByOrgId` + ~5 others) — ArchUnit pin that no
repo skips the filter.

**Build pipeline:** `deploy.yml` adds `npm --prefix frontend/build run
build` parallel to existing root frontend build; both outputs into JAR.
Separate `frontend/build/package.json` so consumer surface doesn't need
B2B-only deps and vice versa; shared deps stay in root.

**DNS + cert:** `build.executespec.ai` CNAME/A → same backend host as
`api.executespec.ai`. Same for `build.dev`. TLS: Cloudflare
`*.executespec.ai` covers both — no new request.

**Cookie maintenance window:** per
[`cookie-domain-switch.md`](../cookie-domain-switch.md) — 30-min window,
14-day notice, single env var flip (`AUTH_COOKIE_DOMAIN=.executespec.ai`),
force-logout every existing session, ~10-min documented rollback. Run
AFTER both surfaces deployed + verified.

### Phasing

Each step ships to dev first, soaks ≥48h, then prod.

1. **Backend Host-aware static handler** (~1 day) — flag
   `surface.host-routing.enabled` (default OFF). With ON, Host header
   drives `static/` directory. Smoke:
   `curl -H 'Host: build.dev.executespec.ai'` returns build app's
   index.html.
2. **Backend repo-scope filter wiring** (~2 days) — apply
   `ProductSurfaceFilter.ATTR_CURRENT_PRODUCT` on 5–6 hot-path queries;
   ArchUnit guard.
3. **`org.product_id` write at subscribe time** (~1 day) — `BillingService`
   calls `TierProductSurfaceMapper`; tests cover every tier × gateway
   permutation.
4. **`frontend/build/` skeleton** (~3–5 days) — standalone Vite project,
   shared primitives, project-centric sidebar, top-level Repos tab.
   Builds cleanly; doesn't serve outside build pipeline (flag stays OFF
   in prod).
5. **Cutover dry-run on dev** (~2 days) — flip static-handler flag ON for
   `dev.executespec.ai`; smoke-test `build.dev.executespec.ai`; verify
   cross-subdomain auth doesn't break (cookie still single-host).
6. **Cookie-domain switch on dev** (~1 day plus 14-day notice) — per
   `cookie-domain-switch.md`. Verify login at `dev.executespec.ai`
   recognized at `build.dev.executespec.ai`.
7. **Prod cutover** (~1 day) — repeat 5+6 on prod with 30-min maintenance
   window; send 14-day customer notice.
8. **Phase 3 follow-up** — stand up `app.executespec.ai` consumer surface;
   same template, different IA + copy; no new backend work.

**Total Phase 2 effort:** ~2–3 weeks focused work + the 14-day
customer-notice clock for the maintenance window.

**Not in this plan:** consumer Vibe surface = Phase 3; per-surface admin
tooling = separate ticket post-launch; SCIM across surfaces = M-037
backlog; per-surface marketing sites = Cloudflare Pages, decoupled.

**Risks + rollback:**

| Risk | Mitigation |
|---|---|
| Static-handler change breaks existing single-host serve | Behind feature flag; ArchUnit pin; dev-soak ≥48h before prod |
| Cookie maintenance window invalidates active sessions | 14-day customer notice + in-app banner; 10-min rollback documented |
| Path-alias drift between apps causes silent type errors | Shared `tsconfig.base.json` extended by both; CI `tsc --noEmit` on both pre-JAR |
| Second app pulls unexpected deps | vite-bundle-analyzer in CI; alert on bundle-size delta |

**Acceptance:** `curl -H 'Host: build.dev.executespec.ai'
https://dev.executespec.ai/` returns different index.html than default;
login at `dev.executespec.ai` recognized at `build.dev.executespec.ai`
without re-auth; all project tabs load on new surface; no `/api/**`
behaviour change; PostHog `product` property populated on every event
from new surface; ArchUnit guard fires; Stripe checkout completion
stamps `org.product_id` correctly per tier × gateway; rollback runbook
verified on dev within 10 min.

**Out of scope:** IA changes to existing single-app dashboard beyond v2.7.2;
visual redesign (keep existing tokens + primitives + 4-band palette);
marketing-site changes (its own deploy).

---

## 7. Readymade Solutions library

**Status:** **W1–W5 shipped on `feature/readymades-and-migrations`
2026-04-29.** Branch not yet merged to dev. End-to-end functional:
gallery + detail + slot form + variant resolution + instantiate via V2
pipeline.

**R-### consumed:** R-327 (W1) → R-331 (W5). R-332..R-335 reserved for
W6 catalog expansion / W7 community contribution / W8 tier-gating polish
/ W9 search+recommendations.

### Shipped commit trail

| Wave | R-### | Commit | What landed |
|---|---|---|---|
| W1 | R-327 | `44d8dff5` | V157 schema (5 tables) + entities + repos + DTOs |
| W2 | R-328 | `00e9f05f` | Catalog YAML format + 3 POC seeds (login-with-google / stripe-checkout / openai-chat) + `ReadymadeSeeder` (`@PostConstruct` + `@Transactional` `reseed()`) + `ReadymadeAdminController` |
| W3 | R-329 | `f81bbb24` | Library API + `StackNormalizer` + `VariantResolver` + `ReadymadeService` (list/detail/facets/tier-gating) + `ReadymadeController` read endpoints (instantiate stubbed 501) |
| W4 | R-330 | `cb32f08f` | Frontend `/v3/library` gallery + `/v3/library/:slug` detail + 8 library helpers in `lib/api.ts` + macro-group tabs + tier-stratified cost framing chip |
| W5 | R-331 | `a6c9f0a2` | V160 tracking columns on `execution_run` + `ReadymadeOrchestrator` (instantiate via existing `ExecutionOrchestrator`) + wired `POST /api/library/{slug}/instantiate` (was 501) — full end-to-end functional |

### One-line pitch

> *A searchable library of standard building blocks — sign-in, payments,
> notifications, data, monitoring, AI — that adapts to your repo's stack,
> ships as a PR you own. Free starters for everyone, premium catalog for
> paid tiers.*

### W6–W9 reserved

- **W6 — Catalog expansion (R-332)** — 130+ solutions × 3-5 variants
  each (1-2 quarters authoring at quality bar; accelerate via community
  submissions). Sub-batch: 5–10 multi-primitive stacks ("SaaS
  auth+billing starter", "Internal admin panel", "AI chat app starter",
  "Marketplace MVP"). Each stack = a `readymade_solution` row with
  `kind=STACK` whose `dependency` rows enumerate member primitives.
- **W7 — Community contribution path (R-333)** — `POST /api/library/contribute`
  (any tier YAML/JSON submit), `GET /api/library/contributions/me`,
  SUPER_ADMIN `POST /api/admin/library/contributions/{id}/{approve|reject}`.
  New `readymade_contribution` staging table; states NEW → REVIEW →
  APPROVED → LISTED with REJECTED + WITHDRAWN terminals. Author profile
  populates `author_user_id` / `author_org_id` on promoted rows.
  COMMUNITY-source solutions default to TIER=PRO until SUPER_ADMIN flips.
- **W8 — Tier-gating UI polish + lock-state UX (R-334)** — lock-icon +
  hover tooltip; unlock CTA → `/v3/pricing?from=library&solution=<slug>`
  deep-link; "Browse FREE solutions" filter; per-card files/credits chips
  even on locked cards.
- **W9 — Search + recommendations (R-335)** — FTS via `idx_readymade_fts`
  GIN index; "Trending" (instantiations per solution per 30-day rolling);
  "Recently used" (per-user/org history); "Recommended for me" (filter to
  variants matching user's most-recent project stack); empty-state
  escape hatch.

### Out of scope (v1)

| Deferred | Item |
|---|---|
| Separate product | Migrations / Refactors (see §8) |
| v1.5 / v2 | L2 architecture-awareness (rewrite generated code to match user's existing patterns — functional vs class components, naming, file structure). LLM-time adaptation pass; expensive to ship right. |
| v1.5 / v2 | Real-time collaborative library editing for community submissions |
| v1.5 / v2 | Plan-preview before submit (planner outline + cost on detail page pre-credit) |
| v2 | Cross-org sharing of community submissions |
| v2 | Custom variant authoring inside the UI |
| v2 | Versioning + rollback of solution definitions |
| Roadmap | Public marketplace + revenue share |
| Roadmap | Multi-language UI (content multi-lang via variants; UI English-only v1) |

**Effort:** 9 waves; plumbing (W1-W5, W7-W9) ~8-10 commits / 4-6 weeks
focused; catalog seed 1-2 quarters authoring (accelerate via community
submissions early). Total L — bigger than agent-builder, more like
v2.6.3 enterprise readiness in scale.

---

## 8. Migrations product

**Status:** **W1–W7 shipped on `feature/readymades-and-migrations`
2026-04-29.** Branch tip `05d92db6`; not yet merged to dev. W8 / W9 /
W10 deliberately deferred.

**R-### consumed:** R-336 (W1) → R-342 (W7). R-343 (W8) / R-344 (W9) /
R-345 (W10) reserved.

### Shipped commit trail

| Wave | R-### | Commit | What landed |
|---|---|---|---|
| W1 | R-336 | `c3c6eaaa` | V158 schema (5 tables) + entities + repos + DTOs |
| W2 | R-337 | `c3c6eaaa`+ | Catalog YAML format + 3 POC recipes (js-to-typescript / express-to-fastify / class-components-to-hooks) + `MigrationRecipeSeeder` (`@PostConstruct` + `@Transactional` `reseed()`) + `MigrationAdminController` |
| W3 | R-338 | `f81bbb24` | Library API (`GET /api/migrations`, `GET /api/migrations/{slug}`, `POST /dry-run`) + `MigrationStackMatcher` + `MigrationDryRunService` (Phase 1 halt-and-inspect, no LLM in v1) |
| W4 | R-339 | `416cec06` | V159 tracking columns on `execution_run` + `MigrationOrchestrator` (apply, multi-repo fan-out via Execution View) + cross-product `composer/SlotValidator` + `composer/SpecComposer` + wired `POST /api/migrations/{slug}/apply` |
| W5 | R-340 | `e21c0145` | Frontend `/v3/migrations` gallery + `/v3/migrations/:slug` halt-and-inspect detail + 6 migration helpers in `lib/api.ts` |
| W6 | R-341 | `3c78245b` | `TierEnforcementService.requiresCostCap(tier, hasByok)` + frontend `requiresCostCap(user)` mirror + tier-stratified cost framing on dry-run summary |
| W7 (partial) | R-342 | `05d92db6` | 5 more recipe seeds: mongoose-to-prisma (3 variants), rest-to-trpc, pages-router-to-app-router, mocha-to-vitest, webpack-to-vite — v1 catalog now 8 of 11 |

### One-line pitch

> *Pick a migration recipe, see what it'll do across your repos before
> you commit, click apply, get PRs. Same recipe runs on every linked
> repo in your project — Express → Fastify, JS → TS, Tailwind v3 → v4.
> We show you file count, sample diff, and cost first.*

### What this is

A **separate product surface from Readymade Solutions**, focused on
**transforming existing code** (not adding new code). Each migration is
a parametric recipe declaring source-stack + target-stack + options;
reads codemap + detected stack to confirm source matches before any LLM
call; can run across every linked repo in one launch via multi-repo
Execution View; goes through two-phase halt-and-inspect gate (Phase 1
enumerates files + estimates cost + sample diff; Phase 2 applies after
user confirms). Output is PRs, one per affected repo.

### W8–W10 reserved (v1.5+)

- **W8 — Hybrid AST + AI fallback (R-343)** — ast-grep integration:
  spawn ast-grep via existing `ShellTool` sandbox (or include Rust
  binary in runtime image). Per-recipe `deterministic_recipe_ref` column
  populated with `ast-grep:./recipes/<slug>.yml`. New
  `DeterministicPassRunner`: run ast-grep first → capture residual files;
  hand off residuals only to V2 worker with recipe's AI fallback prompt
  + tight per-file token cap. Cost-tracker: deterministic-pass files =
  $0; only LLM hits the meter. Verification: build + tests pass +
  `preservation_contract` satisfied (declared by recipe; checked
  deterministically — e.g. for Express→Fastify, "every route in source
  is still served"). Schema field `migration_recipe.deterministic_recipe_ref`
  already in V158 ready to populate; ast-grep / OpenRewrite integration
  is real per-language lift — pick this up when v1 hits enough usage to
  justify the predictability+cost win.
- **W9 — Continuous via PostMergeCascade (R-344)** — new event type
  `MigrationOpportunityDetected` in `harness/event/`; recipe-matcher:
  when a Renovate-style PR merges, parse dep changes
  (`package.json` / `pom.xml` / `requirements.txt` diffs) + find recipes
  whose source/target stack matches bumped version range. Notification:
  in-app + email with one-click "Apply this migration" CTA →
  `/v3/migrations/:slug` Phase-1 page. Dedup: max 1 suggestion per
  recipe per project per 30 days; max 3 suggestions per project per
  week. Settings: org-admin can disable per project. Lands once catalog
  ≥ 30 recipes covering real Renovate bumps + careful notification-rate
  design.
- **W10 — Polyglot expansion (R-345+)** — Python (Django 4→5,
  Flask→FastAPI, PyMongo→SQLAlchemy); Java (Spring Boot 2→3, JUnit 4→5,
  Java 17/21 — probably wraps OpenRewrite recipes since JVM coverage is
  mature); Go deferred (fewer migration patterns).

### Tier-stratified cost model (cross-product)

Applies to BOTH this product AND Readymade Solutions:

- **FREE / PRO — managed-key, credit-charged:** ExecuteSpec pays the LLM
  provider; user pays per-credit. **Cost caps mandatory** — protect us
  from runaway recipes blowing user's wallet (and ours). UI shows
  estimated cost on every recipe card; Phase 1 plan-gate shows hard cap
  input (default 5× typical × selected_repos).
- **TEAM / ENTERPRISE — BYOK, fixed-subscription:** user brings own LLM
  keys; pays fixed monthly. No cost cap needed from us.

### Out of scope (v1)

| Deferred | Item |
|---|---|
| W8 (v1.5) | Hybrid AST + AI fallback per-language |
| W9 (v1.5) | Continuous suggestions via PostMergeCascade |
| W10 (v1.5+) | Polyglot recipes (Python, Java, Go) |
| v2 | Custom recipe authoring inside UI (community + first-party) |
| v2 | Migration recipe versioning + rollback |
| v2 | Cross-recipe orchestration (sequence of migrations as one launch) |
| v2 | "Dry-run" that re-runs full recipe LLM-side without applying (today's dry-run is sample-only) |
| Roadmap | Migration marketplace with revenue share |
| Roadmap | Import from OpenRewrite / Codemod.com registries |
| Catalog follow-up | 3 remaining v1 recipes (CommonJS → ESM, Tailwind v3 → v4, Vue 2 → Vue 3) — none block v1 launch |

**Effort:** 10 waves; plumbing (W1-W6, W8, W9) ~10-12 commits / 6-8 weeks
focused; ast-grep / jscodeshift recipe authoring once W8 lands.
