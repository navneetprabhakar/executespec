# ExecuteSpec Prompt State Audit - 2026-06-05

## Scope

This is a code-first checkpoint. I inspected all 92 files under
`src/main/resources/prompts/`, then checked runtime references in `src/main/java`
and `src/test/java`. I did not trust generated docs as evidence.

Two runtime prompt surfaces are not files under `prompts/` but are included here
because they materially affect output quality:

- `SequentialExecutor.SYSTEM_PROMPT_BASE` in `src/main/java/com/navneet/executespec/harness/SequentialExecutor.java`
- `AccuracyAssessmentService.SYSTEM_PROMPT` in `src/main/java/com/navneet/executespec/reviewer/AccuracyAssessmentService.java`

## Executive State

Overall state: **not prompt-governed enough for a non-frontier harness yet**.

The prompt set has many strong individual prompts, especially patch mode and the
single-call review agents. The problem is consistency and runtime activation:
several live prompts contain stale contracts, several useful fragments are not
used by the live planner, several active prompts bypass `PromptAssembler`, and
DeepSeek/OpenRouter-specific prompt tuning is default-off.

### P0 Findings

1. **Live outline planner has stale taxonomy.**
   `outline-planner.md` still says the task cap is `SINGLE_SHOT`, `MEDIUM`,
   `MULTI_TASK`. The current classifier emits `SINGLE_SHOT`, `SMALL`, `MEDIUM`,
   `LARGE`, `LEGACY`. This is active runtime drift and can confuse task sizing.

2. **Scaffold injection key mismatch.**
   `SequentialExecutor.loadScaffoldForProjectType()` loads
   `prompts/scaffolds/<projectType>.md`. The live planner tells the model to
   emit `java-spring-boot`, but the file is `scaffolds/spring-boot.md`, so the
   Spring Boot scaffold is likely not injected. `static-html.md`, `fullstack.md`,
   `java.md`, and `all.md` also appear unreachable from the live planner's
   current `projectType` list.

3. **Reviewer prompt conflicts with tests-as-evidence.**
   `reviewer-general.md` explicitly says missing tests are out of scope unless
   the spec asked for them. The worker base prompt says tests are mandatory for
   changed functions/endpoints/modules, and scoring uses tests as evidence. The
   reviewer is therefore blind to a major readiness signal.

4. **Shared safety/build fragments are mostly not used by live prompts.**
   `api-url-rules.md`, `backend-robustness.md`, `preview-defaults.md`, and
   `security-rules*.md` are only included by retired `planner*.txt` files. The
   active outline planner does not resolve includes and does not use these
   fragments.

5. **Provider prompt tuning is default-off.**
   The DeepSeek/OpenRouter/Gemini/OpenAI/Anthropic fragments contain important
   behavioral guardrails, but they are only appended when
   `worker.provider-prompt-tuning.enabled` is enabled. Normal BUILD runs do not
   reliably get those provider-specific instructions.

6. **Most active prompts bypass `PromptAssembler`.**
   Only the classifier and compactor prompts use `PromptAssembler`. Many critical
   prompts are loaded with `ClassPathResource` or `@Value` and manual
   `.replace(...)`. This means no include resolution, no unified unresolved
   placeholder check, and no consistent prompt hash/audit path.

### P1 Findings

1. **Weak-model context controls are promising but default-off.**
   `tool-output-compactor.md` and `worker-history-compactor.md` are good prompts,
   but `worker.tool-compactor.enabled` and `worker.history-compaction.enabled`
   are default-off. DeepSeek-class models still face large accumulated context
   unless those flags are deliberately enabled and evaluated.

2. **Native DeepSeek cannot rely on forced `tool_choice`.**
   `OpenAiNativeToolClient` intentionally omits `tool_choice` for native
   DeepSeek because the API can reject it. That makes text-only/no-tool behavior
   a prompt-and-loop problem rather than an enforceable wire-level guarantee.

3. **Retired prompts remain in the live prompt directory.**
   `planner.txt`, `planner.v1.txt` through `planner.v14.txt`, `triage.txt`, and
   `reviewer-v2.txt` are stale or rollback-only but sit beside live prompts.
   This increases the chance future agents, optimizers, or humans edit the wrong
   file.

4. **Test-generation prompts are legacy-router prompts.**
   `test-planner.txt`, `test-worker.txt`, `test-fix-suggestions.txt`,
   `api-test-suite.txt`, `validation-report.txt`, and accuracy assessment still
   use the legacy/non-tool path. They can work, but they are not first-class
   native tool-calling prompts across all providers.

5. **Reports and memory prompts are lossy by design.**
   `validation-report.txt`, `memory-compaction.txt`, and `run-ledger.txt` are
   useful product outputs, but they intentionally compress and reframe. They
   must not be treated as readiness evidence.

## Prompt-by-Prompt Inventory

Legend:

- **Good**: usable as-is, minor cleanup only.
- **Needs cleanup**: not obviously breaking output, but stale/noisy/incomplete.
- **High risk**: active runtime prompt with a correctness/readiness issue.
- **Archive**: should not influence runtime; move to explicit archive or delete.
- **Eval only**: used by eval harness, not customer runtime.
- **Dormant**: available file, but not currently reachable by live runtime keys or flags.

| Prompt | Runtime state | State | Notes and action |
|---|---:|---:|---|
| `api-test-suite.txt` | Active post-run | Needs cleanup | OpenAPI generator prompt is clear, but output is only lightly validated. Add schema parsing/validation before saving. |
| `classifier.md` | Active | Good | Uses `PromptAssembler`; current enum set is correct. Keep and add conformance tests for malformed JSON/no preamble. |
| `codereview/code-review-agent.md` | Active | Good | Clear tool contract and scope. Move loading to `PromptAssembler` for audit/hash consistency. |
| `codereview/pr-review-agent.md` | Active | Good | Clear PR-review scope and bounded comments. Move loading to `PromptAssembler`. |
| `coherence-planner.md` | Active | Good | Useful structural pass. Limited by design: it cannot add missing tasks, only patch references/paths. |
| `context-agent.txt` | Active legacy | Needs cleanup | Concise JSON-patch prompt. Verify whether CODEBASE_CONTEXT remains a live product surface; otherwise retire. |
| `conversation-system.md` | Active legacy | Needs cleanup | Good suggest-first UX for old conversation flow. Must be separated from new spec chat so behavior does not drift. |
| `execution_planner.txt` | Active | Needs cleanup | Thin multi-repo solution planner. Needs stricter schema/acceptance criteria and `PromptAssembler` migration. |
| `memory-compaction.txt` | Active | Good | Fit for compressed project memory. Not evidence-grade by design. |
| `outline-planner-skeleton.md` | Active | Needs cleanup | Generally good, but projectType examples omit `react-native-expo` while runtimeSurface includes `MOBILE_APP`. Align with tool schema and scaffold keys. |
| `outline-planner.md` | Active | High risk | Stale `MULTI_TASK` cap, scaffold key drift, active planner does not use shared fragments. Fix first. |
| `patch-agent.md` | Active PATCH mode | Needs cleanup | Strong reproduce-first base, but currently isolated from provider/model prompt tuning. Route PATCH through the shared prompt variant resolver so DeepSeek/OpenRouter/Qwen/Kimi get the right model-level guidance. |
| `planner.txt` | Retired/unused | Archive | Old V1/V2 planner schema, parallel tiers, `owned_files`, include markers. Move out of live prompt root. |
| `planner.v1.txt` | Rollback archive | Archive | Old planner version. Keep only in explicit archive if rollback is still needed. |
| `planner.v2.txt` | Rollback archive | Archive | Same as above. |
| `planner.v3.txt` | Rollback archive | Archive | Same as above. |
| `planner.v4.txt` | Rollback archive | Archive | Same as above. |
| `planner.v5.txt` | Rollback archive | Archive | Same as above. |
| `planner.v6.txt` | Rollback archive | Archive | Same as above. |
| `planner.v7.txt` | Rollback archive | Archive | Same as above. |
| `planner.v8.txt` | Rollback archive | Archive | Same as above. |
| `planner.v9.txt` | Rollback archive | Archive | Same as above. |
| `planner.v10.txt` | Rollback archive | Archive | Same as above. |
| `planner.v11.txt` | Rollback archive | Archive | Same as above. |
| `planner.v12.txt` | Rollback archive | Archive | Same as above. |
| `planner.v13.txt` | Rollback archive | Archive | Same as above. |
| `planner.v14.txt` | Rollback archive | Archive | Same as above. |
| `reviewer-general.md` | Active | High risk | Scope excludes missing tests. Update to enforce tests-as-evidence and no-op/fallback visibility. |
| `reviewer-v2.txt` | Rollback archive | Archive | Old reviewer prompt with obsolete tool names and "AI-generated code" framing. Move to archive. |
| `reviewer-wiring.md` | Active | Good | Clear wiring/blast-radius pass. Add tests for verdict parsing and no text outside `completeTask`. |
| `run-ledger.txt` | Active | Good | Good compressed memory prompt. Not audit evidence. |
| `task-expander.md` | Active | Good | Strong description/hardConstraints guidance. Add validation that `meetsRequirements` is non-empty when requirements exist. |
| `test-fix-suggestions.txt` | Active legacy | Good | Focused failure-to-fix classifier. Move to `PromptAssembler`; add JSON parse retry. |
| `test-planner.txt` | Active legacy | Needs cleanup | Good behavioral-test guidance, but legacy placeholders and Fallback router. Not provider-native. |
| `test-worker.txt` | Active legacy | Needs cleanup | Generates Java black-box tests for HTTP/CLI/static. Useful, but prompt says "Java integration test class" regardless of target stack. Clarify this is the harness language. |
| `tool-output-compactor.md` | Active default-off | Good | Strong fidelity rules. Enable/evaluate for non-frontier preset. |
| `triage.txt` | Unused/stale | Archive | Old classifier taxonomy: `SINGLE_SHOT`, `MEDIUM`, `MULTI_TASK`. Delete or archive. |
| `validation-report.txt` | Active legacy | Needs cleanup | Useful customer-facing CI framing, but can hide agent/provider failure detail. Keep product-facing only. |
| `worker-history-compactor.md` | Active default-off | Good | Good progress-note prompt. Enable/evaluate for long worker runs. |
| `fragments/api-url-rules.md` | Fragment | Dormant | Important rule, but only included by retired planner files. Inject into live planner/worker. |
| `fragments/backend-robustness.md` | Fragment | Dormant | Important backend readiness rule. Inject into live planner/worker. |
| `fragments/compilation-checklist.md` | Fragment | Dormant | Useful, but currently unused by live prompts. Either inject or delete. |
| `fragments/cors-config.md` | Fragment | Dormant | Good env-driven rule, but conflicts with active scaffold wildcard examples. Normalize CORS policy. |
| `fragments/format-after-edit.md` | Fragment | Dormant/duplicated | Worker base has a similar hardcoded section. Consolidate to one source. |
| `fragments/fullstack-directory.md` | Fragment | Dormant | Useful but not included in live outline planner. |
| `fragments/module-system.md` | Fragment | Dormant | Useful for Node; inject when Node projectType. |
| `fragments/preview-defaults.md` | Fragment | Dormant | Useful preview defaults; not included by live planner. |
| `fragments/provider-anthropic.md` | Provider fragment | Dormant by default | Good provider-specific behavior, but behind `worker.provider-prompt-tuning.enabled`. |
| `fragments/provider-deepseek.md` | Provider fragment | Dormant by default | Important for DeepSeek zero-edit/no-tool failure. Should be part of non-frontier preset. |
| `fragments/provider-gemini.md` | Provider fragment | Dormant by default | Good cache/read-once discipline. Enable selectively. |
| `fragments/provider-openai.md` | Provider fragment | Dormant by default | Useful but contains benchmark-specific guidance. Keep provider-specific, not global. |
| `fragments/provider-openrouter.md` | Provider fragment | Dormant by default | Useful for gateway latency/tool schema only. OpenRouter is an aggregator, so this must be paired with underlying model-family/exact-model fragments; it is not sufficient by itself. |
| `fragments/router-isolation.md` | Fragment | Dormant | Important Express rule. Inject for Node backend/fullstack plans. |
| `fragments/security-rules-planner.md` | Fragment | Dormant | Security rules are only in retired planner includes. Move into live planner path. |
| `fragments/security-rules-worker.md` | Fragment | Dormant | Security rules are not in worker base via assembler. Consolidate with worker prompt. |
| `fragments/security-rules.md` | Fragment | Dormant | Strong prompt-injection/tool-result rule, but live worker relies mostly on hardcoded base and tool sandbox. Inject or delete. |
| `scaffolds/all.md` | Scaffold | Dormant/stale | Old projectType names and task schema. Not loaded by current projectType keys. Archive or regenerate. |
| `scaffolds/fullstack.md` | Scaffold | Dormant/stale | Live planner does not emit `fullstack`; schema uses old fields. Archive or map intentionally. |
| `scaffolds/go.md` | Scaffold | Active when `projectType=go` | Needs cleanup | Schema uses old `task_id` style but guidance is usable. Convert to worker-facing constraints. |
| `scaffolds/java.md` | Scaffold | Likely dormant | Needs cleanup | Live planner does not list plain `java`. Add key mapping or remove. |
| `scaffolds/node-backend.md` | Scaffold | Active when `projectType=node-backend` | Needs cleanup | Good module-system guidance, but old task schema noise. |
| `scaffolds/node-frontend.md` | Scaffold | Active when `projectType=node-frontend` | Needs cleanup | Good config-only split, but old task schema noise. |
| `scaffolds/python.md` | Scaffold | Active when `projectType=python` | Needs cleanup | Useful Django/FastAPI/Flask boundaries, but CORS wildcard should align with env-driven policy. |
| `scaffolds/react-native-expo.md` | Scaffold | Active when `projectType=react-native-expo` | High risk | Temporally unstable SDK instruction and heavyweight exact dependency list. Needs current-version validation and tests. |
| `scaffolds/rust.md` | Scaffold | Active when `projectType=rust` | Needs cleanup | Usable, but old task schema should be converted to constraints. |
| `scaffolds/spring-boot.md` | Scaffold | Likely unreachable | High risk | Filename does not match live `java-spring-boot` projectType. Add alias or rename/copy to `java-spring-boot.md`. |
| `scaffolds/static-html.md` | Scaffold | Likely dormant | Needs cleanup | Live planner uses runtimeSurface `STATIC_WEB`, not projectType `static-html`. Add explicit keying if needed. |
| `eval/cost-ai-pdf-summarizer.txt` | Eval only | Good | Cost/build eval prompt. Uses mocks; not readiness evidence for real provider quality. |
| `eval/cost-booking-system.txt` | Eval only | Good | Cost/fullstack fixture. Mock email; okay for cost eval, weak for correctness eval. |
| `eval/cost-ecommerce-store.txt` | Eval only | Good | Cost/fullstack fixture. Broad app spec; useful for cost, not held-out verification. |
| `eval/cost-fitness-dashboard.txt` | Eval only | Good | Cost/fullstack fixture. |
| `eval/cost-kanban-board.txt` | Eval only | Good | Cost/frontend fixture. |
| `eval/cost-landing-page.txt` | Eval only | Good | Cost/frontend fixture; visual quality is underspecified for modern UX checks. |
| `eval/cost-portfolio-blog.txt` | Eval only | Good | Cost/frontend fixture; placeholder thumbnails reduce UX realism. |
| `eval/cost-realtime-chat.txt` | Eval only | Good | Cost/fullstack realtime fixture; mocks acceptable for cost eval only. |
| `eval/cost-rest-api-recipes.txt` | Eval only | Good | Backend/API cost fixture. |
| `eval/cost-saas-dashboard.txt` | Eval only | Good | Fullstack cost fixture with mock data. |
| `eval/cost-url-shortener.txt` | Eval only | Good | Fullstack/API fixture; good endpoint density. |
| `eval/lightweight-request-triage.txt` | Eval only | Needs cleanup | Uses old `MULTI_TASK` classification. Keep only if this eval intentionally tests legacy triage. |
| `eval/lightweight-review-summary.txt` | Eval only | Good | Useful lightweight summarization case. |
| `eval/lightweight-validation-report.txt` | Eval only | Good | Useful report parsing/summarization case. |
| `eval/planning-architecture-decision.txt` | Eval only | Good | Planning reasoning fixture. |
| `eval/planning-code-review.txt` | Eval only | Good | Good code-review planning fixture. |
| `eval/planning-conflicting-requirements.txt` | Eval only | Good | Useful ambiguity/constraint fixture. |
| `eval/planning-plan-generation.txt` | Eval only | Good | Useful complex plan generation fixture. |
| `eval/planning-saga-compliance.txt` | Eval only | Good | Strong domain/compliance fixture. |
| `eval/planning-saga-consistency.txt` | Eval only | Good | Strong consistency/transaction fixture. |
| `eval/planning-saga-diagnosis.txt` | Eval only | Good | Strong incident-diagnosis fixture. |
| `eval/workhorse-compilation-fix.txt` | Eval only | Good | Good workhorse bug-fix fixture. |
| `eval/workhorse-optimistic-locking.txt` | Eval only | Good | Good Java concurrency fixture. |
| `eval/workhorse-react-component.txt` | Eval only | Good | Good frontend component fixture, but no browser verification. |
| `eval/workhorse-websocket-notification.txt` | Eval only | Good | Good Spring/WebSocket fixture. |

## Runtime Prompt Surfaces Outside `prompts/`

| Prompt surface | Runtime state | State | Notes and action |
|---|---:|---:|---|
| `SequentialExecutor.SYSTEM_PROMPT_BASE` | Active | Needs cleanup | Core worker prompt is strong but hardcoded. It duplicates dormant fragments, cannot be hashed/versioned by `PromptAssembler`, and provider fragments are appended only behind a flag. Externalize or at least hash/audit the composed prompt. |
| `AccuracyAssessmentService.SYSTEM_PROMPT` | Active legacy | Needs cleanup | Good UNKNOWN-vs-MISSING discipline, but inline and uses `FallbackLlmRouter`. Externalize, align with verified-score provenance, and add deterministic fallback behavior tests. |

## Recommended Prompt Cleanup Order

1. Fix `outline-planner.md` taxonomy and align its task-cap section with
   `SINGLE_SHOT`, `SMALL`, `MEDIUM`, `LARGE`, `LEGACY`.
2. Fix scaffold keying: add `scaffolds/java-spring-boot.md` or alias
   `java-spring-boot -> spring-boot`; decide whether `static-html`, `fullstack`,
   `java`, and `all` are real supported keys or archives.
3. Update `reviewer-general.md` so missing mandatory tests and no verification are
   in scope.
4. Move retired prompts (`planner*.txt`, `triage.txt`, `reviewer-v2.txt`) to an
   explicit archive directory or delete after confirming rollback requirements.
5. Move active agent prompts to `PromptAssembler` and add a startup check for
   unresolved placeholders.
6. Decide a default non-frontier preset: provider fragment ON, tool-output
   compactor ON, history compactor ON, stricter no-tool/no-edit salvage telemetry.
7. Regenerate scaffold prompts as worker-facing constraints, not old task JSON
   snippets.
8. Add prompt conformance tests: each active prompt should have one test proving
   the expected tool call or JSON shape is accepted by the parser.
