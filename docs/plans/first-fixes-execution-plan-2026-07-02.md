# ExecuteSpec First-Fixes Execution Plan

Date: 2026-07-02
Source analysis: `/Users/navneetprabhakar/Documents/antigravity/executespec-cli/ANALYSIS.md`
Primary repos:

- Backend/web: `/Users/navneetprabhakar/Documents/antigravity/executespec`
- CLI/client: `/Users/navneetprabhakar/Documents/antigravity/executespec-cli`

## Scope

This plan covers the first fixes from the consolidated CLI + backend analysis:

1. Wire LLM-backed orchestrator routing as the primary intent path.
2. Stop plan approval from blocking pipeline pool threads.
3. Make the default chat experience honest and usable against the CLI default host.
4. Park local-hands runs on client disconnect instead of turning disconnects into tool failures.

The plan intentionally does not cover CLI renderer cleanup, npm/Homebrew release work, chat-stack
deletion, worker-loop consolidation, or planner-prompt pruning. Those become follow-up tracks after
the front-door and availability issues are contained.

## Non-Negotiables

- Preserve the existing deterministic route corpus as a safety regression gate.
- Keep cost-bearing or destructive actions as proposals that require confirmation.
- Keep WEB, CLI, and VS Code capability boundaries separate; do not expose local-hands actions to
  WEB sessions.
- Do not introduce direct provider calls. LLM routing must go through `LlmRouter`.
- Feature-flag the behavior changes so dev can exercise them before prod defaults move.
- Every API change must be additive unless the standalone CLI repo is changed in the same release
  window.

## Phase 0 - Baseline And Branching

Goal: start from a clean, measurable baseline before changing routing or run lifecycle behavior.

Backend actions:

- Create a backend branch such as `codex/first-fixes-router-gate-chat-local-hands`.
- Capture current test baseline:
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -DskipTests compile`
  - targeted tests for `agentsession`, `harness`, `tool/remote`, `api/controller` if full verify is
    too slow during development.
- Record current feature flag defaults for:
  - `spec.chat.enabled`
  - proposed `agent.intent.llm-routing.enabled`
  - proposed `run.plan-gate.async-resume.enabled`
  - proposed `worker.local-hands.disconnect-parking.enabled`

CLI actions:

- Create a matching branch in `executespec-cli` only when Phase 1 or Phase 3 needs client-side
  changes.
- Capture current CLI baseline:
  - `npm test`
  - `npm run typecheck` if present
  - the existing agentic/security QA suites only if they are already runnable locally.

Exit criteria:

- Backend compiles before edits.
- Existing deterministic routing corpus passes before edits.
- No unrelated dirty work is staged.

## Phase 1 - LLM Router First, Deterministic Router As Fallback

Problem:

The CLI currently routes natural-language turns through client regexes first, then the backend routes
through deterministic keyword patterns in `AgentIntentService`. The backend already has
`OrchestratorRoutingPromptCatalog` and `OrchestratorRoutingPromptEvalRunner`, but
`AgentIntentService.decide()` does not call them.

Backend files:

- `src/main/java/com/navneet/executespec/agentsession/AgentIntentService.java`
- `src/main/java/com/navneet/executespec/agentsession/OrchestratorRoutingPromptCatalog.java`
- `src/main/java/com/navneet/executespec/agentsession/OrchestratorRoutingPromptEvalRunner.java`
- `src/main/java/com/navneet/executespec/agentsession/AgentOrchestratorTurnService.java`
- `src/main/java/com/navneet/executespec/agentsession/AgentSessionService.java`
- `src/main/resources/prompts/orchestrator/*`
- `src/main/resources/application.yml`

Implementation:

- Introduce a production service, separate from the eval runner, for one-turn LLM routing:
  `OrchestratorRoutingService`.
- Inject `LlmRouter`, `ModelRegistry`, `ObjectMapper`, `OrchestratorRoutingPromptCatalog`, and
  `FeatureFlagService`.
- Route with LIGHTWEIGHT tier model for the effective provider.
- Feed only the minimum routing context:
  - user message
  - client surface and capabilities
  - project/run/session presence
  - changed-file summary when already provided
  - explicit user constraints such as "do not edit"
- Parse the strict JSON schema used by the eval runner.
- Convert the model decision into the existing `OrchestratorDecision` DTO.
- Preserve hard deterministic guards before the LLM:
  - unsafe/security reject
  - explicit run lifecycle commands with a run id
  - local tool action that the client must own
- Use deterministic routing as fallback when:
  - the feature flag is off
  - the LLM call fails
  - JSON is invalid
  - the route is missing, unknown, or violates policy
  - confidence is below the configured threshold
- Emit decision metadata:
  - `source=llm_router|deterministic_fallback|hard_guard`
  - prompt version
  - provider/model
  - fallback reason

Policy requirements:

- An LLM decision cannot downgrade `SECURITY_REJECT`.
- An LLM decision cannot turn an explicit "do not edit" turn into `CODE_RUN_REQUIRED`.
- Cost-bearing routes must keep `requiresUserConfirmation=true`.
- WEB sessions must not receive `LOCAL_TOOL_ACTION`.
- Local-only routes require CLI or VS Code capabilities.

Tests:

- Extend `AgentRoutingCorpusTest` so every corpus row runs through:
  - deterministic fallback mode
  - a fake LLM-router mode using canned valid JSON
  - invalid/malformed JSON fallback mode
- Add tests for hard guard precedence:
  - unsafe request rejected even if fake LLM says run
  - "explain only" stays read-only even if fake LLM says implementation
  - WEB client cannot route to local tool action
  - low-confidence model output falls back deterministically
- Keep `OrchestratorRoutingPromptEvalRunnerTest` as the live-provider harness, not the primary CI
  gate.

CLI handoff:

- After backend routing is live on dev, demote CLI natural-language classification in
  `executespec-cli`:
  - deterministic slash/command syntax stays local
  - local filesystem safety checks stay local
  - ambiguous natural-language turns go to the backend first
  - offline fallback remains explicit, not silent
- Add CLI regression cases for near-identical sentences that currently misroute.

Exit criteria:

- Backend corpus passes with the feature flag off and on.
- A fake LLM route can drive the existing action-card and confirmation flow.
- Live dev smoke with one provider shows route metadata in the stream without exposing provider
  internals to normal UI copy.

## Phase 2 - Release Pipeline Threads Across Plan Approval

Problem:

`RunOrchestrator` submits the whole pipeline to `PipelineExecutorPool` and then calls
`gateStore.awaitPlanGate(runId)` inline. A PRODUCTION run at `AWAITING_APPROVAL` holds one fixed-pool
thread for up to 24 hours. Enough parked gates exhaust the pool and prevent new runs from planning.

Backend files:

- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`
- `src/main/java/com/navneet/executespec/api/controller/RunController.java`
- `src/main/java/com/navneet/executespec/harness/GateStore.java`
- `src/main/java/com/navneet/executespec/integration/PipelineExecutorPool.java`
- `src/main/java/com/navneet/executespec/state/StateStore.java`
- `src/main/java/com/navneet/executespec/integration/RunPersistenceWriter.java`

Implementation:

- Split orchestration into two explicit phases:
  - planning phase: classify, plan, persist plan/tasks/context, estimate, open gate, emit
    `GATE_OPENED`, then return from the pool thread.
  - execution phase: rehydrate run plan/context and continue from `WORKING` after approval.
- Add a method such as `RunOrchestrator.continueApprovedRun(runId, approvalContext)` that submits
  execution work onto `PipelineExecutorPool`.
- Change `POST /api/runs/{id}/approve`:
  - atomically claim the gate through `GateStore.tryClaimPlanGate`
  - persist approval decision
  - charge/confirm credits as today
  - enqueue `continueApprovedRun` when approved
  - cancel cleanly when rejected
- Stop using `GateStore.isPlanGateWaiting()` as a failure condition for approval. In the new model,
  the normal state is "no thread waiting."
- Keep legacy behavior behind a feature flag during rollout:
  - flag off: current blocking path
  - flag on: async approval-resume path
- Ensure server restart semantics improve:
  - if a run is `AWAITING_APPROVAL`, approval after restart should enqueue continuation, not fail
  - if required workspace/plan state is missing, fail with a precise retryable reason

State and idempotency:

- Approval must be single-winner. Concurrent approvals still return one success and one 409.
- Repeated approval after execution has started returns 409.
- Rejection after approval has already started returns 409.
- Cancelling an awaiting run clears the gate and prevents later continuation.

Tests:

- Unit/integration tests around `RunController.approve`:
  - approval when no pipeline thread is waiting enqueues continuation
  - approval after restart-like state does not fail solely because `isPlanGateWaiting=false`
  - concurrent approvals preserve single-winner behavior
  - rejection terminates without enqueueing execution
  - cancellation prevents later approval
- Orchestrator test:
  - planning phase returns after opening gate
  - pool thread is not held while status is `AWAITING_APPROVAL`
- Add a targeted stress-style test if practical:
  - submit more awaiting runs than pool size
  - verify an additional run can still enter planning

Exit criteria:

- No fixed pool thread waits on a human approval.
- Existing plan-gate UI and SSE behavior remains compatible.
- Approval after same-JVM wait, no-wait, and restart-like cases behaves deterministically.

## Phase 3 - Make Chat Default Honest

Problem:

The CLI's default experience opens chat, but the backend default host currently returns a polite
`spec.chat.enabled is OFF` done event. That makes the flagship zero-config path fail at the first
turn.

Backend files:

- `src/main/java/com/navneet/executespec/specchat/SpecChatController.java`
- `src/main/java/com/navneet/executespec/agentsession/AgentStreamTurnRunner.java`
- `src/main/resources/application.yml`
- feature flag seed migration if defaults are DB-backed

Implementation options:

- Preferred backend product fix:
  - enable `spec.chat.enabled` on the default dev host only after managed-AI and per-turn billing
    caps are confirmed for chat turns
  - keep prod rollout separate if prod host is not ready
- Required API honesty fix regardless of flag default:
  - expose a cheap capability endpoint or reuse an existing capability response so CLI can know
    whether chat is enabled before launching the chat TUI
  - include the preferred replacement action when disabled, such as "run submit" or web login
  - avoid making users discover disabled chat only after typing a prompt

Server behavior:

- `POST /api/agent/sessions/{id}/turns/stream` and `/api/spec/chat` must return consistent disabled
  semantics.
- Disabled response should be machine-readable:
  - `enabled=false`
  - `reason=FEATURE_DISABLED`
  - `fallbackActions=[...]`
- Enabled response must stay under managed-AI preauthorization.

CLI handoff:

- On startup, probe chat capability for the selected host.
- If disabled:
  - do not launch the full chat TUI as the primary surface
  - show a concise launcher with working commands
  - allow `--force-chat` only for debugging, if useful
- If enabled:
  - launch chat as today.

Tests:

- Backend tests for consistent disabled envelope on both chat endpoints.
- Managed-AI exhausted/disabled tests for enabled chat path.
- CLI tests for capability probe:
  - chat enabled launches chat
  - chat disabled shows actionable fallback
  - host override probes the selected host, not stale config

Exit criteria:

- Bare `executespec` against the default host either opens working chat or clearly routes the user to
  a working command before they type.
- The backend no longer has two different disabled-chat contracts.

## Phase 4 - Park Local-Hands Runs On Disconnect

Problem:

`RemoteToolDispatcher.unregister(runId)` completes pending tool futures with an INTERNAL error. The
worker sees a tool failure and the run likely fails. A CLI laptop sleep or network drop should park
the run and allow `resume-local`, not destroy the run.

Backend files:

- `src/main/java/com/navneet/executespec/tool/remote/RemoteToolDispatcher.java`
- `src/main/java/com/navneet/executespec/tool/remote/RemoteToolController.java`
- `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`
- `src/main/java/com/navneet/executespec/engine/WorkerAgent.java`
- `src/main/java/com/navneet/executespec/state/StateStore.java`
- run status enum/DTO surfaces if centralized
- migration if a new durable status or reconnect token is required

Implementation:

- Introduce a distinct remote disconnect result, not an ordinary tool error:
  - exception type such as `RemoteClientDisconnectedException`, or
  - structured envelope kind such as `CLIENT_DISCONNECTED` that worker/orchestrator treats
    specially.
- Add a durable run state:
  - preferred: `AWAITING_CLIENT`
  - include `awaitingClientSince`, reconnect deadline, and last pending tool call metadata if needed
- On client disconnect:
  - mark the run `AWAITING_CLIENT`
  - persist enough execution context for resume
  - emit typed SSE event
  - stop spending LLM/tool iterations
  - do not mark the run `FAILED`
- On client reconnect/resume:
  - verify the same user/org/run authorization
  - re-register the remote dispatcher session
  - continue from the last durable worker checkpoint if available
  - if exact in-flight tool replay is unsafe, rerun the current worker turn with clear context that
    the prior local tool call was interrupted before a result returned
- On reconnect window expiry:
  - fail or cancel with `CLIENT_DISCONNECTED_TIMEOUT`, not `INTERNAL`

CLI handoff:

- Ensure `resume-local` calls the server-side resume endpoint or handshake that leaves the run in
  `AWAITING_CLIENT` and rebinds local tools.
- CLI must distinguish:
  - user cancelled intentionally
  - network/client disconnect
  - server timed out waiting for reconnect

Tests:

- `RemoteToolDispatcherTest`:
  - unregister during pending call yields disconnect signal, not INTERNAL tool failure
  - timeout remains timeout
  - normal complete remains unchanged
- Orchestrator/worker test:
  - disconnect transitions local-hands run to `AWAITING_CLIENT`
  - reconnect resumes or requeues the interrupted turn
  - reconnect after deadline fails with explicit reason
- CLI e2e/manual smoke:
  - start local run
  - kill client during a pending tool call
  - verify backend status is `AWAITING_CLIENT`
  - run `resume-local`
  - verify run continues without duplicate destructive local action.

Exit criteria:

- A local-hands client disconnect no longer appears as a generic tool failure.
- The user has a documented resume path that matches the CLI command.
- Terminal-Bench remote-worker use is checked separately because it also uses `RemoteToolDispatcher`.

## Phase 5 - Documentation, Rollout, And Verification

Backend docs to update before commit:

- Root `CLAUDE.md` / `AGENTS.md` if the execution model, flags, statuses, or API contracts change.
- `src/main/java/com/navneet/executespec/agentsession/CLAUDE.md` for router behavior.
- `src/main/java/com/navneet/executespec/harness/CLAUDE.md` for non-blocking plan gate behavior.
- `src/main/java/com/navneet/executespec/terminalbench/CLAUDE.md` if remote dispatcher semantics
  change for Terminal-Bench.
- OpenAPI docs if chat capability, approval, or local-hands resume contracts change.
- Reason log for the decision and rollout state.

Verification matrix:

- Backend:
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -DskipTests compile`
  - targeted `agentsession` tests
  - targeted `GateStore` / `RunController` / `RunOrchestrator` tests
  - targeted `RemoteToolDispatcher` tests
  - full `mvn verify -q` before merge candidate
- Frontend/backend smoke:
  - web agent read-only turn
  - web agent run proposal
  - plan approval from web
  - run detail SSE after approval
- CLI smoke in `executespec-cli`:
  - bare `executespec` against default host
  - natural-language route that should stay read-only
  - natural-language route that should create a run proposal
  - disabled-chat host behavior
  - local-hands disconnect/resume

Rollout:

- Ship flags default OFF except chat-capability envelope.
- Enable LLM routing on dev for one provider first.
- Enable async plan-gate resume on dev and run a small approval-load smoke.
- Enable chat on dev only if managed-AI caps are confirmed.
- Enable disconnect parking only after a Terminal-Bench regression check.
- Promote each flag independently. Do not bundle all four into one irreversible release switch.

## Delivery Order

1. Phase 2 first if availability risk is urgent. It is mostly backend-contained and removes the
   fixed-pool cliff.
2. Phase 1 next because it fixes the product front door and gives the CLI a cleaner contract.
3. Phase 3 immediately after Phase 1 so the default CLI path stops lying.
4. Phase 4 last among the first fixes because resume semantics touch worker control flow and
   Terminal-Bench remote execution; it needs more careful regression coverage.

If only one workstream can be active, do Phase 2 before Phase 1. A bad router produces ugly UX; a
blocked pipeline pool produces an availability incident.
