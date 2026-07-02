# Prompt Audit Fixes Execution Doc - 2026-07-02

Source audit:
`docs/plans/prompt-audit-and-tb21-improvement-plan-2026-07-02.md`

Goal: implement the audit fixes before the TB 2.1 internal run, then verify locally. This document
is the execution checklist, not a second audit. The benchmark remains internal-only: TB v2.1, k=1,
no submission, tracked through `/benchmark`.

Current implementation status: H1-H6, TB1-TB3, and M1-M15 are implemented in
`codex/benchmark-quality-unified-codegen`. Dead prompt archives with no runtime loader were removed
after reference checks.

## Non-negotiables

- Keep one code generation path: BUILD/PATCH/benchmark paths may wrap or skip post-codegen phases,
  but benchmark code generation must continue through `CodeGenEngine` or the shared `WorkerAgent`
  surface documented for Terminal-Bench.
- Do not add direct provider calls. LLM calls still route through `LlmRouter`.
- Do not broaden the TB model matrix. Approved TB 2.1 internal matrix:
  - `deepseek:deepseek-v4-pro-native`
  - `deepseek:deepseek-v4-flash-native`
  - `anthropic:claude-sonnet-5`
  - `anthropic:claude-sonnet-4-6`
  - `openrouter:glm-5.2`
- Keep TB at k=1 unless a later, explicit campaign overrides the runner guard.
- Prompt-visible behavior changes need targeted tests and a local server smoke before TB spend.

## Phase 0 - Baseline

Actions:

- Record dirty files before editing.
- Run the current focused gates after each batch:
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q compile`
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q spotless:check`
  - focused backend tests for touched packages
  - `npm --prefix frontend run format:check`
  - `npm --prefix frontend run build`

Exit criteria:

- Current branch remains `codex/benchmark-quality-unified-codegen`.
- Existing benchmark/UI changes remain intact.

## Phase 1 - High Severity Prompt/Caller Contract Fixes

Status: complete.

### H1 - Planner classification uses the wrong vocabulary

Implementation:

- Add a strategy-cap adapter that maps `PlanStrategy` to the prompt-facing classification and
  max-task cap.
- Thread the effective `PlanStrategy` into `PlannerRequest`.
- Keep the legacy `Classification` field only where old sub-plan expansion deliberately caps
  EPIC splits.
- Make `PlannerOutputUtils` truncate against the strategy-derived cap when present.
- Align classifier/planner band constants and tests.

Acceptance:

- Unit test proving a `PlanStrategy.MEDIUM` plan can retain more than 4 tasks.
- Unit test proving legacy EPIC sub-plan cap remains bounded.

### H2 - Shared fragments are orphaned

Implementation:

- Inventory orphaned fragments and classify each as worker-live, scaffold-only, or delete.
- Reinject only project-type-appropriate worker fragments through `SequentialExecutor`.
- Do not dump all fragments into every worker prompt.
- Merge `format-after-edit.md` into the existing format discipline and include Rust `cargo fmt`.
- Resolve CORS policy duplication before reusing `cors-config.md`.

Acceptance:

- Unit test for a Node/fullstack project prompt containing API URL/router/module guidance.
- Unit test proving unrelated project types do not get irrelevant web-stack fragments.

### H3 - Test planner to test worker method handoff is broken

Implementation:

- Load `TestPlan.planJson` when building test-worker prompts.
- Extract the specific task's planned `testMethods`.
- Serialize those methods into `{test_methods_json}`.
- Preserve API probe results during compile-error retry.

Acceptance:

- Unit test proving planned method names reach the test-worker prompt.
- Unit test proving retry prompt still includes API probe evidence.

### H4 - Validation report closing copy overclaims

Implementation:

- Remove mandatory "resolved" and "lint" claims from `validation-report.txt`.
- Make bottom-line copy conditional on actual check results and auto-fixed markers.
- Add explicit guidance for the injected Accuracy Assessment section.
- Replace impossible global 15-line limit with per-section budget.

Acceptance:

- Unit/golden test for unresolved issues not claiming resolution.
- Unit/golden test for no lint claim when lint was not run.

### H5 - PR review prompt advertises unavailable readFile tool

Implementation:

- Either bind a read-only `readFile` tool to `PrReviewAgent`, or remove the readFile instruction.
- Remove or implement stale retry javadoc.

Acceptance:

- Unit test proving only advertised tools are present in the prompt/tool list.

### H6 - Execution planner prompt/tool/dependency drift

Implementation:

- Remove phantom `submitExecutionPlan` tool-call instruction from `execution_planner.txt`.
- Either wire per-solution `dependsOn` into `dependencyEdges`, or update the prompt to require
  `dependencyEdges`.

Acceptance:

- Unit test: plan JSON with `dependsOn` persists dependency edges.
- Unit test: prompt no longer instructs a non-bound tool call.

## Phase 2 - Terminal-Bench Fixes Before TB 2.1

### TB1 - Provider fragments in terminal path

Status: complete.

Implementation:

- Add terminal-specific provider fragments under `prompts/fragments/provider/<provider>/terminal.md`
  for DeepSeek, Anthropic, and OpenRouter.
- Append them in `TerminalBenchRunService` behind the existing provider-tuning flag.
- Do not append PATCH fragments directly into TB.

Acceptance:

- Unit test proving DeepSeek TB prompt includes terminal-safe "tool call every turn" guidance.
- Unit test proving unknown provider falls back to base TB prompt.

### TB2 - Passing-candidate protection

Status: complete.

Implementation:

- Detect conservative verifier-shaped successful terminal checks in the shared `WorkerAgent`:
  `/tests/test.sh`, commands under `/tests/`, common test runners, explicit verify scripts, and
  benchmark commands.
- Require extracted deliverable paths to exist before accepting such a verifier-shaped pass.
- Snapshot extracted deliverables to `/tmp/es-passing-candidate-<runId>` on the remote container
  when possible.
- After a verifier-shaped pass, refuse later risky mutating/rerun tools and tell the worker to call
  `completeTask`.
- If the model responds with text only after such a pass, force-complete the worker result while
  preserving the verified candidate.

Acceptance:

- Unit test proves the verifier-shaped command detector accepts real test/benchmark commands and
  rejects exploration/trivial smoke commands.
- The behavior lives in `WorkerAgent`, not a separate Terminal-Bench codegen path.

### TB3 - Infra-fail visibility in benchmark tracking

Status: complete.

Implementation:

- Extend benchmark task result/event payloads to carry outcome/reason when the bridge can detect
  timeout/container/tool infrastructure failures.
- Keep resolved% based on reward, but make infra failures visible in `/benchmark`.

Acceptance:

- Backend tracking test proving outcome/reason persists and UI can render it.

## Phase 3 - Medium Severity Fixes

Status: complete.

Implement all live MEDIUM findings from the audit, grouped by blast radius:

- M1, M2, M3, M4, M5, M6: planner/reviewer schema and prompt drift.
- M7: compactor safety before flags are enabled.
- M8, M9: prompt-injection framing and destructive context-agent fallback.
- M10, M11: generated-test prompt/schema drift.
- M12: run-ledger and memory-compaction evidence/size handling.
- M13: band table unification with H1.
- M14: orchestrator routing prompt eval schema consistency.
- M15: code-review strict schema alignment.

Acceptance:

- Each fix has either a unit test, a prompt snapshot/golden test, or an explicit documented reason
  why it is text-only and covered by compile/static checks.

## Phase 4 - Dead Weight And Docs

Status: complete.

Implementation:

- Delete dead prompt resources only after confirming no Java/resource references.
- Remove or mark rollback-incompatible prompts.
- Externalize inline prompts opportunistically only when directly touched; otherwise document the
  remaining convention debt.
- Update root/package docs and reason log.

Acceptance:

- `rg` confirms deleted resources have no references.
- Docs list the new prompt routing contracts.

## Phase 5 - Local Verification

Status: complete for pre-benchmark readiness. Focused backend tests passed, Spotless passed, backend
compile passed, frontend format/build passed, and the local server/API smoke passed.

Backend:

- Start backend locally with `local` profile.
- Verify `/actuator/health`.
- Verify `/api/models/available` includes Sonnet 5 and the benchmark matrix model names.
- Verify benchmark tracking start/event/detail endpoints with a SUPER_ADMIN token.

Frontend:

- Start Vite or use the backend-served static bundle.
- Verify Compose route model picker is present for an Enterprise user.
- Verify Org model-matrix model picker is present.
- Verify `/benchmark` renders at least one locally seeded benchmark run with provider/model/k/cost
  and task outcome/reason.

Exit criteria:

- Local backend is running on `http://localhost:8080` with `/actuator/health` returning 200.
- Vite is running on `http://127.0.0.1:5173`.
- Authenticated `/api/org/me/model-matrix` confirms Sonnet 5 defaults and the approved TB model
  set is selectable.
- Authenticated `/api/v2/test/benchmark` start/event/detail flow was smoked with a synthetic
  local run, including persisted `outcome` and `outcomeReason`, then marked `DONE`.
- No real TB run has been started from this branch yet.
