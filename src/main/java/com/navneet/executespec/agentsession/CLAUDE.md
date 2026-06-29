# Package: `agentsession`

Backend protocol for the CLI and VS Code agent shells. This package owns durable agent sessions,
turns, action events, the action catalog, deterministic first-pass intent classification, repo to
project resolution, gate decision cards, and the streaming chat bridge that records open-ended
turns separately from runs.

## Boundaries

- Terminal/CLI/VS Code work that touches this package must cite the terminal audit register
  (`docs/plans/terminal-audit-register-2026-06-20.md`) and carry the relevant
  `TERM-AUDIT-SERVER-*` tag in docs/PRs.
- `agent_session` / `agent_turn` are **not** `pipeline_run`. Open-ended conversation can inspect,
  interpret, or propose work without becoming a billable execution run.
- Cost-bearing or destructive actions are returned as typed confirmation-required proposals. This
  package must not approve a plan gate or create/approve a run implicitly from ambiguous chat text.
- Streaming turns first emit and persist an orchestrator route decision. Non-run routes receive a
  read-only tool surface only; implementation/lifecycle/local/destructive decisions stop at a typed
  proposal or rejection until the client performs an explicit confirmed action.
- `POST /api/orchestrator/turn` is the non-stream primary decision endpoint. It uses the same
  deterministic route/policy contract, persists `ORCHESTRATOR_DECISION`, and uses client `turnId`
  as the idempotency key.
- `agent_usage_ledger` is the future meter for open-ended CLI/VS Code conversation usage. Run
  credits and refunds remain owned by the existing run approval path. Streaming chat writes usage
  here with `billingPolicy=INCLUDED` until a separate conversation billing/fair-use policy exists.
- CLI and VS Code should consume the same `/api/agent/**` protocol instead of implementing separate
  agents.
- Provider choice is cross-surface account state, not a client-local default. Session creation and
  turns must use `AiProviderPreferenceService`: explicit provider input updates
  `app_user.preferred_ai_provider`; omitted input resolves from that preference.
- Common chat is not only a run launcher. When an `agent_session` has a `projectId`, the streaming
  turn path binds `ProjectChatTools` through `SpecChatService` so CLI and VS Code can answer
  project/code questions with the same architecture, memory, document, tech-debt, and source-search
  tools used by web project chat.
- The read-only chat tool surface also includes `web_search` / `web_fetch` (from
  `specchat/WebSearchChatTools`, SSRF-hardened, content returned `untrusted`-flagged) so the agent
  can ground answers in public web pages. The worker-only `tool/` web tools stay out of chat. Chat
  uploads go through `AgentAttachmentService` (`POST /api/agent/sessions/{id}/attachments`): every
  file is type/size/extract/injection-validated and stored sanitized in `AgentAttachmentStore`
  (Redis), then injected server-side as untrusted-delimited context via
  `StreamTurnRequest.attachmentIds` — never the raw bytes, never client-controlled delimiting.
- Run-scoped web agent sessions persist `agent_session.run_id` (V246). Creation must validate the
  run through `RunAccessGuard`; if the caller also sends `projectId`, it must match the run's
  project. Streaming turns pass the scoped run id into `SpecChatService` as context instead of
  relying only on a UI message prefix.
- Action events that belong to a local/terminal action must persist the first-class
  `agent_action_event.action_id` (V247), not only a nested payload field, so CLI/web clients can
  correlate permission prompts and action results after reload.

## Key Files

| File                                       | Purpose                                                                                                                                                                                                             |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `OrchestratorController.java`              | Primary structured `/api/orchestrator/turn` endpoint for route/policy decisions plus explicit `/turn/confirm` for confirmed cloud-run submissions.                                                                  |
| `OrchestratorService.java`                 | Stable controller-to-service facade for structured turn decisions and explicit confirmed run submission; delegates persistence to `AgentSessionService` and keeps run creation behind `AgentRunSubmissionGate`.     |
| `AgentRunSubmissionGate.java`              | The only agent-session path allowed to create a new cloud run from a stored `CODE_RUN_REQUIRED` decision. Confirmed submissions can carry `lowComplexityWorkerStartTier` so LOW tasks can start cheap without using a route-pinning model override.                              |
| `AgentActionArguments.java`                | Package-local typed-action argument helper shared by confirmed run submission and confirmation write guards.                                                                                                        |
| `AgentSessionController.java`              | REST surface under `/api/agent`: sessions, streaming + recorded turns, action catalog, context resolution, gate decision cards.                                                                                     |
| `AgentSessionService.java`                 | Session access checks, high-level orchestration, and API-facing service delegation.                                                                                                                                 |
| `AgentSessionReadService.java`             | Reads agent sessions, session events, and turn transcripts with ownership checks, capped pagination, DTO mapping, and stored trace hydration.                                                                       |
| `AgentSessionCreationService.java`         | Creates or reuses agent sessions with requested-id idempotency, project/run-scope validation, provider preference resolution, model access checks, and normalized session defaults.                                 |
| `AgentOrchestratorTurnService.java`        | Creates structured orchestrator turns: idempotent replay, request policy downgrade, project read check, turn persistence, trace event append, and session title/timestamp update.                                   |
| `AgentRunConfirmationService.java`         | Wraps confirmed cloud-run submission from stored orchestrator decisions: validates turn ownership, rejects non-run routes, enforces project write access, links turns to runs, and appends submission trace events. It preserves provider/model/cost-cap, non-pinning LOW-start confirmation fields, and the `localExecutionRequested` / `localExecutionAccepted` handshake echoed from the run submission gate. |
| `AgentRecordedTurnService.java`            | Persists legacy recorded turns: deterministic intent/orchestrator route evaluation, initial turn row, session title/timestamp update, `INTENT` event append, and response assembly.                                 |
| `AgentActionLifecycleService.java`         | Persists action planned/event/result records for local tool and terminal action audit trails.                                                                                                                       |
| `AgentEventRecorder.java`                  | Appends durable action events with consistent ids, timestamps, turn/action scoping, and payload JSON fallback.                                                                                                      |
| `AgentRunEventBridge.java`                 | Mirrors typed task-wave and goal/continuation `RunEvent`s into active run-scoped `agent_action_event` rows so reloadable web/CLI/VS Code sessions can show the same run decision trail, including approved continuation actions (`CONTINUATION_ACTION` with `status=APPROVED`).                              |
| `AgentValidationReportEventBridge.java`    | Mirrors saved validation-report evidence ledgers plus conservative `runGoalDecision` proof into active run-scoped `agent_action_event` rows as `RUN_VALIDATION_EVIDENCE`.                                           |
| `AgentTracePayloadFactory.java`            | Builds orchestrator trace payloads with route, policy, prompt, cost-run-call, and message-hash evidence.                                                                                                            |
| `AgentContextResolver.java`                | Resolves local Git remotes/repo names to accessible linked projects without leaking unreadable project links.                                                                                                       |
| `AgentGateDecisionCardFactory.java`        | Builds plan-gate decision cards from `PipelineRun` plus project metadata and plan JSON; keeps task/risk/model fallback logic out of the session service.                                                            |
| `AgentGateReadService.java`                | Reads pending plan gates for agent clients: capped user-scoped list, single-run access guard, awaiting-approval status check, and card mapping.                                                                     |
| `AgentTurnReadModel.java`                  | Hydrates reloadable turn DTOs and idempotent orchestrator replay responses from stored trace events.                                                                                                                |
| `AgentTurnRecorder.java`                   | Creates initial `AgentTurn` rows with consistent sequence, transcript fields, proposed-action JSON, and status.                                                                                                     |
| `AgentStreamTurnStarter.java`              | Starts streaming turns: route decision, initial turn row, provider preference update, title update, and `STREAM_STARTED` trace event.                                                                               |
| `AgentStreamTurnLifecycle.java`            | Persists stream terminal states: completion usage ledger rows, failure events, and disconnect cancellation behavior.                                                                                                |
| `AgentStreamTurnRunner.java`               | Executes one SSE streaming turn after start: emits ack/route/proposal/token/tool/done frames, binds read-only tools, handles decision-only routes, and records completion/failure/disconnect.                       |
| `AgentStreamTurnSupport.java`              | Package-local stream support policy: read-only tool allowlist (incl. `web_search`/`web_fetch`), conversation fallback, server-side attachment-block injection, and client-disconnect detection.                     |
| `AgentAttachmentService.java`              | Validates + extracts + sanitizes a chat upload (magic-byte type allow-list + Tika + `InputSanitizer` injection scan + `ContextSanitizer`), stores it, and builds the untrusted-delimited injection block for the turn. Audits accept/reject. |
| `AgentAttachmentStore.java`                | Redis-backed store (2h TTL) for validated, sanitized upload text keyed by session + attachment id, loaded server-side at turn time.                                                                                 |
| `AgentText.java`                           | Package-local string helper for repeated blank, fallback, trim, and path-leaf handling across agent-session services.                                                                                               |
| `AgentIntentService.java`                  | Conservative deterministic pre-router. LLM intent routing can replace/augment this later, but must preserve typed routes, policy, typed actions, and confirmations.                                                 |
| `AgentIntentDecisions.java`                | Package-local decision/policy construction and billing-impact mapping helper for deterministic route outputs.                                                                                                       |
| `AgentGuardIntents.java`                   | Package-local pre-routing guard for empty input, unsafe requests, and explicit no-edit/chat-only instructions.                                                                                                      |
| `AgentCodeChangeIntents.java`              | Package-local code-change router for local code tasks, missing-project implementation proposals, and confirmation-gated cloud-run proposals.                                                                        |
| `AgentNonRunIntents.java`                  | Package-local non-run router for terminal local actions, review/plan, vague mutation clarification, read-only code help, cost queries, and project resolution.                                                      |
| `AgentChatFallbackIntent.java`             | Package-local final fallback for ordinary chat; keeps the default route explicitly non-run.                                                                                                                         |
| `AgentIntentLegacyAdapter.java`            | Package-local compatibility adapter from orchestrator decisions to legacy `IntentDecision` DTOs used by older `/api/agent` clients.                                                                                 |
| `AgentIntentPatterns.java`                 | Package-local route vocabulary and matcher helper for deterministic local-action, read-only, lifecycle, unsafe, and mutation-request classification.                                                                |
| `AgentRunLifecycleIntents.java`            | Package-local run lifecycle router for approve/reject/cancel/list-gates commands; keeps lifecycle side effects out of code-run routing.                                                                             |
| `OrchestratorRoutingCorpus.java`           | Shared loader for the deterministic JSONL routing corpus used by tests and eval tooling.                                                                                                                            |
| `OrchestratorRoutingPromptCatalog.java`    | Shared prompt/schema catalog for provider-specific orchestrator routing prompt eval.                                                                                                                                |
| `OrchestratorRoutingPromptEvalRunner.java` | Optional live provider eval runner over the routing corpus; returns JSON plus a Markdown report artifact.                                                                                                           |
| `OrchestratorRoutingEvalController.java`   | SUPER_ADMIN operator endpoint for credentialed prompt-eval runs.                                                                                                                                                    |
| `AgentActionCatalog.java`                  | Static action catalog exposed to clients and future LLM intent prompts. One private action-definition source emits both descriptors and capability tools so surface/tier/billing metadata cannot drift.             |
| `entity/*`                                 | `AgentSession`, `AgentTurn`, `AgentActionEvent`, `AgentUsageLedger`.                                                                                                                                                |
| `dto/AgentSessionDtos.java`                | Shared DTO records for CLI/VS Code.                                                                                                                                                                                 |

## Migration Trail

- `V240__agent_session_protocol.sql` creates `agent_session`, `agent_turn`,
  `agent_action_event`, and `agent_usage_ledger`.
- `V242__user_preferred_ai_provider.sql` adds the shared account provider preference used by
  agent-session chat defaults.
- `V246__agent_session_run_scope.sql` adds `agent_session.run_id` and the run-scope index for
  reloadable `/runs/{runId}/agent` sessions.
- `V247__agent_action_event_action_id.sql` adds `agent_action_event.action_id` for reloadable
  action lifecycle correlation across permission prompts, local tool events, and action results.

## Tests

- `AgentIntentServiceTest` pins the route matrix and billing/confirmation safety contract:
  explanation, review, planning, "find the bug", chat-only patch requests, local git actions, and
  destructive requests must not create cloud runs; explicit implementation can only produce a
  confirmation-required `create_cloud_run` proposal.
- `AgentIntentLegacyAdapterTest` pins legacy DTO compatibility for `interpret()` callers:
  cloud-run proposals map to `CREATE_RUN`, lifecycle actions map to gate intents, read-only routes
  map to legacy `CHAT`, and confirmation prompts/responses keep the old nullability contract.
- `AgentTextTest` pins package-local string helper semantics: `firstNonBlank` preserves original
  values, `blankToNull` trims, bounded trim appends `\u2026`, and path leaf extraction handles
  trailing slashes.
- `AgentActionArgumentsTest` pins typed-action argument extraction used by confirmed run
  submission and confirmation write-guard checks: null/missing values stay null, string values keep
  their original spacing, and non-string values are stringified consistently.
- `AgentIntentDecisionsTest` pins shared route-decision construction: action-required
  confirmations become policy confirmation gates, explicit policy fields are preserved, and
  billing-impact strings stay aligned with action types.
- `AgentGuardIntentsTest` pins pre-routing guard behavior: empty messages clarify, unsafe requests
  reject, and explicit no-edit/chat-only instructions cannot call the run API.
- `AgentRunLifecycleIntentsTest` pins lifecycle command routing directly: approve/reject/cancel
  require run ids and confirmation actions, list-runs/list-gates stays read-only, and unrelated
  prompts are not claimed by lifecycle routing.
- `AgentRunSubmissionGateTest` pins confirmed cloud-run submission idempotency, propagation of
  `lowComplexityWorkerStartTier=LIGHTWEIGHT|WORKHORSE` as a normalized non-pinning run hint, and
  preservation of `localExecutionAccepted` on idempotent local-hands retries so clients never infer
  remote fallback from a duplicate submit response.
- `AgentCodeChangeIntentsTest` pins the only code-run-producing branch directly: local tools yield
  `local_code_task` without run API access, project-scoped cloud runs require confirmation, and
  missing/blank project context cannot call the run API.
- `AgentNonRunIntentsTest` pins non-run routing around the code-change branch: local terminal
  actions, review/plan, vague mutation clarification, read-only code help, cost queries, and
  project resolution cannot call the run API.
- `AgentChatFallbackIntentTest` pins the default fallback route as `CHAT_ONLY` with no proposed
  action, no confirmation, and no run API access.
- `AgentIntentPatternsTest` pins route-vocabulary helpers directly: UUID/short-run-id extraction,
  read-only route precedence, vague-mutation target requirements, local git/shell action detection,
  and unsafe/no-edit phrase separation.
- `AgentRoutingCorpusTest` loads `src/main/resources/orchestrator/routing-corpus.jsonl`, a 450-row
  deterministic corpus covering read-only, plan/review, code-change, lifecycle, ambiguous, and
  unsafe prompts. Rows marked `forbidRun` must keep `policy.runApiAllowed=false`, and every
  `CODE_RUN_REQUIRED` decision must require explicit confirmation.
- Provider routing prompts under `src/main/resources/prompts/orchestrator/` deliberately classify
  summarize/explain/show/read/search/inspect requests over repo, project, or workspace surfaces as
  `READ_ONLY_CODE_HELP`, not `CHAT_ONLY`; this keeps read-only project help grounded without
  allowing a run to be created.
- `AgentSessionServiceActionLifecycleTest` pins stream-turn trace persistence: the structured route
  decision is stored before chat execution and includes policy evidence such as `runApiAllowed`.
  It also pins confirmed run submission idempotency, turn-to-run linking, and trace fields such as
  `promptVersion`, `calledRunApi`, `createdRunId`, and `policyDecisions`.
- `AgentRunSubmissionGateTest` pins that only stored, confirmed `CODE_RUN_REQUIRED` decisions reach
  `RunOrchestrator.submitRun`; non-run routes and missing confirmation fail before a run is created.
- `AgentActionCatalogTest` pins surface/tier filtering and descriptor/capability metadata alignment
  so local-only tools stay out of WEB and action billing/confirmation fields do not drift, and pins
  `web_search`/`web_fetch` exposure on every surface.
- `AgentAttachmentServiceTest` pins the upload gate: clean text is accepted/extracted/stored, image
  and oversize/empty files are rejected, and an `InputSanitizer` injection hit propagates as 400 with
  no store write. `specchat/WebSearchChatToolsTest` pins the `web_fetch` SSRF guards (scheme,
  localhost/`*.internal`, loopback/link-local/metadata IPs), suspicious egress-query rejection,
  default-port-only enforcement, pinned-address handoff, content-type/length response guards, and
  required-arg rejection.
- `AgentApiOpenApiCoverageTest` pins that public agent-session, orchestrator, and managed-AI API
  routes plus action-lifecycle schemas are represented in `docs/openapi.yaml`.
- `AgentSessionReadServiceTest` pins active-session pagination, owned-session loading,
  missing/forbidden session rejection, event listing, bounded turn pagination, and stored trace
  hydration.
- `AgentSessionCreationServiceTest` pins requested-id idempotency, ownership rejection, run-scoped
  project resolution, provider preference update, model gate calls, and run/project mismatch
  rejection.
- `AgentActionLifecycleServiceTest` pins planned-action event payloads, generic action events,
  result status normalization, touched files, verification payloads, and session timestamp updates.
- `AgentOrchestratorTurnServiceTest` pins structured turn creation, read-only permission downgrade,
  idempotent replay, conflicting turn-id rejection, project read checks, and trace event append.
- `AgentRunConfirmationServiceTest` pins confirmed run-submission wrapping: non-run rejection,
  linked-run idempotency, turn ownership rejection, project write checks, gate invocation, turn
  linking, local-hands handshake echoing, and `ORCHESTRATOR_RUN_SUBMITTED` trace events.
- `AgentRecordedTurnServiceTest` pins legacy recorded-turn persistence: deterministic intent/route
  output, initial turn rows, title update behavior, null-request handling, and `INTENT` event
  append.
- `AgentEventRecorderTest` pins session/turn-scoped event metadata, payload JSON, and null payload
  handling for durable action events.
- `AgentRunEventBridgeTest` pins run-scoped action-event surfacing for typed task-wave and
  goal/continuation run events and verifies unrelated run events do not write agent-session
  records.
- `AgentValidationReportEventBridgeTest` pins run-scoped action-event surfacing for final
  validation-report evidence ledgers and verifies events without a run id do not write records.
- `AgentTracePayloadFactoryTest` pins route/policy/run metadata, deterministic prompt metadata,
  message hashes, policy decision evidence, and blocked-route fallback fields.
- `AgentContextResolverTest` pins Git remote normalization, nested repo-context fallback,
  inaccessible-link filtering, no-match reasons, and the null-request fail-safe for context
  resolution.
- `AgentGateDecisionCardFactoryTest` pins gate-card project lookup, task parsing, model fallback,
  summary/title fallback, and LOW/MEDIUM/HIGH risk inference from task count.
- `AgentGateReadServiceTest` pins capped pending-gate pagination, card mapping, `RunAccessGuard`
  use for single-gate reads, and non-awaiting gate rejection.
- `AgentTurnReadModelTest` pins turn read-model hydration from stored orchestrator traces,
  idempotent replay fallback when trace payloads are missing, and fail-soft handling of malformed
  trace payloads.
- `AgentTurnRecorderTest` pins next-sequence assignment, explicit/generated turn ids, transcript
  fields, proposed-action JSON, and initial turn status.
- `AgentStreamTurnStarterTest` pins stream-start route decisions, provider override/fallback,
  session title updates, initial `STREAMING` turn persistence, trace events, and returned
  `StreamTurnContext`.
- `AgentStreamTurnLifecycleTest` pins completion/failure/disconnect persistence, including usage
  ledger rows, terminal stream events, and the rule that disconnect does not overwrite completed
  turns.
- `AgentStreamTurnSupportTest` pins stream support policy: read-only tool allowlist,
  conversation fallback behavior, and client-disconnect detection.
- `OrchestratorRoutingPromptCatalogTest` and `OrchestratorRoutingPromptEvalRunnerTest` pin shared
  prompt schemas and fail-closed model-eval scoring without requiring live credentials.
- `OrchestratorControllerTest`, `AgentOrchestratorTurnServiceTest`,
  `AgentRunConfirmationServiceTest`, and `AgentSessionServiceActionLifecycleTest` pin the primary
  `/api/orchestrator/turn` service boundary, idempotent turn replay, read-only mode downgrade,
  `ORCHESTRATOR_DECISION` trace persistence, confirmed run submission, and the turn read model's
  route/reason-code hydration from stored trace events.
- `AgentSessionServiceActionLifecycleTest` also pins run-scoped session creation: `RunAccessGuard`
  is required, the run's project is persisted, and the response carries `runId`.
