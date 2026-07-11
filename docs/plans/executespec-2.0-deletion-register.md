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
| Fake `spawn_subagent` | Persisted child run protocol and bounded orchestration | Scout/verifier integration tests |

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
| Backend/CLI fake `spawn_subagent` aliases and reconnaissance stub | Backend aliases/stub deleted in M2 cleanup; CLI fake aliases already deleted. Persisted child protocol remains the replacement before any delegation UX ships. |
| Deprecated `PatchAgent` wrapper | DONE — deleted after `CodeGenEngine`/PATCH characterization; `prompts/patch-agent.md` remains because `CodeGenEngineImpl` owns and actively assembles it |
| `FallbackLlmRouter` compatibility router | DONE — deleted after all production text callers moved to `LlmRouter.callText`; canonical routing, retry, failover and usage accounting now have one transport |
| CLI Ink implementation and Ink/React dependencies | After OpenTUI transcript vertical slice |
| CLI split-footer implementation | After OpenTUI transcript vertical slice |
| `app-tui` route shell, persistent Inspector, Launcher routes and route commands | Before CLI 1.0 visual gate |
| `opentuiPreview.ts` and automatic separate-terminal launcher behavior | Before CLI 1.0 |
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
