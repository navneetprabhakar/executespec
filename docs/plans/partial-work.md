# Partial / Follow-up Work

> Consolidated 2026-04-25 from `android-codegen-plan.md`, `figma-integration-v2-plan.md`,
> `harness-v2-remaining-work.md`, and `remove-partial-status.md`. These plans had partial
> implementations or scoped follow-ups still pending. Sourced verbatim from the originals
> with only the cross-references updated.

---

## 1. Mobile Code Generation — Android (RN+Expo follow-ups)

> Original: `android-codegen-plan.md` (rev 2, 2026-04-18). React Native + Expo path
> already shipped in v2.5.13 (PR #46). This section captures what is **not yet done**.

### What shipped (v2.5.13)
- `projectType=react-native-expo` + `runtimeSurface=MOBILE_APP` enums.
- `prompts/scaffolds/react-native-expo.md` scaffold + `BuildCommandResolver` rule (`expo export --platform <p>` + `tsc --noEmit`).
- `ShellSandboxPolicy` `expo` / `eas` / `npx` verb families.
- `DevDockerfileGenerator.reactNativeExpoDockerfile()` (2-stage build → nginx-served `dist`).
- Frontend `MobilePreviewPanel.tsx` with QR pairing (Expo Go) replacing the iframe.
- Feature flag `codegen.react_native.enabled` seeded via V118.
- Verified live with Android (Calorie Tracker, runId `521236da`) + iOS (Habit Tracker, runId `49f8a507`).

### What's pending
1. **Deploy story (EAS Build → GCS)** — `DeploymentService` REACT_NATIVE branch is not yet implemented. Plan: invoke `eas build --platform <t> --profile production --non-interactive --no-wait` with org-scoped EAS access token (`org.eas_token` — needs new migration); poll EAS REST for completion; download signed APK/AAB/IPA; re-upload to GCS at `gs://executespec-mobile-builds/<orgId>/<runId>/<platform>.<ext>`; return 24h-signed download URL.
2. **iOS / Android signing custody** — out of scope for v1; users upload `.p12` + provisioning profile + Android keystore to EAS via their own dashboard. Documented under user manual when shipped.
3. **`expo-doctor` static-analysis gate** — test planner does not yet run `expo-doctor` as part of the test surface.
4. **Native Android (Kotlin + Compose) path** — explicitly out of scope for v1. Roadmap: if a project outgrows managed Expo, eject to bare RN via `npx expo prebuild`.
5. **In-browser preview via `react-native-web`** — v2 idea; defer until v1 stabilises across the matrix.
6. **`eas submit` (Play Store / App Store direct upload)** — needs per-org service-account key management + track/rollout UX; on roadmap.
7. **Connected device tests (Detox / Maestro)** — needs emulator or device-farm story; v2.

### Open questions still unresolved
- **EAS billing model** — Expo's hosted build minutes cost ~$0.04/min. Options: (a) pass through, (b) absorb in PRO/MAX with quota, (c) BYO-EAS-token. Stopgap is (c); revisit once eval data is in.
- **Bundle-size budget** — warn in `ValidationReportPanel` if Metro bundle > 8 MB gzipped.
- **iOS runtime-surface gate** — require Apple Developer account before accepting `target: BOTH` in pre-plan classifier.

### Pending metrics
- `executespec.codegen.rn.eas.minutes_used{platform,profile}`
- `executespec.codegen.rn.bundle.size_bytes{platform}`
- `executespec.codegen.rn.expo_doctor.findings{severity}`

---

## 2. Figma Integration — V2

> Original: `figma-integration-v2-plan.md` (draft 2026-04-13). Targets V2 harness +
> existing Slack/JIRA/Confluence integration patterns. Tier-gated to ENTERPRISE / MAX.

### What shipped
- `RunRequest.figmaUrl` (single URL) and `figma_url` column on `pipeline_run` — basic ingestion only.
- Optional Figma URL drives the design-compliance dimension on `ReviewScoringService` (heuristic; no actual Figma fetch).

### What's pending (entire Figma integration v2)
- **OAuth + credential storage** — `FigmaOAuthService` + `FigmaOAuthController` + `figma_credential` table (planned V78 — number now obsolete; renumber when scheduling).
- **Tier + enablement gate** — `FigmaIntegrationGate.enforce(orgId, runRequest)` called from both run submission and `ToolRegistryV2.bind(ctx)`.
- **Extraction service** — `FigmaExtractionService` facade with MCP-primary + REST-fallback, Resilience4j circuit breaker (5 fail / 60s window), Caffeine cache (30 min TTL keyed by `(orgId, fileKey, nodeId, format)`).
- **Clients** — `FigmaMcpClient` (lazy `McpSyncClient` per org, cached) + `FigmaRestClient` (Spring WebClient, exponential 429 backoff, 10MB JSON truncation).
- **V2 tool** — `FigmaDesignContextTool` bound conditionally into `ToolRegistryV2`; JSON schema with `figmaUrl` / `outputFormat` / `includeScreenshot`. Cost attributed via `CostHook` to `V2RunCostTracker` under phase `INTEGRATION`.
- **Planner / worker prompt augmentation** — section injected into `prompts/v2/planner.md` + `worker.md` only when `figma_urls` are present.
- **Validation report** — `designComplianceJson` field with color-match %, typography-match %, layout-match %.
- **Schema** — V78 `figma_credential` + V79 `pipeline_run.figma_urls TEXT[]` + `figma_context_path TEXT`. Renumber to current V-sequence when scheduling.
- **Feature flags** — `integration.figma.{enabled, mcp_enabled, rest_enabled, max_screenshots_per_run, max_calls_per_run}`.
- **Frontend** — Settings → Integrations card (connect/disconnect Figma) + `NewRunModal` Figma URL field + `ValidationReportPanel` Design Compliance row.

### Open question still blocking
**MCP server-to-server auth** — Spring AI 1.1.4's `HttpClientSseClientTransport` takes a fixed URL at construction; per-user bearer-token injection isn't obviously supported. Three options (decide after P0 probe):
- (a) per-user singleton MCP client with Caffeine TTL — simple, loses cross-org cache
- (b) per-request client construction — higher cost, cleanest isolation
- (c) custom transport accepting `HttpHeaders` from `RequestAttributes` — most work, best fit

### Phased plan (when scheduled)
1. **P0** — manual probe of MCP server-to-server auth (1 day, throwaway branch).
2. **P1** — OAuth + credential table + `FigmaIntegrationGate` (1 PR, low risk).
3. **P2** — REST extraction + V2 tool + tests (1 PR, medium risk).
4. **P3** — MCP primary path + circuit breaker (1 PR, medium risk).
5. **P4** — Planner + worker prompt integration (1 PR, medium risk).
6. **P5** — UI + docs (1 PR, low risk).
7. **P6** — Asset export pipeline (deferred; SVG export via `/v1/images`).

### Estimated size
~22 new files + 10 modified, ~2400 LOC net.

---

## 3. Harness V2 — Remaining Work

> Original: `harness-v2-remaining-work.md`. V2 is the only harness — V1 deleted in
> deprecation P3 (2026-04-13).

### Status snapshot

| Area | Status |
|---|---|
| Tool layer (10 tools + completeTask) | done |
| LlmRouterV2 (native tool calling, 3 providers) | done |
| WorkerAgentV2 (sequential, native completeTask) | done |
| PlannerAgentV2 (restricted-tool plan, runtimeSurface, hardConstraints) | done |
| SequentialExecutorV2 (per-task tier routing, smoke test, retry) | done |
| ReviewerAgentV3 (general + wiring passes) | done |
| ContextStoreV2 (Redis persistence, per-task) | done |
| V2Metrics observability | done |
| V2RunOrchestrator + V1-table persistence (UI parity) | done |
| V2RunCostTracker + per-phase attribution + run-stats fix | done |
| StartupRunResumerV2 fail-cleanly + resume_attempts cap + 6h stale cutoff | done (Phase 1.5) |
| V2 cancel via `DELETE /api/runs/{id}` | done |
| ScmPushService extracted from V1 harness | done |
| V1 harness deletion (deprecation P1–P6) | done 2026-04-13 |

### Gap 1 — Phase 6: batch execution in V2
**Status:** not implemented. V2 runs are realtime-only.
**Plan:** see `pending-v2-batch-execution-plan.md`.
**Sequencing:** unblocked — V1 `batch/*` deleted in deprecation P3.

**Acceptance:**
- Batch-mode approval on a V2 run runs worker-tasks through the 3 provider batch APIs.
- Reviewer / test-gen / preview stay realtime.
- Eval matrix matches realtime accuracy within 5%, at ~50% of realtime credit cost.

### Gap 2 — Startup resume (full replay) — pending
**Status:** Phase 1.5 shipped (fail-cleanly + resume_attempts cap of 3 + 6h stale cutoff). Full conversation replay still pending.

**Phase 2 work:** for `WORKING` / `REVIEWING` runs with a fresh Redis snapshot, load `v2:agent-ctx:{runId}:{taskId}` and resume `WorkerAgentV2` at the saved iteration instead of failing.

**Acceptance:** kill the JVM mid-run, restart, run reaches a terminal state without user intervention. Integration test that simulates JVM stop.

**Estimated size:** ~400 LOC.

### Gap 3 — Endpoints returning 410 Gone in V2

| Endpoint | Status | Unblock by |
|---|---|---|
| `POST /runs/{id}/retry` (full-run) | 410 | Gap 2 Phase 2 (full replay) |
| `POST /runs/{id}/pause` + `resume` | 410 | Gap 2 Phase 2 |
| `POST /runs/{id}/rework` | 410 | V2 plan-iteration flow (not yet specced) |
| `POST /runs/{id}/reopen-merge` | 410 | Permanent — V2 auto-merges |
| `POST /runs/{id}/tasks/{taskId}/retry` | wired baseline | Full replay arrives with Gap 2 Phase 2 |
| `POST /runs/{id}/implement-suggestions` | 410 | V2 chat/iterate flow (separate spec) |
| `POST /runs/{id}/retry-validation` | wired | — |
| `POST /runs/{id}/edit-plan` | 410 | Needs planner re-entry — defer until after batch |
| `POST /runs/{id}/clarify` + `chat` + `apply` + `iterate` | 410 | V2 ConversationAgent integration (scope-TBD) |
| `POST /runs/{id}/run-tests` + `apply-test-fixes` | 410 for V2 | Wire once V2 test-gen is fully plumbed through `TestGenOrchestrator` |
| `POST /runs/{id}/merge` | 410 — permanent | V2 auto-merges; remove from frontend |
| `POST /api/feasibility` | 410 — permanent | Use plan-approval estimate instead |

**Recommended order:**
1. Gap 2 Phase 2 unblocks `retry` / `pause` / `resume`.
2. `merge` / `reopen-merge` / `feasibility` / `clarify` — remove from frontend; permanent 410.
3. Chat / iterate / edit-plan / rework / implement-suggestions — separate design doc; out of scope for V2 parity.

### Architectural principles V2 commits to
- **Sequential over parallel.** One task, one worker, one loop.
- **Native tool calling over XML.** Strict JSON schemas; PATH B (`<tool_call>…`) does not exist in V2.
- **Ground truth during execution.** Every task ends with smoke test (`buildProject`); reviewer re-runs build after fixes.
- **Upfront context, not search-first.** Workspace tree + planner-declared dependency files in worker's initial system prompt.
- **Provider-agnostic at agent layer.** Provider quirks (OpenAI `extra_body`, Gemini thought-signatures, Anthropic cache blocks) live in V2 native tool clients.
- **Redis-persisted context.** Every 5 iterations best-effort. Replay possible via `ContextStoreV2.load()`.

### Out of scope (will not change)
- No re-introduction of V1 "progressive merge". Auto-merge is structural.
- No parallel task execution in the sequential path. If ever needed → new `ParallelExecutorV2`.
- No XML tool calls. Provider regressions get a client wrapper.

---

## 4. Removing PARTIAL as a Run Status

> Original: `remove-partial-status.md` (draft 2026-04-19). Proposal only;
> waiting on user review before scheduling.

### The problem
`PARTIAL` has become a dumping ground covering: reviewer timeouts, UNRESOLVED issues, smoke-test failures, iteration-cap hits with usable code, and legacy pre-v2.5.10 runs. The headline badge washes out the nuance carried in `status_reason`. Users treat `PARTIAL ≡ broken` and re-run, wasting credits.

### Proposal
Replace `PARTIAL` with one of three outcomes from a pair of independent signals:

| Signal | Values | Source |
|---|---|---|
| `codeBuildPassed` | YES / NO / SKIPPED | Reviewer's final `buildProjectTool` exit; YES iff exit=0 |
| `reviewerOutcome` | CLEAN / FIXED / UNRESOLVED / INCOMPLETE | Reviewer pass outcomes + summary |

| codeBuildPassed | reviewerOutcome | New status |
|---|---|---|
| YES | CLEAN \| FIXED | **`COMPLETE`** (green) |
| YES | UNRESOLVED \| INCOMPLETE | **`COMPLETE_WITH_CONCERNS`** (amber) |
| NO | any | **`FAILED`** (red) |
| SKIPPED | any | **`FAILED`** (red) |

Three terminals instead of four. `COMPLETE_WITH_CONCERNS` is the new amber.

### Data migration (V119 — renumber to current V-sequence when scheduled)
1. For every `pipeline_run` with `status='PARTIAL'`:
   - `validation_report.final_build_passed=true` → `COMPLETE_WITH_CONCERNS`
   - else → `FAILED`
2. DB constraint restricts `status` to `{QUEUED, PLANNING, AWAITING_APPROVAL, WORKING, REVIEWING, COMPLETE, COMPLETE_WITH_CONCERNS, FAILED, CANCELLED}` (9 values).
3. `RunStatus` enum mirrors the constraint; `PARTIAL` stays as a `@Deprecated` synonym → `COMPLETE_WITH_CONCERNS` for one major version, then deleted.

### Backend changes
- `V2RunOrchestrator.resolveTerminalStatus(exec, review)` — rewrite decision tree to the buildPassed × reviewerClean matrix.
- `status_reason` stays — drives tooltip copy. `REVIEWER_UNRESOLVED` migrates to surfacing on `COMPLETE_WITH_CONCERNS` instead of `PARTIAL`.
- Remove `"PARTIAL"` branch from `mapOverallToV1Status` / `resolveTerminalStatus` fallbacks.

### UI changes
- `StatusBadge` — drop `PARTIAL`, add `COMPLETE_WITH_CONCERNS`. Existing tooltip mechanism (v2.5.10) supports it.
- Run list colour coding — COMPLETE=green, COMPLETE_WITH_CONCERNS=amber, FAILED=red.

### Credit / billing semantics
- `COMPLETE` → no refund.
- `COMPLETE_WITH_CONCERNS` → no refund (code builds; user got value).
- `FAILED` → refund (same as today).

This is clearer than today (PARTIAL doesn't refund even when output is unusable).

### Rollout
Land backend + DB behind feature flag `status.tri-state.enabled` (default off). Ship UI with `COMPLETE_WITH_CONCERNS` support but render `PARTIAL` until flag flips. 2-week staging window → flip for everyone → drop flag + deprecated enum at next major bump.

### Estimated effort
- Backend + migration: ~1 day.
- Frontend (badge + list colours): ~0.5 day.
- QA across PARTIAL archive: ~0.5 day.
- Flag-rollout window: 2 weeks wall time.
