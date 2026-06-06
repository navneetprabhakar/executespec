# ExecuteSpec Readiness And Harness Audit - 2026-06-05

## Scope

Requested audit: product, technology, and UX readiness, with special focus on what is missing for a robust harness that can generate high-quality output with non-frontier models such as DeepSeek.

This document is a working checkpoint artifact. It records what was inspected, intermediate conclusions, evidence, and final recommendations so the audit can survive context compaction.

## Methodology Correction - Code Is Authoritative

After the initial checkpoint, the founder clarified that generated/planning documents may paint an overly optimistic picture. This audit therefore treats repository documentation as **non-authoritative**.

Rules for the rest of this audit:

- Code, tests, migrations, configuration, frontend implementation, and checked-in scripts are authoritative.
- Generated docs, `CLAUDE.md` notes, planning docs, review docs, and reason logs are leads only.
- A claim is accepted only when it is backed by implementation code, tests, migrations, config, UI code, or runnable scripts.
- When docs and code disagree, the code wins and the disagreement is counted as documentation/operational drift.
- Existing docs are especially not trusted for readiness scoring, default flag state, provider support, or benchmark maturity.

## Checkpoint 0 - Starting State

- Date/time: 2026-06-05 21:17 IST.
- Repo root: `/Users/navneetprabhakar/Documents/antigravity/executespec`.
- Files tracked by `rg --files`: 2116.
- Existing audit docs found, but not treated as evidence: `docs/audit/REMEDIATION-2026-05-29.md`.
- Worktree was already dirty before this audit. Notable pre-existing modified/untracked areas:
  - `docs/reason-log/2026-05.md`
  - `src/main/java/com/navneet/executespec/agent/OpenAiNativeToolClient.java`
  - `src/main/java/com/navneet/executespec/auth/dto/CreateProjectRequest.java`
  - `src/main/java/com/navneet/executespec/codeintel/ArchitectureDocGenerator.java`
  - `src/main/java/com/navneet/executespec/codeintel/RepoArchitectureGenerator.java`
  - `src/main/java/com/navneet/executespec/integration/RunContextComposer.java`
  - `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`
  - `src/main/java/com/navneet/executespec/tool/remote/RemoteToolDispatcher.java`
  - `AGENTS.md`
  - `docs/plans/tiered-codemap-architecture.md`
  - `scripts/v2-eval-results/a1-20260530-082523/`
  - `src/main/java/com/navneet/executespec/codeintel/ArchLlmDispatch.java`
  - `src/main/java/com/navneet/executespec/codeintel/TieredCodemapSelector.java`
  - `src/main/java/com/navneet/executespec/terminalbench/`
  - `src/main/resources/db/migration/V230__seed_codeintel_tiered_flag.sql`
  - `src/test/java/com/navneet/executespec/codeintel/TieredCodemapSelectorTest.java`

## Inspection Plan

1. Map repository and count core surfaces.
2. Read the core execution path:
   - orchestration, planning, execution, worker loop, reviewer, scoring, model routing, tool dispatch, build/test runners, workspace and SCM push.
3. Read the harness/eval surfaces:
   - benchmark, SWE-bench, ProgramBench, terminalbench, eval scripts, model capture, score computation, run replay.
4. Read product/UX surfaces:
   - frontend shell, run creation, plan gate, run detail, validation report/issues, project dashboard, model/provider controls, CLI/VS Code client paths.
5. Synthesize readiness:
   - production readiness, product coherence, UX reliability, technical correctness, model-portability, non-frontier model support, and eval harness completeness.

## Checkpoint 1 - Execution Path And Worker Harness

Inspected primary execution path:

- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`
- `src/main/java/com/navneet/executespec/harness/SequentialExecutor.java`
- `src/main/java/com/navneet/executespec/engine/WorkerAgent.java`
- `src/main/java/com/navneet/executespec/tool/*`
- `src/main/java/com/navneet/executespec/agent/LlmRouter.java`
- `src/main/java/com/navneet/executespec/agent/OpenAiNativeToolClient.java`
- worker/planner/reviewer prompt files and provider fragments.

Note: any `CLAUDE.md` or plan-doc text encountered in this area was used only to find code paths. It was not accepted as readiness evidence without matching implementation.

Interim findings:

- The run lifecycle is real and reasonably complete: request creation, cost estimate, model/provider resolution, plan gate, workspace setup, multi-repo clone, sequential task execution, build smoke, reviewer, validation report, auto-preview, and SCM push are all present.
- `RunOrchestrator` is a very large orchestrator with extraction in progress. Several comments are stale relative to current behavior. Example: the class-level orientation still says plan gate/GitHub push/credits are not implemented, while the code does implement them. This is a readiness risk because operational docs and code disagree.
- BUILD mode is the actual product path. PATCH mode is intentionally thin and skips planner/executor/reviewer/validation report; it maps `COMPLETED_BY_STAGNATION` and `STOPPED_WITHOUT_COMPLETE` to terminal success when the patch agent has written enough to finish. That is acceptable for a failing-test autofix/benchmark loop, but not sufficient as a general quality harness.
- The worker loop has good rails for weaker models: native tool calling, read-before-edit, exact edit matching, no git mutations, wall-clock and cost caps, one progress nudge, stagnation force-stop, and a final zero-write salvage.
- The same worker loop still relies on several default-off levers that are specifically useful for non-frontier models:
  - `worker.provider-prompt-tuning.enabled`
  - `worker.salvage-force-tool-choice.enabled`
  - `worker.retry-context-retention.enabled`
  - `worker.cross-provider-fallback.enabled`
  - `worker.tool-compactor.enabled`
  - `worker.history-compaction.enabled`
  - `build.test-exec-gate.enabled`
  - `worker.build-tdd.enabled`
- Multi-task plans deliberately skip per-task smoke for intermediate tasks and rely on the final task + reviewer as the authoritative gate. This improves throughput but weakens task-level truth. It can hide a broken intermediate task until late.
- `SequentialExecutor` soft-completion is pragmatic but permissive: a worker that stops without `completeTask` after writes can proceed to smoke. A final smoke failure becomes `PARTIAL`; intermediate task smoke is skipped. This can make weak-model runs look more successful than the underlying task discipline.
- `RunTestsTool` is PATCH-mode and pytest-id oriented. `WorkspaceTestRunner` is language-neutral, but its in-loop worker tool (`runWorkspaceTests`) is default-off after DeepSeek regressions.
- DeepSeek support is partially wired:
  - `LlmRouter` has a native `deepseek` path through `OpenAiNativeToolClient`.
  - `OpenAiNativeToolClient` captures/replays DeepSeek `reasoning_content`.
  - The model matrix has `deepseek-v4-flash-native` for all tiers.
  - Provider prompt fragment exists and is pinned by tests, but is only injected through `SequentialExecutor.composeSystemPrompt` when the flag is on.
- DeepSeek support has a concrete contradiction: `WorkerAgent` can set `tool_choice="required"` for forced salvage, and tests pin that `LlmRequest.toolChoice()` is set; but `OpenAiNativeToolClient` currently strips all `tool_choice` whenever the base URL contains `deepseek`. That means the forced-salvage mitigation documented for DeepSeek can be neutralized on the actual native DeepSeek wire path.

## Checkpoint 2 - Scoring, Validation, Tests, And Truthfulness

Inspected validation/scoring path:

- `src/main/java/com/navneet/executespec/integration/RunValidationKickoff.java`
- `src/main/java/com/navneet/executespec/report/ValidationReportService.java`
- `src/main/java/com/navneet/executespec/report/ValidationReportGenerator.java`
- `src/main/java/com/navneet/executespec/reviewer/AccuracyAssessmentService.java`
- `src/main/java/com/navneet/executespec/smoketest/WorkspaceTestRunner.java`
- `src/main/java/com/navneet/executespec/smoketest/TestCommandResolver.java`
- `src/main/java/com/navneet/executespec/smoketest/BuildCommandResolver.java`
- `src/main/java/com/navneet/executespec/smoketest/TestOutputParser.java`

Interim findings:

- The additive verified score is materially better than an LLM-only score. It uses code-delivered, final build, real test pass rate when available, reviewer verdict, and critical issue count. The `filesGenerated == 0` hard floor is correct.
- Anti-gaming guards are present and default-on for additive scoring: endpoint coverage tightens CODE_DELIVERED and coverage quality caps the TESTS slot when tests are trivial or incomplete.
- Real post-run test execution is present via `WorkspaceTestRunner` and `scoring.real-test-exec`. It derives commands from markers rather than model text, runs in benchmark containers where available, and returns empty rather than fake counts when output is unparseable.
- The test runner supports pytest, Jest/npm, Maven/Gradle Surefire, Go, and Cargo. It does not cover every modern stack, and it only runs the worker's own tests. This is useful but not equivalent to held-out acceptance correctness.
- `BuildCommandResolver` falls back to `true` when no markers exist. This prevents spurious failures but makes the build signal meaningless on unknown stacks unless the UI/report makes that source obvious.
- `AccuracyAssessmentService` is still a legacy non-tool LLM assessor. It reads a bounded file tree and selected snippets, then assigns spec adherence, endpoint coverage, schema accuracy, and test coverage level. It has thoughtful unknown-vs-missing rules and a grep demotion pass, but endpoint/test coverage still ultimately come from bounded LLM evidence.
- There is prompt tension inside `AccuracyAssessmentService`: the system rules say absence of evidence should become `UNKNOWN`, but the user prompt later says "Mark as MISSING only if you find NO evidence in any source file." That can push weaker models back toward false missing claims.
- `AccuracyAssessmentService.defaultReport()` is optimistic: with no unfixed criticals it returns a 75 score and `testCoverageLevel=PARTIAL`. If the assessor fails, downstream scoring can still look decent unless deterministic build/tests/reviewer contradict it.
- `ValidationReportGenerator` still uses `FallbackLlmRouter` and direct resource loading. It is narrative-only with structured fallback, so the main risk is not correctness but attribution: the generated markdown can sound more certain than the structured signals justify.

## Checkpoint 3 - Eval Harness And Release Evidence

Inspected benchmark/eval surfaces:

- `src/main/java/com/navneet/executespec/benchmark/BenchmarkRunService.java`
- `src/main/java/com/navneet/executespec/terminalbench/TerminalBenchRunService.java`
- `scripts/benchmark-run.sh`
- `scripts/build-eval.sh`
- `scripts/a1-flag-eval.sh`
- `scripts/v2-eval-results/a1-20260530-082523/*`
- `scripts/build-eval-cases/rest-api-low/*`
- `docs/plans/benchmark-strategy.md` as a lead only, not as evidence.

Interim findings:

- SWE-bench harness design is strong: real production submit path, blind problem statement, cleanroom container, network off, no gold tests in prompt, source-only diff capture, and concurrency gating.
- SWE-bench code-gen mode intentionally stops after worker to avoid reviewer/formatter/diff pollution. That is right for leaderboard patch capture but not a full product-readiness measure.
- Terminal-Bench integration is a direct worker-loop remote-tool adapter. It is useful for terminal-agent evals but bypasses planner/reviewer/scoring, so it should not be treated as product-pipeline evidence.
- `build-eval.sh` is exactly the missing direction for BUILD readiness: blind spec in, generated workspace out, held-out acceptance tests run by the harness. However it currently has only one sample case. It is not yet a corpus or release gate.
- `a1-flag-eval.sh` compares five worker levers OFF vs ON across Anthropic/OpenAI/Gemini. The checked-in result is one medium case and shows improved OpenAI score/cost with flags on, but it is far too small to justify defaults.
- There is no unified "model readiness dashboard" that combines held-out acceptance score, zero-edit rate, forced-salvage rate, build pass rate, reviewer rescue rate, cost, latency, and per-provider failure taxonomy.

## Checkpoint 4 - Product/UX And DeepSeek Usability

Inspected primary frontend/product surfaces:

- `frontend/src/App.tsx`
- `frontend/src/ide/IdeWorkspace.tsx`
- `frontend/src/ide/IdeSurfaces.tsx`
- `frontend/src/ide/IdePlan.tsx`
- `frontend/src/ide/IdeRunResult.tsx`
- `frontend/src/ide/IdeProjectTabs.tsx`
- `frontend/src/ide/IdeOrg.tsx`
- `frontend/src/lib/api/runs.ts`
- `frontend/src/lib/api/models.ts`
- backend model/org key controllers and DTOs.

Interim findings:

- The protected app is coherent and usable: IDE shell, compose, plan gate, live run, result, code browser, validation, tests, cost, PR, project tabs, org settings, model matrix.
- The Console chrome is still flag-gated via localStorage and not the default. Given the stated reframe away from "IDE in browser" toward mission control, this remains a product-positioning transition rather than a finished default experience.
- The run result overview surfaces a large verified score and compact signals, but not enough provenance:
  - no breakdown of verified score weights;
  - no explicit "deterministic vs LLM-judged" labels;
  - no indication whether build was no-op fallback, compile-only, test-exec-gated, or boot-verified;
  - no indication which harness feature flags were on;
  - no held-out acceptance score.
- The Validation tab mostly shows pipeline result, files, issue counts, and issues. It does not expose the accuracy JSON, endpoint coverage, test coverage level, score slots, or measurement source in a way that would help a customer trust or debug a weak-model run.
- Compose exposes TDD mode, but the in-loop TDD/test tool is default-off after the DeepSeek regression. The UX currently does not explain that TDD mode mainly affects planner ordering unless the platform flags are enabled.
- DeepSeek is not actually selectable in the New Spec provider picker. Compose shows Anthropic/OpenAI/Gemini and Enterprise OpenRouter only.
- DeepSeek BYOK is not implemented end-to-end:
  - `Org` has encrypted key fields for Anthropic/OpenAI/Gemini/OpenRouter only.
  - `OrgDto` has boolean key flags for those four only.
  - `OrgService.updateAiKeySettings` accepts those four only.
  - `ApiKeyResolver`, `ResolvedKeys`, `RunKeyContext`, and `AvailableProviderResolver` do not carry a DeepSeek key.
  - `AvailableProviderResolver` does not consider system DeepSeek key or org DeepSeek key, even though `LlmProvider.DEEPSEEK` exists and model matrix has a DeepSeek provider.
  - Org settings UI has no DeepSeek key row.
- The backend does accept `DEEPSEEK` in some request DTOs and spec-chat tooling, so the system currently has a "provider exists in enum/router/matrix" state but not a clean product path for customers to use it.

## Checkpoint 5 - Code-Intelligence Context

Inspected context/codemap paths:

- `src/main/java/com/navneet/executespec/integration/RunContextComposer.java`
- `src/main/java/com/navneet/executespec/codeintel/TieredCodemapSelector.java`
- `src/main/java/com/navneet/executespec/codeintel/ArchLlmDispatch.java`
- `docs/plans/tiered-codemap-architecture.md` as a lead only, not as evidence.

Interim findings:

- The project has the right instinct for weaker models: reduce standing context, inject relevant symbols rather than long directory listings, and use code-intel as a localization hint rather than a complete dump.
- `RunContextComposer` recently shrank codemap and architecture injection caps heavily, explicitly because DeepSeek/project-mode evals showed large prefixes hurting attention and causing zero edits or edit jitter.
- `TieredCodemapSelector` is a good Phase-1 deterministic fix: skeleton + spec-relevant function blocks, fallback to head truncation only on unparseable codemap. It is default-off behind `codeintel.tiered.enabled`.
- Semantic code retrieval exists for project chat, but the worker/planner injection path is still Phase 1 lexical selection. The stronger "retrieve relevant code chunks per task" harness is designed but not built.

## Readiness Assessment

Short version: ExecuteSpec is a serious V2 pipeline with many of the right primitives, but it is not yet a finished "proper harness" for consistently high-quality output from non-frontier models. It is closer to a strong beta platform with advanced internal eval infrastructure than a release-gated, evidence-backed non-frontier production harness.

### Product Readiness

Score: 6.5 / 10.

Strengths:

- Clear product thesis: spec → plan → gate → code → build/test/review → PR.
- Plan gate, project context, PR creation, validation report, preview/deploy, run chat, project memory, tech-debt autofix, and BYOK/model-matrix features create a real product surface rather than a demo.
- The UX gives users a visible run loop and post-run artifacts.

Gaps:

- The product does not yet tell a user exactly why a run should be trusted. The verified score is prominent, but measurement provenance is not.
- The new Console/mission-control framing is still opt-in, while the default remains IDE metaphor-heavy.
- DeepSeek is not a real first-class customer option despite router/model-matrix support.
- TDD mode is exposed as if it is an execution behavior, while the core in-loop test tool is default-off.

### Technical Readiness

Score: 7 / 10 for frontier-provider BUILD runs; 5 / 10 for non-frontier model harnessing.

Strengths:

- Native tool-calling router, read-before-edit, edit exactness, sandboxing, structured costs, feature flags, review passes, real post-run test execution, additive score, and anti-gaming guards are all strong foundations.
- The SWE-bench adapter is designed with public-leaderboard integrity in mind.
- There is meaningful test coverage over core units: scoring, worker behavior, tool dispatch, access guards, provider prompt fragments, code-intel selection, and command parsers.

Gaps:

- Too many non-frontier protections are built but default-off, and the evidence for flipping them is not yet broad enough.
- DeepSeek has incomplete BYOK/product wiring and a forced-salvage wire contradiction around `tool_choice`.
- Reviewer and accuracy assessment still lean on LLM judgment. For weak models, this should be secondary to deterministic checks and held-out acceptance tests.
- Build/test execution is still mostly compile/self-test. Held-out correctness is a script skeleton, not a corpus or release gate.
- `PromptAssembler` is a stated convention, but important agents/reviewer/report paths still load prompt resources directly. This weakens prompt include/version/audit discipline.
- Several source comments and orientation docs drift from the code. Drift is not cosmetic in a harness; it corrupts operator decisions.

### UX Readiness

Score: 5.5 / 10.

Strengths:

- The app has the right surfaces: compose, plan, live execution, result, code, tasks, logs, validation, tests, cost, PR, org model matrix.
- Recent-runs and plan-gate indicators help operators keep track of in-flight work.

Gaps:

- Validation UI is too shallow for a trust product. It should show score slots, source of each signal, exact commands run, runner source, no-op build fallback, test coverage level, endpoint coverage, and whether each signal was deterministic/LLM judged.
- No model-readiness surface exists for DeepSeek/other non-frontier models: zero-edit rate, average iterations, salvage rate, wrong-file rate, hidden-test pass rate, and flags enabled.
- The run result labels ("Shippable", large score) can overstate confidence when the underlying checks are compile-only or self-test-only.
- Provider availability is inconsistent: backend accepts providers the UI cannot configure or run cleanly.

## What Is Missing For A Proper Non-Frontier Harness

### 1. First-class DeepSeek provider path

Required:

- Add encrypted `deepseek_api_key` fields at org/user levels, DTO flags, migrations, key update API, org settings UI row, `ApiKeyResolver`, `ResolvedKeys`, `RunKeyContext`, and `AvailableProviderResolver` support.
- Add DeepSeek to Compose provider picker when available, with clear Enterprise/BYOK gating.
- Add tests that verify a DeepSeek BYOK org can submit a run without platform keys and that unavailable DeepSeek is hidden/rejected cleanly.
- Fix model/provider docs so "Enterprise BYOK native DeepSeek" is not just a router comment.

### 2. Provider capability matrix, not provider string hacks

Required:

- Move behavior like "supports `tool_choice=required`", "requires reasoning_content echo", "thinking-mode rejects tool_choice", "max safe output", and "parallel tool calls allowed" into model/provider metadata.
- Change `OpenAiNativeToolClient` from "baseUrl contains deepseek ⇒ strip all tool_choice" to model-capability-driven behavior.
- Add wire-level tests for native DeepSeek request bodies, not only `LlmRequest` tests.

### 3. Deterministic held-out BUILD eval corpus

Required:

- Grow `scripts/build-eval-cases` from 1 sample to at least:
  - 10 LOW cases
  - 15 MEDIUM cases
  - 10 HIGH cases
  - 5 existing-repo edit cases
  - stacks: Python/FastAPI, Java/Spring, Node/Express, React/Vite, CLI/library, multi-repo.
- Each case needs a spec, hidden acceptance tests, expected build/boot command, allowed runtime dependencies, and a scoring parser.
- Nightly matrix should produce `BUILD-resolved %`, not only `verifiedScore`.
- Release gates should use held-out score + zero-edit rate + build pass rate + cost/latency, per provider.

### 4. In-loop verification redesigned for weak models

Required:

- Do not rely on weak models to voluntarily call a broad `runWorkspaceTests` tool if that tool shape harms tool-use behavior.
- Prefer platform-mediated verification checkpoints:
  - after edits, platform runs resolver-derived build/tests;
  - worker receives a compact, structured failure summary;
  - worker has a fixed retry budget.
- Keep the tool schema small for DeepSeek: fewer tools, stricter task prompts, stronger file-path localization, and forced edit/complete choices where supported.

### 5. Task-level acceptance contracts

Required:

- Convert planner `requirements[]` into explicit acceptance checks where possible.
- Each task should carry:
  - requirement IDs,
  - files allowed/touched,
  - expected verification command,
  - postcondition text,
  - hidden/deterministic checks where available.
- A run should not be "green" only because the final build passed; it should show requirement coverage and acceptance coverage.

### 6. Context retrieval should be per task, not mostly static prefix

Required:

- Turn tiered codemap on only after A/B evidence, then move to semantic T1 selection from `project_code_chunk`.
- Add a worker `codemap_lookup` or `search_code` tool for on-demand context instead of growing standing prompts.
- Record which context chunks were injected and which were actually read/used.

### 7. Independent verifier strategy

Required:

- For DeepSeek-generated code, do not rely solely on DeepSeek for review/accuracy.
- Options:
  - deterministic validators first;
  - stronger reviewer model for paid production runs;
  - two-model cross-check only on suspicious patterns;
  - held-out tests as primary truth.
- Make verifier model/cost visible in run result.

### 8. Harness observability and failure taxonomy

Required:

- Persist and surface:
  - zero-write terminal runs;
  - text-only stops;
  - wrong-file edits;
  - stale-read edit failures;
  - salvage attempted/succeeded/failed;
  - forced tool-choice accepted/rejected;
  - build command source;
  - test command source;
  - no-op build fallback;
  - context bytes injected;
  - model/provider per task, not just run-level worker model.
- Add a provider readiness dashboard that slices these by provider/model/flag set.

### 9. UX trust layer

Required:

- Replace the single verified-score emphasis with a scorecard:
  - Code delivered
  - Build
  - Tests
  - Held-out acceptance
  - Reviewer
  - Criticals
  - Boot/preview health
  - Measurement source for each.
- Add "Confidence" labels:
  - deterministic,
  - self-test only,
  - LLM judged,
  - unmeasured.
- On low-confidence runs, show "Needs human review" even if the score is numerically high.

## Prioritized Plan

### P0 - Correctness / credibility blockers

1. Make DeepSeek BYOK/provider selection end-to-end.
2. Fix DeepSeek forced-salvage `tool_choice` behavior using provider capability metadata and wire tests.
3. Add verified-score breakdown/provenance to API and UI.
4. Expand `build-eval` to a 20-case smoke corpus and make it the non-frontier release gate.
5. Expose no-op build/test fallback explicitly in validation.

### P1 - Non-frontier quality lift

1. A/B `worker.provider-prompt-tuning.enabled`, `worker.salvage-force-tool-choice.enabled`, `worker.retry-context-retention.enabled`, and `codeintel.tiered.enabled` on a 50-case DeepSeek set.
2. Add platform-mediated in-loop verification retry for BUILD without adding a broad new worker tool.
3. Use semantic code retrieval for task-relevant context.
4. Add task-level acceptance contracts and requirement coverage.
5. Make reviewer strategy configurable by risk/provider.

### P2 - Productization

1. Promote Console chrome when it can show mission-control score provenance better than the IDE shell.
2. Add model readiness dashboards for operators and Enterprise admins.
3. Generate per-run reproducibility bundles: prompt versions, model IDs, flags, commands, diffs, logs, validation signals, acceptance results.
4. Add a "non-frontier mode" preset: smaller tool surface, provider fragment on, context tiering on, deterministic checkpoints on, stricter completion gate.

## Final Judgment

ExecuteSpec has unusually strong foundations for an AI coding platform: real orchestration, native tool use, scoring discipline, benchmark adapters, and a lot of accumulated operational learning. The main issue is not lack of components; it is that the components needed for non-frontier robustness are scattered behind flags, not productized into a single measured harness.

For frontier models, this can plausibly ship as a controlled beta with honest caveats. For DeepSeek-class/non-frontier models, it needs a tighter harness before claiming readiness: first-class provider wiring, capability-aware tool calling, deterministic held-out BUILD evals, platform-mediated verification, smaller/relevant context, and UI provenance that prevents over-trusting self-graded outputs.
