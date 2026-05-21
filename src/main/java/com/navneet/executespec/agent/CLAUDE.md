# `agent/` — LLM agents + routing

> Last reviewed: 2026-05-12 (1.0.0-M1).

All LLM-calling agents + the routing infrastructure for the V2 pipeline.

## Key files

| File | Purpose |
|------|---------|
| `LlmRouter.java` | Canonical native-tool-calling dispatcher. Routes per provider (Anthropic via Spring AI, OpenAI via `OpenAiNativeToolClient`, Gemini via `GeminiNativeToolClient`). Every call flows through `RunCostTracker` (cache-aware pricing from `application.yml model-matrix.model-pricing`). |
| `FallbackLlmRouter.java` | Legacy V1 router; still used by non-tool-calling agents (`AccuracyAssessmentService`, `ConversationAgent`, `testgen/*`). Multi-provider fallback via YAML `failover-order`. Emits `RUN_PROVIDER_SWITCHED` SSE on fallback. `FallbackMode.NO_FALLBACK` opts out (used when `-Dexecutespec.strict-provider=true`). |
| `OpenAiNativeToolClient.java` | OpenAI native-tool client — bypasses Spring AI 1.1.4's `extra_body` bug; auto-cache validated at 43%. |
| `GeminiNativeToolClient.java` | Gemini native-tool client — schema sanitization + thought-signature round-trip; implicit-cache prefix preserved. Records `cache_read_input_tokens` on every call. |
| `SmartClassifierAgent.java` | (LIGHTWEIGHT) Picks `PlanStrategy ∈ {SINGLE_SHOT, SMALL, MEDIUM, LARGE, LEGACY}`. Heuristic fast path (≤150-char tiny specs, no multi-feature keywords) returns at 0ms / $0; LLM verifier hits Haiku/Flash with the heuristic verdict as a hint. Redis cache 1h TTL keyed by `SHA-256(spec + sortedRepos + projectId)`. Always falls back to heuristic on parse / enum / timeout error. |
| `OutlinePlannerAgent.java` | (PLANNING) Two modes — `FULL_PLAN` (32k cap, single `submitPlan` tool call) for SMALL/MEDIUM; `SKELETON_ONLY` (8k cap, id/title/complexity/repos/dependsOn/type only) for LARGE/LEGACY. SINGLE_SHOT bypasses the LLM via `synthesizeSingleShot`. Both prompts (`outline-planner.md` + `outline-planner-skeleton.md`) bias toward **single-responsibility / fewest-cohesive-tasks** (NOT single-file-per-task) to curb over-decomposition (RL-2026-05-011). |
| `TaskExpanderAgent.java` | (WORKHORSE) Per-LEAF parallel expander. `submitTaskDetail` tool fills description / touchesFiles / hardConstraints / meetsRequirements. Dispatched via `CompletableFuture.allOf` on the 4-thread pipeline pool, 20-min wall timeout (LARGE/LEGACY). Per-task failures fall back to title-derived LEAFs. |
| `CoherencePlannerAgent.java` | (LIGHTWEIGHT) `patchPlan` tool with 5 patch kinds (ADD_MEETS_REQUIREMENT / ADD_DEPENDS_ON / REMOVE_DEPENDS_ON / FIX_HARD_CONSTRAINT_CONFLICT / MOVE_FILE_TO_REPO_SUBDIR). Empty patches array = clean plan. Non-fatal on per-patch failure. |
| `WorkerAgent.java` | (per-task tier — LOW=LIGHTWEIGHT / MEDIUM=WORKHORSE / HIGH=PLANNING) Sequential multi-turn loop. Native tool calling end-to-end via `LlmRouter`. `CompleteTaskTool` terminates. **One progress signal** (RL-2026-05-005): `itersSinceProgress` → one UserMessage nudge at `progressWarnAfter` (also on a text-only stop), force-terminate at `progressForceAfter`. **Lever B** best-guess salvage on iter-limit / budget-exceeded / progress-force when nothing was written. Snapshots conversation to Redis every 5 iters via `ContextStore`. |
| `PatchAgent.java` | Patch-mode single-shot agent. Per-provider iter caps (Anthropic 150, OpenAI 100, Gemini 120). Test-verification gate on `completeTask` (`failed:[]`, `errored:[]`, `exit_code:0` within `TEST_VERIFICATION_RECENCY_ITERS=5`). |
| `PlanStrategyCircuitBreaker.java` | Redis-backed. 5 consecutive failures → 1h open → ladder degrade LEGACY → LARGE → MEDIUM → SMALL → legacy. Fail-open on Redis errors. |
| `PlanStrategy.java` + `ClassificationResult.java` + `Plan.java` + `PlannedTask.java` + `RequirementSpec.java` | Plan data types. `RequirementSpec{id, text, kind}` (FUNCTIONAL / NON_FUNCTIONAL / TEST / CONSTRAINT) — every task cites which IDs it covers via `meetsRequirements`. |
| `PlannerOutputUtils.java` | Static helpers: `parsePlanFromArgs`, `validatePlan` (Validation v3 Pillar 1), `salvagePlanFromText` (Gemini pseudo-code recovery), `warnIfConstraintsUnderpropagated`, `cleanTitle`, `resolveProviderForTitle`, `truncate`. |
| `CompleteTaskTool.java` | Native termination tool — the worker calls it as a tool to end the loop. |
| `ConversationAgent.java` | Chat copilot. Uses LIGHTWEIGHT tier via `FallbackLlmRouter`. |
| `CostEstimator.java` + `RunCostEstimate.java` | Pre-plan cost estimate. V2 path uses `RunCostTracker.estimateRunTierAware` (see `cost/`). |
| `ApiKeyResolver.java` | Resolves API keys: user → org → system fallback. `isOwnKeysMode(userId)` returns true when org is `OWN_KEYS` (drives the BYOK cost-cap exemption). |
| `RunKeyStore.java` | Caches resolved keys per run. |
| `PromptAssembler.java` + `PromptAudit.java` | Composable prompt fragment system. Resolves `{{include:fragment}}` markers from `prompts/fragments/` + `{{VAR}}` substitutions. SHA-256 prompt hash → `prompt_audit` table. |

## Tier mapping (`application.yml agent-tier-map`)

| Tier | Agents |
|------|--------|
| PLANNING | OutlinePlannerAgent, ReviewerAgent, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent |
| WORKHORSE | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent |
| LIGHTWEIGHT | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, ConversationAgent, TestReportGenerator |

## Patterns

- **Native tool calling end-to-end.** No XML `<tool_call>` parsing in V2 — that was a V1 idiom. Strict JSON schemas on every `tool/` entry.
- **Sliding-window context:** first 2 + last 18 messages.
- **Token-cost control:** `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars.
- **Never call providers directly** — go through `LlmRouter` (native tool agents) or `FallbackLlmRouter` (non-tool agents).
- **Worker progress signal (RL-2026-05-005):** ONE counter `itersSinceProgress` (consecutive iters with no `writeFile`/`editFile`) drives everything. At `progressWarnAfter` (per-tier `req.stagnationWarnAfter`, default `STAGNATION_WARN_AFTER_N=8`) inject ONE "PROGRESS CHECK" UserMessage — the same one-shot nudge also fires on a premature text-only stop (model emits a summary without calling `completeTask`). At `progressForceAfter` (default `STAGNATION_FORCE_AFTER_N=12`) force-terminate with `COMPLETED_BY_STAGNATION` (Lever B salvage first if nothing was written). UserMessage (not SystemMessage) so Gemini's implicit cache prefix stays intact. This replaced the old Lever A + Lever C + stagnation warn/force quartet.
- **gitCommit denied for the worker** — `ToolRegistry.bindForWorker(ctx)` filters it (auto-push happens at run-end via `ScmPushService.pushMultiRepo`).
- **Validation v3 Pillar 1:** `PlannerOutputUtils.validatePlan` enforces every spec requirement is cited by at least one task; one retry on violation. Gated behind `validation.v3-strict` (default OFF).
- **Prompts** in `src/main/resources/prompts/*.md`. Rollback archives `planner.v*.txt` + `reviewer-v2.txt` retained for `PromptVersioningService`.
