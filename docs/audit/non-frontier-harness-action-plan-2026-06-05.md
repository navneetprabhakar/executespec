# ExecuteSpec Non-Frontier Harness Action Plan - 2026-06-05

## Method

This plan is based on the code-first audit discussion. Generated docs, prior planning docs, and reason logs are treated as non-authoritative. They can point to code paths, but implementation, tests, migrations, config, frontend code, and runnable scripts decide what is real.

## Priority 0 - Clean The Ground Truth

### 1. Remove or correct unnecessary/stale comments

Goal: make the codebase stop telling operators the wrong story.

Actions:

- Sweep high-risk execution files for stale or overconfident comments:
  - `RunOrchestrator.java`
  - `SequentialExecutor.java`
  - `WorkerAgent.java`
  - `LlmRouter.java`
  - `OpenAiNativeToolClient.java`
  - `RunContextComposer.java`
  - `ValidationReportService.java`
  - `AccuracyAssessmentService.java`
  - `BenchmarkRunService.java`
  - frontend run/validation/org settings surfaces.
- Delete comments that restate code.
- Correct comments that claim features are missing when code now implements them.
- Mark default-off or experimental behavior explicitly where code exposes UI affordances.
- Add a small ArchUnit/static test or `rg`-based CI check for known stale phrases once cleaned.

Acceptance:

- No top-level comments claim plan gate, credits, GitHub push, or provider routing are missing when implemented.
- No DeepSeek comment claims a mitigation works unless the wire request proves it.
- Comments describe current behavior, not intended roadmap.

## Priority 1 - Make DeepSeek A Real Provider

### 2. Add DeepSeek BYOK and availability end to end

Actions:

- Add encrypted `deepseek_api_key` to user/org storage as needed.
- Add Flyway migration.
- Extend DTOs:
  - `OrgDto`
  - org key update request/response
  - any user key DTOs if personal keys are supported.
- Extend services:
  - `OrgService.updateAiKeySettings`
  - `ApiKeyResolver`
  - `ResolvedKeys`
  - `RunKeyContext`
  - `RunKeyContextStore`
  - `AvailableProviderResolver`.
- Add org settings UI key row.
- Add Compose provider picker support when DeepSeek is available.
- Add access/entitlement rules for Enterprise/BYOK.

Acceptance:

- An Enterprise org can save a DeepSeek key.
- A run using provider `DEEPSEEK` resolves the org/user DeepSeek key, not platform fallback unless configured.
- DeepSeek appears only when usable.
- Tests prove unavailable DeepSeek is hidden or rejected cleanly.

### 3. Build provider capability metadata

Actions:

- Create provider/model capability metadata in config or registry:
  - supports tool calls
  - supports `tool_choice=auto`
  - supports `tool_choice=required`
  - rejects tool choice in thinking mode
  - requires reasoning-content echo
  - supports cache controls
  - max safe output tokens
  - supports parallel tool calls
  - supports strict JSON/schema mode.
- Refactor OpenAI-wire clients to consult capabilities instead of string-matching base URLs.
- Add explicit tests for OpenAI, OpenRouter, DeepSeek flash, DeepSeek pro/thinking behavior.

Acceptance:

- No `baseUrl.contains("deepseek")` logic decides core tool behavior.
- Native DeepSeek request bodies are tested at wire shape level.
- Forced salvage sends `tool_choice=required` only when supported.

### 3a. Add provider/model prompt variants

Goal: separate provider transport behavior from model behavior, especially for
OpenRouter.

Actions:

- Add a central prompt resolver keyed by:
  `agent + execution mode + resolved provider + resolved model`.
- Compose prompt fragments in this order:
  - shared base;
  - provider/gateway fragment;
  - model-family fragment;
  - exact-model fragment.
- Keep `provider-openrouter.md` focused on gateway/wire behavior only.
- Add model-family/exact-model fragments for OpenRouter-routed models:
  - DeepSeek;
  - Qwen;
  - Kimi;
  - any other model family added to the OpenRouter allow-list.
- Fix BUILD worker prompt composition so it uses the actual resolved
  provider/model, not the originally requested provider.
- Wire PATCH mode through the same resolver.

Acceptance:

- OpenRouter DeepSeek does not receive the same prompt as OpenRouter Kimi or
  OpenRouter Qwen.
- Cross-provider fallback cannot run with the wrong provider/model prompt.
- Prompt-resolution tests cover native DeepSeek plus OpenRouter DeepSeek, Qwen,
  and Kimi.

### 4. Fix native DeepSeek salvage behavior

Actions:

- Reconcile `WorkerAgent` forced salvage with `OpenAiNativeToolClient` request construction.
- If DeepSeek flash supports required tool choice, send it.
- If DeepSeek thinking rejects all tool choice, suppress it only for that model.
- Persist salvage attempt metadata:
  - attempted
  - forced
  - provider accepted/rejected
  - emitted tool call
  - produced edit
  - fallback attempt used.

Acceptance:

- A unit/wire test proves the actual native DeepSeek request includes or omits `tool_choice` based on model capability.
- A worker test proves fallback salvage still runs if forced salvage is rejected.

## Priority 2 - Build The Truth Harness

### 5. Expand held-out BUILD eval corpus

Actions:

- Grow `scripts/build-eval-cases` from 1 sample to at least 40 cases:
  - 10 LOW
  - 15 MEDIUM
  - 10 HIGH
  - 5 existing-repo edit cases.
- Cover:
  - Python/FastAPI
  - Java/Spring
  - Node/Express
  - React/Vite
  - CLI/library utilities
  - multi-repo workflows.
- Each case must include:
  - `spec.md`
  - `band`
  - hidden `acceptance/run.sh`
  - expected runtime/build assumptions
  - parser producing pass/total
  - clear timeout.

Acceptance:

- A local command produces `BUILD-resolved: passed/total`.
- No worker can see acceptance tests.
- The corpus catches compile-only and self-test-only false positives.

### 6. Make BUILD eval a release gate

Actions:

- Add a fixed provider/model matrix:
  - Anthropic baseline
  - OpenAI baseline
  - Gemini baseline
  - DeepSeek flash/native
  - selected OpenRouter models.
- Capture:
  - held-out pass rate
  - terminal status
  - build pass rate
  - zero-edit rate
  - text-only stop rate
  - salvage success rate
  - cost
  - latency
  - token counts
  - context bytes.
- Add minimum gate thresholds by band and provider.

Acceptance:

- No provider/model or harness flag change ships without eval results.
- `verifiedScore` is reported as secondary; held-out score is primary.

### 7. Add provider conformance tests

Actions:

- For every supported provider, run a tiny deterministic smoke:
  - plan
  - read file
  - edit file
  - run build
  - complete task
  - produce cost
  - generate validation.
- Include BYOK and platform-key variants where applicable.
- Include provider unavailable/invalid-key behavior.

Acceptance:

- Anthropic/OpenAI/Gemini/OpenRouter/DeepSeek have explicit conformance results.
- Provider support cannot regress silently.

## Priority 3 - Verification Over Voluntary Behavior

### 8. Add platform-mediated build/test checkpoints

Actions:

- After worker edits, platform runs resolver-derived build/tests automatically.
- Feed compact structured failures back to worker:
  - command
  - exit code
  - top error
  - failing test names
  - likely file references.
- Keep retry budget finite by complexity/provider.
- Avoid exposing a broad `runWorkspaceTests` tool to weak models by default.

Acceptance:

- DeepSeek does not need to decide to run tests; the platform does it.
- Worker receives actionable failure context and can retry.
- Test failures become task/run truth, not only scoring-time signals.

### 9. Make no-op and fallback verification visible

Actions:

- Persist build command source:
  - planner-declared
  - inferred framework
  - fallback no-op.
- Persist test command source:
  - pytest
  - npm
  - maven
  - gradle
  - cargo
  - go
  - none
  - unparseable.
- Add API fields and UI badges for fallback/no-op.

Acceptance:

- A no-op build can never be displayed as a plain green build.
- Users can tell whether tests were real, self-authored, absent, or unparseable.

### 10. Introduce task-level acceptance contracts

Actions:

- Planner emits stable requirement IDs and task postconditions.
- Each task declares:
  - requirement IDs covered
  - expected touched files
  - verification command or platform check
  - completion condition.
- Validator checks coverage before approval.
- Executor records which postconditions were verified.

Acceptance:

- A run can show requirement coverage, not just task count.
- Production plans cannot silently accept uncovered requirements.

## Priority 4 - Non-Frontier Execution Mode

### 11. Create a non-frontier preset

Actions:

- Add preset config for DeepSeek-like models:
  - smaller tool list
  - provider fragment on
  - tiered codemap on after eval
  - stricter completion gate
  - platform-mediated verification
  - lower tolerance for text-only loops
  - compact failure summaries
  - smaller standing prompt context.

Acceptance:

- Selecting DeepSeek applies the preset unless explicitly overridden.
- The preset is visible in run metadata.

### 12. Reduce standing context and retrieve per task

Actions:

- Keep codemap/architecture injection small.
- Turn tiered codemap on only after A/B evidence.
- Move from lexical selector to semantic retrieval from `project_code_chunk`.
- Add a worker lookup tool for codemap/code chunks where needed.
- Persist injected context chunk IDs/byte counts.

Acceptance:

- DeepSeek receives relevant files/symbols without large static prefixes.
- Context-size regressions are visible in metrics.

### 13. Tighten completion semantics for weak models

Actions:

- Make text-only stop after edits a warning and structured retry, not soft success.
- Require at least one verified edit/build/test signal for terminal success on behavior-changing tasks.
- Keep environmental/verify-only tasks separately classified.

Acceptance:

- Zero-edit and text-only runs cannot look successful without explicit classification.
- Weak-model loopholes are visible and measurable.

## Priority 5 - Independent Verification And Scoring

### 14. Add verifier strategy by provider/risk

Actions:

- For DeepSeek-generated code, default verifier should not be DeepSeek.
- Order:
  - deterministic checks
  - held-out tests
  - build/boot
  - reviewer model only where needed
  - accuracy LLM as secondary narrative.
- Allow stronger verifier model for production runs.

Acceptance:

- Run report shows generator model and verifier model separately.
- DeepSeek does not self-certify quality.

### 15. Expose verified-score provenance

Actions:

- Add score breakdown fields:
  - code delivered
  - build
  - tests
  - held-out acceptance
  - reviewer
  - criticals
  - boot/preview.
- Add source tags:
  - deterministic
  - worker self-test
  - held-out
  - LLM judged
  - unmeasured
  - fallback/no-op.
- Update result UI and project run panels.

Acceptance:

- Users can see exactly why a score is high or low.
- Low-confidence high-score runs are marked "Needs human review".

### 16. Harden accuracy assessment

Actions:

- Resolve prompt contradiction around missing vs unknown.
- Make default report conservative, not optimistic.
- Separate LLM-judged accuracy from deterministic requirement checks.
- Do not synthesize critical issues from weak evidence without confidence labels.

Acceptance:

- Assessor failure cannot inflate confidence.
- Unknown evidence does not tank or over-credit the run.

## Priority 6 - Observability And Operator Controls

### 17. Persist failure taxonomy

Actions:

- Persist per run/task:
  - zero-write
  - text-only stop
  - no completeTask
  - stagnation forced
  - salvage attempted
  - salvage succeeded
  - forced tool choice accepted/rejected
  - stale-read edit failure
  - wrong-file edit signal
  - build command source
  - test command source
  - context bytes
  - retry count.

Acceptance:

- Dashboards can explain why DeepSeek failed, not just that it failed.

### 18. Add provider readiness dashboard

Actions:

- Slice by provider/model/flag set:
  - held-out pass rate
  - verified score distribution
  - zero-edit rate
  - build pass rate
  - test pass rate
  - salvage rate
  - average cost
  - p50/p95 latency
  - context size.

Acceptance:

- Operators can tell whether DeepSeek is improving or regressing.
- Flag flips require dashboard evidence.

### 19. Add reproducibility bundles

Actions:

- Store for each run:
  - model IDs by phase/task
  - provider capabilities
  - feature flags
  - prompt/template versions
  - tool schema versions
  - build/test commands
  - diff
  - event log
  - validation score inputs
  - held-out acceptance result if present.

Acceptance:

- Any suspicious run can be reproduced or audited without relying on memory/docs.

## Priority 7 - Prompt And Planner Discipline

### 20. Migrate prompt loading to `PromptAssembler`

Actions:

- Move important direct resource loads to `PromptAssembler`:
  - planner
  - reviewer
  - report generator
  - accuracy assessment
  - project/chat agents where practical.
- Version prompt templates.
- Log prompt version/hash in run metadata.

Acceptance:

- Prompt drift is auditable.
- Include/substitution behavior is consistent.

### 21. Harden planner validation

Actions:

- Make requirement coverage validation fail closed for production runs.
- Allow fallback only for prototype/eval modes with explicit status reason.
- Add tests for uncovered requirements, invalid dependencies, and impossible build commands.

Acceptance:

- Production runs cannot proceed with uncovered requirements unless explicitly revised/approved.

### 22. Tune reviewer scope

Actions:

- Reviewer should flag missing behavior tests when a task touches a function/endpoint/module and no deterministic tests exist.
- Reviewer should distinguish:
  - code quality issues
  - requirement gaps
  - verification gaps
  - confidence gaps.

Acceptance:

- "Reviewer clean" does not imply "tests/acceptance proven" unless those checks ran.

## Priority 8 - Product UX Cleanup

### 23. Convert result UI to evidence-first scorecard

Actions:

- Add score slots and confidence labels.
- Show generator/verifier model split.
- Show commands and sources.
- Show held-out acceptance if present.
- Move narrative report below structured evidence.

Acceptance:

- A customer can understand trust level without reading logs.

### 24. Clarify Compose controls

Actions:

- Explain TDD mode precisely or hide it when in-loop test execution is disabled.
- Show provider availability from backend, not hardcoded list.
- Show non-frontier preset when selected.
- Show project/code-intel context status before submit.

Acceptance:

- UI cannot offer a provider/mode the backend cannot run correctly.

### 25. Promote Console when mission-control surfaces are ready

Actions:

- Console should become default only when it shows:
  - plan gate
  - run health
  - score provenance
  - provider readiness
  - failure taxonomy.

Acceptance:

- The web product reads as control plane, while VS Code owns the IDE metaphor.

## Priority 9 - Documentation Last

### 26. Rewrite docs only after implementation truth changes

Actions:

- Remove or rewrite generated docs that overstate readiness.
- Keep a single source-of-truth provider support matrix.
- Keep a single source-of-truth feature flag table.
- Add "known limitations" for DeepSeek/non-frontier mode until eval gates pass.

Acceptance:

- Docs describe code and eval evidence; they do not lead it.

## Suggested Execution Order

1. Comment/doc drift cleanup in core code.
2. DeepSeek end-to-end provider wiring.
3. Provider capability matrix and DeepSeek `tool_choice` fix.
4. 20-case BUILD eval smoke corpus.
5. Provider conformance tests.
6. API/UI score provenance.
7. Platform-mediated verification loop.
8. Non-frontier preset.
9. Failure taxonomy persistence.
10. Provider readiness dashboard.
11. Task-level acceptance contracts.
12. Semantic per-task retrieval.
13. Independent verifier strategy.
14. PromptAssembler migration.
15. Planner/reviewer hardening.
16. Evidence-first UX.
17. Console promotion.
18. Documentation rewrite.

## First Sprint Candidate

Scope: 1 week, code-first.

Deliverables:

- Clean stale comments in core execution/provider/scoring files.
- Add DeepSeek key fields/migration/resolver path.
- Add DeepSeek org settings row and Compose provider availability.
- Add provider capability metadata skeleton.
- Add native DeepSeek wire tests for `tool_choice`.
- Add 5 more BUILD eval cases.

Exit criteria:

- DeepSeek can be selected and run through the normal UI on a configured Enterprise org.
- The forced-salvage behavior is truthful at the wire level.
- A small hidden-acceptance eval produces a real pass/total score.
