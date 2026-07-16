# Subagent eligibility → execution seam audit

Date: 2026-07-12  
Scope: backend worktree `executespec-2.0`  
Result: no product-code change required

## Finding

Accepted eligibility can already trigger bounded persisted SCOUT/VERIFIER execution, but only through the explicit coordinator path. The eligibility endpoint remains side-effect free.

Evidence:

- `SubagentExecutionService.evaluateDelegation(...)` delegates to `SubagentProtocolService.evaluateDelegation(...)`, which is `@Transactional(readOnly = true)` and creates no child session or provider call (`src/main/java/com/navneet/executespec/agentsession/SubagentExecutionService.java:66-73`; `src/main/java/com/navneet/executespec/agentsession/subagent/SubagentProtocolService.java:85-130`).
- `POST /api/agent/sessions/{sessionId}/subagents/eligibility` only returns the deterministic decision (`AgentSessionController.java:246-260`). It does not reserve, start, or execute a child.
- `POST /api/agent/sessions/{sessionId}/subagents/coordinator` is the explicit execution hook (`AgentSessionController.java:231-244`). `SubagentExecutionService.executeCoordinator(...)` requires `automatic=true`, trigger `COORDINATOR`, the `agent.subagents.coordinator-auto.enabled` flag, repeats eligibility, atomically calls `spawnAndRecordCoordinator`, starts the persisted child, and calls `executeReserved(...)` (`SubagentExecutionService.java:355-397`).
- `executeReserved(...)` runs the child through `SpecChatService.runTurnBounded` with read-only tools, per-turn authorization, max four execution rounds, tree/cost limits, evidence validation, and persisted completion/cancellation (`SubagentExecutionService.java:83-174`).

## Tests already covering the seam

- `SubagentProtocolServiceTest.evaluateDelegation_isSideEffectFreeAndReportsCapacity` and `...rejectsInactiveParentWithoutSideEffects` prove the probe contract.
- `SubagentExecutionServiceTest` covers flag-off rejection, flag-on coordinator execution, eligibility re-check, child-id matching, and reservation/audit behavior.
- `AgentSessionControllerSubagentTest` covers eligibility, explicit coordinator execution, parent/session matching, and cancellation delegation.
- `SubagentChatToolsTest` covers model-visible single/batch delegation, eligibility rejection, and coordinator flag routing.

## Remaining blocker / non-goal

There is no safe implicit “accepted decision automatically runs” transition on the eligibility endpoint, by design. Turning `POST .../eligibility` into an execution trigger would violate its read-only contract, make retries non-idempotent, and create provider spend from a probe. The missing capability is a higher-level orchestrator that decides when to call the explicit coordinator endpoint (or service method) after an accepted decision. That caller needs an authenticated parent turn, user-visible consent/policy, idempotency key, and a durable continuation action before enabling automatic model-driven delegation.

Recommendation: retain the current two-step contract. Add an orchestrator hook only when those four inputs are available; reuse `executeCoordinator(...)` rather than duplicating reservation or child execution logic. No live provider call is needed to validate this seam.
