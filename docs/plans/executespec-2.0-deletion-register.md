# ExecuteSpec 2.0 deletion register

This is an execution register. `COMPATIBILITY_ONLY` entries need an owner, removal gate and deadline before release; they are not extension points.

## KEEP

| Surface | Item | Constraint |
|---|---|---|
| Backend | BUILD/PATCH concepts, independent reviewer, verification ledger, run-step usage and cost evidence | Recompose behind smaller stage boundaries |
| Protocol | Agent-session entities, durable turns, proposals/confirmation, action/event audit and attachments | Upgrade to semantic v2 contract |
| Search | `rg` fast path, path safety, tool-output spill files | Replace flat result schema |
| Client core | Typed HTTP API, scoped SSE replay/reconnect, local-hands boundary, permission/path safety | UI must consume semantic adapter, not transport enums |
| CLI | OpenTUI runtime, one plain/accessibility renderer, headless JSON/NDJSON, exit codes, diff/revert/checkpoints | One application state model |
| Web | Console authentication/shell foundation and agent-session stream | Collapse routes around work, evidence and decisions |
| Benchmarks | Blind scorer boundary, manifests/tracking store | Tracking remains distinct from official scoring |
| Docs | Feature capability docs, benchmark truth separation, historical reason logs | Generated navigation/facts and integrity checks |

## REPLACE

| Current | Replacement | Removal gate |
|---|---|---|
| Regex natural-language pre-router | Structured cheap-model decision with deterministic command/safety overrides | Routing corpus and confirmation tests |
| Blocking `GateStore.awaitPlanGate` | Persisted suspended state resumed by event/job | Load/restart/timeout tests |
| Local disconnect as internal error | `AWAITING_CLIENT` with resumable pending calls | Reconnect and duplicate-result tests |
| Flat search strings and two JS walkers | Typed git/rg/symbol/test index with fingerprint/freshness/citations | Recall/latency and stale-index tests |
| Raw/full worker history and client-owned canonical chat JSON | Server ContextPlanner, journal, compaction, headroom and local cache | Long-session recovery tests |
| API-call-success performance tracker and fragmented routing | Task-outcome store plus one auditable RoutingService | Replay/degradation/provider-cap tests |
| Historical fake `spawn_subagent` stub | Persisted child run protocol and bounded orchestration | Historical stub is superseded; current model-visible tools delegate through `SubagentExecutionService` and remain until a separately approved API migration |

The coordinator-triggered path is now an explicit, bounded control-plane seam rather than a model
claim: the feature flag remains fail-closed, eligibility is advisory, and reservation plus the
accepted audit event are atomic. No unbounded client-side scheduler or implicit run authority is
being retained under compatibility aliases.
| Raw transport events in clients | Agent Protocol v2 semantic transcript state | Cross-client contract suite |
| Route-first OpenTUI | Transcript-first workspace with overlays | Vertical slice and viewport QA |
| Web route collection and polling run card | Shared semantic transcript/control state and resumable events | Browser workflow and reconnect QA |
| Metadata-only image notices | Typed bounded attachment upload, preview/removal and capability routing | Privacy/size/MIME/provider tests |
| Hand-maintained doc map/release facts | Generated manifests and link/version validators | Docs CI green |

## DELETE

| Item | Timing/evidence |
|---|---|
| Sarvam enum, config, router dispatch, catalog/picker/style/docs/tests and stale persisted selections | Milestone 1; forward migration and provider-contract suite |
| Literal NUL bytes in `WorkerAgent.java` and `GeminiNativeToolClient.java` | DONE; escaped delimiters preserve behavior and repository source-text gate prevents recurrence |
| Historical backend/CLI fake `spawn_subagent` aliases and reconnaissance stub | Superseded. Current backend `spawn_subagent`/`spawn_subagents` callbacks call persisted `SubagentExecutionService` with eligibility, bounded reservations, child sessions, and audit events. Retain the current contract; delete only after an explicit replacement/API migration. |
| Deprecated `PatchAgent` wrapper | DONE — deleted after `CodeGenEngine`/PATCH characterization; `prompts/patch-agent.md` remains because `CodeGenEngineImpl` owns and actively assembles it |
| `FallbackLlmRouter` compatibility router | DONE — deleted after all production text callers moved to `LlmRouter.callText`; canonical routing, retry, failover and usage accounting now have one transport |
| PATCH/conversation prompt fallback stubs | DONE — required prompt resources now fail closed instead of silently substituting generic prompts; provider overlays use `PromptAssembler` |
| Inert V2 batch scaffold and dead batch-cost configuration | RETIRED — new BATCH approvals return explicit HTTP 410; provider clients, scheduler, coordinator/resume beans, router, and batch-only config are removed. V61/V117 historical columns/flag and runtime read fields remain for compatibility. |
| No-op agent-session summary-email route | RETIRED — compatibility route remains for source compatibility but returns HTTP 410; current CLI/VS Code sessions do not call it and usage remains available through agent-session turn/event APIs |
| CLI duplicate slash alias normalization | PARTIAL — shared typed resolver now feeds REPL and native OpenTUI; headless dispatch and the remaining route shell still need migration |
| Dead Console CSS selectors `.con-side-mini` / `.con-nav-badge` | DONE — removed after zero non-CSS source references; unrelated legacy Signal/IDE CSS remains pending visual-contract review |
| Stale Web accessibility guard for model overrides | DONE — guard now checks the actual `ModelPicker` `ariaLabel` prop contract; rendered accessibility remains covered by the component tests |
| Dead ApplyService workspace git helpers | DONE — removed unused `tryGitCommit` / `runCmd` helpers after confirming all apply flows use `ScmPushService.pushApplyChanges`; no API or runtime contract changed |
| Legacy 14-argument `RunOrchestrator.submitRun` overload | DONE — `IntegrationManager` now supplies the canonical selected-repository argument explicitly; removed after compile-verified call-graph migration |
| Duplicate standalone billing API client | DONE — moved `BillingPlan`/`fetchPlans` into canonical `frontend/src/lib/api/billing.ts` and deleted the unused `billing-api.ts` module; no imports remain |
| Duplicate admin execution-reconcile fetches | DONE — both settings surfaces now use the typed `adminReconcileExecutions` domain helper and shared transport/auth behavior |
| Raw billing payment-history fetch in `IdeBilling` | DONE — migrated to typed `getPaymentHistory` in the canonical billing API module, preserving best-effort empty-state behavior |
| Raw project-settings PATCH in `IdeProjectSettings` | DONE — migrated to typed `updateProjectSettings` with shared auth/refresh transport and full payload coverage |
| Raw GDPR export request in `IdeMore` | DONE — migrated to typed `downloadUserDataExport` while preserving blob-download behavior; DSAR/erasure and multipart paths remain contract-specific |
| Billing DTOs embedded in `frontend/src/lib/api/core.ts` | DONE — moved cohesive pricing/usage/payment types to `billing.ts`; type re-exports preserve the stable `core` import surface |
| Post-review quality policy embedded in `RunOrchestrator` | DONE — extracted pure reviewer/validation quality decisions into `PostReviewQualityPolicy`; characterization tests pin the boundary |
| Notification DTOs embedded in `frontend/src/lib/api/core.ts` | DONE — moved `NotificationItem` and `NotificationPrefRow` to `notifications.ts`; the API barrel preserves consumer compatibility |
| Unassociated public auth form labels | DONE — login, signup, forgot-password, and reset-password labels now target stable input ids; recovery tests pin the association |
| Stale PATCH prompt runtime-coverage token | DONE — manifest now matches the actual `PromptAssembler` relative fragment path and the validator passes against the packaged graph |
| Agent shell errors without recovery action | DONE — the Web agent error card now offers an explicit retry path, with loading-state feedback, instead of forcing a page reload |
| Silent Web sandbox-session creation failures | DONE — New sandbox actions now route rejected session creation through the visible agent error surface |
| Console decisions outage shown as a healthy empty queue | DONE — decisions now render a retryable alert and suppress the empty state until data reload succeeds |
| Silent Web artifact clipboard/preview failures | DONE — copy and preview actions now expose actionable alerts when browser permissions or popup policy reject them |
| CLI Ink implementation and Ink/React dependencies | DONE — CLI 1.0 has no Ink/React runtime dependency; native OpenTUI is the rich renderer and readline is the plain/accessibility path |
| CLI split-footer implementation | DONE — the former split-footer renderer is absent; footer/status composition is owned by the OpenTUI app frame |
| `app-tui` route shell, persistent Inspector, Launcher routes and route commands | RESOLVED — OpenTUI now has a transcript-first session with semantic drawers; `/route` aliases remain parser-only compatibility and are not in the native palette |
| `opentuiPreview.ts` and automatic separate-terminal launcher behavior | PARTIAL — preview is a deterministic QA utility and `executespec app` is an explicit opt-in launcher; neither is the default chat path. Remove only after a separately approved launcher deprecation decision |
| Model-visible snake/camel tool aliases and obsolete stubs | After protocol tool-name negotiation |
| Implicit natural-language fast edit | Replace with explicit action or command |
| Metadata-only image semantics and tests | When real attachment contract lands |
| Duplicate chat execution behavior and retired Web routes/styles | After agent-session compatibility window |
| Orphan/rollback prompt archives | After generated caller-variable-tool manifest |
| Rejected/expired quality flags | Continuously after controlled graduation |
| Docs `.DS_Store`, stale generated CODEMAP, invalid active benchmark/competitive navigation | Docs cleanup after manifest validators |

## COMPATIBILITY_ONLY

| Item | Allowed behavior | Removal gate |
|---|---|---|
| `/api/orchestrator/turn`, `/api/spec/chat` | Old-client translation only; no new semantics | Supported-client adoption threshold before 2.0 final |
| Legacy project/run/spec chat reads/history | Read/import continuity | Server canonical turn migration |
| AgentIntentLegacyAdapter, recorded-turn POST, no-op summary email | Explicit deprecation responses and metrics | Protocol v2 rollout |
| Legacy string run-event payload and old envelopes | Translate to semantic events | Web/CLI/VS Code contract adoption |
| Client-owned conversation payload/local JSON | One-time import/cache only | Durable-turn sync verified |
| Old renderer flags | Development rollback only | Must not ship as supported CLI 1.0 modes |

## Decomposition targets, not automatic deletions

- Backend: `SequentialExecutor`, `RunOrchestrator`, `WorkerAgent`, `ValidationReportService`, `StateStore`, `RunController`, `CompatibilityTools`.
- Web: `IdeRunResult`, `lib/api/core`, `IdeOrg`, `IdeProjectMore`, `IdeSurfaces`, `IdeProjectTabs`, `IdeSettings`, `agentPanels`.
- CLI: `opentuiApp`, `app-tui/*`, `tui-terminal/*`, `tui/*`, `repl`.

These files are split only along tested semantic boundaries. File size alone is not a reason to invent more abstractions.
