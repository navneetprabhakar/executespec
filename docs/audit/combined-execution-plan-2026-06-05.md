# ExecuteSpec Combined Execution Plan - 2026-06-05

## Operating Rules

- Code, tests, migrations, configuration, and runnable scripts are authoritative.
  Existing docs are leads only.
- Before local app/browser work, check whether backend/frontend are already up.
- If backend must be started, use the local Spring profile:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local`.
- Default local ports:
  - backend: `http://localhost:8080`
  - frontend: `http://localhost:5173`
- Keep changes sliced. Do not mix prompt cleanup, provider behavior, scoring,
  and UX changes in the same implementation batch unless a test requires it.
- Worktree is already dirty. Do not revert unrelated user changes.

## Goal

Turn ExecuteSpec into a reliable non-frontier-model harness, with DeepSeek-class
models able to produce useful output through stronger prompt governance,
provider capability handling, platform-mediated verification, and honest UX
provenance.

## Execution Tracks

### Track A - Prompt Cleanup And Consolidation

Purpose: make the model instructions coherent before changing execution logic.

#### A0. Remove stale comments and stale prompt ground truth

Actions:

- Clean stale prompt-related comments in:
  - `OutlinePlannerAgent.java`
  - `Plan.java`
  - `PlannerOutputUtils.java`
  - `PromptResourceVerifier.java`
  - `SequentialExecutor.java` prompt/scaffold comments only.
- Move or clearly archive retired prompt files:
  - `planner.txt`
  - `planner.v1.txt` through `planner.v14.txt`
  - `triage.txt`
  - `reviewer-v2.txt`
- If moving archives risks `PromptVersioningService`, first add an explicit
  `prompts/archive/` convention and tests.

Acceptance:

- No active prompt or prompt-adjacent comment uses old planner concepts as live
  truth: `MULTI_TASK`, parallel tier execution, `owned_files`, `shared_files`,
  or V1 tool names.
- Retired prompts are visually impossible to confuse with active prompts.

#### A1. Fix active planner drift

Actions:

- Update `outline-planner.md` task-cap rules to current taxonomy:
  `SINGLE_SHOT`, `SMALL`, `MEDIUM`, `LARGE`, `LEGACY`.
- Align `outline-planner.md`, `outline-planner-skeleton.md`,
  `Plan.RuntimeSurface`, and `submitOutline` schema wording.
- Fix scaffold keying:
  - `java-spring-boot` must resolve to Spring Boot scaffold guidance.
  - decide whether `java`, `static-html`, `fullstack`, and `all` are supported
    projectType keys or archived templates.

Acceptance:

- A test proves every projectType the planner prompt advertises resolves to
  scaffold guidance or intentionally resolves to none.
- No active prompt asks for a value the parser/schema rejects or ignores.

#### A2. Combine shared prompt rules through fragments

Actions:

- Promote reusable live fragments:
  - `api-url-rules.md`
  - `backend-robustness.md`
  - `module-system.md`
  - `preview-defaults.md`
  - `router-isolation.md`
  - `security-rules-planner.md`
  - `security-rules-worker.md`
  - `format-after-edit.md`
- Use `PromptAssembler` for active prompts that need fragments.
- Remove duplicated hardcoded text where a fragment becomes authoritative.

Acceptance:

- Shared rules are defined once.
- Active planner and worker prompts receive the relevant shared rules.
- A test assembles active prompts and fails on unresolved `{{...}}` placeholders.

#### A3. Normalize scaffold prompts

Actions:

- Rewrite active scaffold prompts as worker-facing constraints, not old task
  JSON snippets.
- Remove stale fields like `task_id`, `task_layer`, `depends_on`,
  `assigned_model`, `owned_files`, `shared_files`.
- Split volatile scaffold guidance from stable rules. Example: React Native/Expo
  SDK version should be validated dynamically or clearly marked as checked at
  implementation time.

Acceptance:

- Scaffold blocks read like constraints the worker can follow inside a current
  `PlannedTask`.
- No scaffold prompt contains obsolete task schema.

#### A4. Fix reviewer/test evidence prompt

Actions:

- Update `reviewer-general.md` so missing mandatory tests and missing
  verification are in scope when production code changed.
- Keep style-only changes out of scope.
- Make no-op/fallback verification visible in reviewer summaries.

Acceptance:

- Reviewer prompt no longer conflicts with worker tests-are-mandatory rule.
- Tests pin reviewer verdict parsing.

#### A5. Prompt conformance tests

Actions:

- Add tests for:
  - all critical prompt resources load;
  - active prompts assemble without unresolved placeholders;
  - planner enum/schema terms match Java enums;
  - scaffold keys match advertised projectTypes;
  - reviewer verdict header contract remains parseable.

Acceptance:

- Prompt drift breaks CI before it reaches a run.

#### A6. Add provider/model prompt variant resolution

Actions:

- Add a `PromptVariantResolver` or equivalent prompt assembly seam with this
  input shape:
  `agent + phase/mode + resolvedProvider + resolvedModel + projectType`.
- Compose prompts in deterministic order:
  1. agent/mode base prompt;
  2. shared fragments;
  3. provider fragment;
  4. model-family fragment;
  5. exact-model fragment, when present.
- Resolve prompt variants from the **actual dispatched provider/model**, not the
  requested provider. In BUILD mode this means resolving `TierConfig` before
  composing the worker system prompt.
- Treat OpenRouter as a gateway, not a model family:
  - `provider-openrouter.md` contains only gateway/wire constraints;
  - underlying model behavior comes from model-family/exact-model fragments such
    as `model-family-deepseek.md`, `model-qwen3-coder.md`,
    `model-kimi-k2.6.md`, and `model-deepseek-v4-flash.md`.
- Wire the resolver into:
  - BUILD worker prompts;
  - PATCH/targeted-fix prompts;
  - reviewer prompts where provider behavior affects tool use;
  - planner prompts where model-specific schema/tool discipline is needed;
  - compactor prompts if provider/model context handling differs.

Acceptance:

- OpenRouter runs never receive only a generic OpenRouter prompt; they also
  receive the underlying model-family or exact-model prompt.
- Cross-provider fallback/adaptive routing cannot dispatch with a prompt tuned
  for the original requested provider.
- Tests prove prompt resolution for Anthropic, OpenAI, Gemini, native DeepSeek,
  OpenRouter DeepSeek, OpenRouter Qwen, and OpenRouter Kimi.

### Track B - DeepSeek And Provider Correctness

Purpose: make provider support real rather than enum/config-deep.

Actions:

- Add DeepSeek BYOK and availability end to end:
  - org/user key storage where appropriate;
  - DTOs;
  - key resolver;
  - `RunKeyContext`;
  - provider availability;
  - org settings UI;
  - Compose provider picker.
- Add provider/model capability metadata:
  - tool calls;
  - `tool_choice=auto`;
  - `tool_choice=required`;
  - thinking-mode restrictions;
  - reasoning-content echo;
  - cache controls;
  - max safe output;
  - parallel tool calls.
- Replace provider behavior string matching with capability checks.
- Fix native DeepSeek salvage behavior according to capabilities.
- Store prompt-variant metadata alongside provider/model capabilities:
  - provider fragment key;
  - model-family fragment key;
  - exact-model fragment key;
  - prompt compatibility notes for aggregator routes.
- Persist salvage/provider behavior telemetry.

Acceptance:

- DeepSeek appears only when usable.
- Native DeepSeek request-body tests prove whether `tool_choice` is present or
  omitted per model capability.
- OpenRouter model routing proves gateway behavior and underlying model prompt
  behavior are both applied.
- A provider conformance test exercises plan/read/edit/build/complete for each
  supported provider.

### Track C - Verification Harness

Purpose: make correctness measured by the platform, not claimed by the model.

Actions:

- Expand BUILD held-out eval corpus to at least 40 cases:
  - 10 LOW;
  - 15 MEDIUM;
  - 10 HIGH;
  - 5 existing-repo edit cases.
- Make BUILD eval a release gate across Anthropic/OpenAI/Gemini/DeepSeek and
  selected OpenRouter models.
- Capture:
  - held-out pass rate;
  - terminal status;
  - build pass rate;
  - zero-edit rate;
  - text-only stop rate;
  - salvage success rate;
  - cost;
  - latency;
  - token/context size.
- Add platform-mediated build/test checkpoints after edits.
- Persist build/test command source and no-op/fallback status.

Acceptance:

- `verifiedScore` is secondary to held-out pass rate for harness changes.
- A no-op build/test cannot display as plain green.

### Track D - Non-Frontier Execution Mode

Purpose: give DeepSeek-like models a smaller, stricter, more deterministic path.

Actions:

- Define a non-frontier preset:
  - provider prompt tuning ON;
  - tool output compaction ON;
  - history compaction ON;
  - smaller task/context budgets;
  - stricter no-edit/no-tool telemetry;
  - platform verification loop ON.
- Reduce standing context and retrieve task-local context.
- Tighten completion semantics for weak models:
  - `completeTask` requires edits unless task is read-only;
  - build/test verification must be recent or platform-proven;
  - zero-edit salvage outcome is explicit.
- Reframe PATCH mode as a governed targeted-fix execution strategy:
  - keep the thin single-agent path for failing-test/autofix/benchmark shapes;
  - stop treating it as a separate prompt island;
  - route it through `PromptVariantResolver`;
  - route verification through an explicit `VerificationPolicy`;
  - route provider behavior through provider/model capabilities;
  - expose it through `CodeGenEngine` as `TARGETED_PATCH` or equivalent.
- Preserve source-specific verification semantics as policy, not hardcoded mode
  drift:
  - `finding:*` requires recent passing tests when a failing test/repro is the
    spec;
  - blind benchmark sources cannot require hidden tests before completion;
  - customer issue-only PATCH runs must still produce platform-visible evidence.

Acceptance:

- DeepSeek-class runs have lower no-op and text-only stop rates in eval.
- Context size and cache behavior are measurable per task.
- PATCH/targeted-fix runs use the same prompt variant, provider capability, and
  verification provenance mechanisms as BUILD runs.

### Track E - Product And UX Readiness

Purpose: make the app honest about what happened and why users should trust it.

Actions:

- Add verified-score provenance UI:
  - slot weights;
  - measured vs LLM-judged;
  - build/test command source;
  - fallback/no-op labels;
  - provider/model/flag snapshot.
- Promote evidence-first result UX:
  - build result;
  - test result;
  - endpoint coverage;
  - reviewer unresolved issues;
  - PR/diff summary.
- Clean Compose provider controls:
  - show only usable providers;
  - explain BYOK/Enterprise gating;
  - show DeepSeek/OpenRouter availability correctly.
- Decide whether Console chrome should become default after evidence surfaces
  are ready.

Acceptance:

- User can tell whether a run was deterministically verified, partially
  inferred, or mostly unmeasured.
- Provider selection cannot advertise unsupported paths.

## First Implementation Batch

Start here.

1. Patch `AGENTS.md` local-service instructions.
2. Fix `outline-planner.md` taxonomy and schema wording.
3. Fix scaffold key resolution for `java-spring-boot`.
4. Update `reviewer-general.md` test/verification scope.
5. Add prompt/scaffold conformance tests for those fixes.
6. Run targeted tests only:
   - prompt resource/conformance tests;
   - planner output/schema tests;
   - reviewer verdict parsing tests if touched.

Do not start the backend/frontend unless a test or UI verification requires it.
If UI verification is needed, check existing services first and start backend
with the `local` Spring profile only.

## Definition Of Done For Batch 1

- Active prompt drift fixed.
- Spring Boot scaffold injection is reachable for `java-spring-boot`.
- Reviewer no longer ignores missing mandatory tests.
- Tests pin the fixed contracts.
- No unrelated provider/scoring/UX behavior changes are included.

## Second Implementation Batch

Start after Batch 1 prompt drift is under control.

1. Add `PromptVariantResolver`.
2. Move BUILD worker provider-fragment injection to use resolved
   `TierConfig.provider` and `TierConfig.model`.
3. Split OpenRouter prompt behavior into gateway + underlying model-family
   fragments.
4. Add native DeepSeek, OpenRouter DeepSeek, OpenRouter Qwen, and OpenRouter
   Kimi prompt-resolution tests.
5. Wire PATCH mode through the same resolver without changing its execution
   semantics.
6. Add a focused regression test proving PATCH no longer uses only the generic
   `patch-agent.md` prompt for DeepSeek/OpenRouter models.

## Definition Of Done For Batch 2

- Provider/model prompt resolution is centralized.
- OpenRouter aggregator models receive model-level prompt tuning.
- PATCH remains available for targeted fixes, but no longer has a separate
  provider/model prompt path.
- Existing BUILD and PATCH tests pass, plus new prompt-resolution tests.
