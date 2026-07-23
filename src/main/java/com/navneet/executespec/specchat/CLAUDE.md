# `specchat/` — chat trust and capability boundary

`SpecChatService` is a compatibility/model-loop seam, not an action authority. Every chat-reachable
tool must be explicitly classified in `ChatToolPolicy` as `PROJECT_READ`, `PROJECT_ANALYSE`,
`SPEND_AI`, or `WRITE_REPO`; unknown names fail closed as `WRITE_REPO`. Model loops currently bind
`PROJECT_READ` only. Run submission, plan decisions, cancellation, project generation, code-review
scans, and child-agent spend use their explicit structured endpoints/proposals instead.

`policy/ProjectChatPolicyResolver` resolves one immutable, secret-free `ProjectChatPolicy` before
every legacy Spec Chat, persistent agent-session chat, and direct project-chat model turn. It binds
the authenticated actor, actor org, host project org, effective project role, exact capabilities,
provider/model/key-source allow-lists, per-turn budgets, confirmation reference/state, source-trust
labels, audit correlation, and exact V282 context id/scope. Unknown identity, missing membership,
session/project mismatch, tenant drift, stale role, or durable-context mismatch fails closed.
`PROJECT_VIEWER` has `PROJECT_READ` only and therefore cannot initiate provider spend.

The mutable `TurnGuard` holds counters outside the immutable policy. It revalidates actor, tenant,
role, capabilities, and V282 identity before every model round and every tool invocation, then
checks the exact provider/model and round/provider/tool ceilings. V292's
`ProjectChatConfirmationService` is the durable authority outside that model loop: Web/CLI issue,
then consume one action/provider/model/target-scoped 256-bit token. Only its SHA-256 digest is
stored; the grant is actor/host-org/project bound, expires in five minutes by default, and is
consumed under a pessimistic row lock in a committed `REQUIRES_NEW` transaction before side
effects. `PROJECT_VIEWER` is denied at controller and service boundaries. Architecture
regeneration, generated docs, and code review have confirmed handlers. Semantic embedding search,
child-agent spend, plan mutation, cancellation and other unsafe actions remain deliberately
unbound; cloud-run submission retains its separate typed proposal protocol. The secret-free
`ConfirmationReference` can be carried by policy/action state, but pending grants never become
ambient model authority.

Confirmation issue and consume resolve the action's actual agent role, not the chat model. They bind
Enterprise org matrix overrides while discovering the route and reject adaptive provider drift.
`GET /api/projects/{projectId}/chat/info` exposes the same action-specific models to Web and
CLI/OpenTUI. Confirmed execution pins the exact provider/model for every downstream call with
failover disabled, including the asynchronous `CodeReviewService` executor. The shared V282 action
context attributes usage to the initiating actor while retaining the host project owner as the
credential subject. Route/context creation failure, route drift, provider/model mismatch, or an
architecture LLM fallback returns 503 rather than reporting a successful confirmed action. The
one-shot grant remains burned after any downstream failure.

Hard action budgets are part of the scope hash and are displayed on confirmation cards:

- `REGENERATE_ARCHITECTURE`: 1,000,000 total tokens, $10, 64 provider calls.
- `GENERATE_PROJECT_DOC`: 200,000 total tokens, $3, 2 provider calls.
- `RUN_CODE_REVIEW`: 300,000 total tokens, $5, 2 provider calls.

These are explicit UI/command actions. The chat model does not generate structured confirmation
proposals and never receives the bearer token.

V292 also rejects `activity_audit` UPDATE/DELETE in PostgreSQL. `ActivityAuditService.record`
takes a transaction advisory lock before reading the hash-chain tail, keeping issue, allow, deny,
consume, replay and expiry evidence ordered across JVMs. Audit rows contain scope identifiers and
hashes, never bearer tokens or target JSON. Input is NFKC-normalized, invisible characters are
removed, and user instructions are scanned before persistence or provider use. Local-folder
snapshots, uploaded content, repository source, docs, web pages, memory, architecture, and tool
results are untrusted data and must remain delimiter-framed/marked as such.

Release evidence includes a real PostgreSQL V291→V292 Flyway rehearsal (schema creation, hashed
scope insert, audit UPDATE/DELETE rejection, idempotent rerun) and a concurrent two-consumer test
that produces exactly one `CONSUMED` and one `REPLAY` outcome under the row lock. Deterministic
route/budget/controller/service tests cover substitution, expiry, wrong bearer, grant burn after
executor failure, org-override restoration, async route propagation, and aggregator routability.

Per-turn hard limits are 5 tool-enabled model rounds plus at most one tool-free final synthesis, 12
tool calls, 40,000 observed input+output tokens, $0.50 incremental provider cost, and 60,000
conversation characters. Crossing a limit prevents further tool/provider work and produces a
bounded terminal explanation. Provider text, tool envelopes, SSE fields, and exception
descriptions pass through `ContextSanitizer`; raw credentials and raw provider exception messages
must never reach a client/model.

The compatibility `POST /api/spec/chat` turn does not run on the servlet thread. `SpecChatController`
hands the loop to `SpecChatTurnDispatcher`, which admits it onto a context-propagating virtual thread
under a global (`spec.chat.max-concurrent-turns`, default 8) and per-user
(`spec.chat.max-concurrent-turns-per-user`, default 2) ceiling; an over-ceiling turn is refused with
HTTP 429 before any emitter streams, and the emitter carries a 10-minute hard timeout. A client
disconnect surfaces as an emitter IO failure and stops the loop rather than continuing to spend.

Provider routing is fail-closed at this spending boundary. `SpecChatService` requires the caller to
pass the provider already resolved from the explicit turn/session/account/org policy. Missing or
unknown providers are rejected, and failure to resolve the selected provider's model returns 503;
there is no implicit Anthropic or system-key fallback.

Security decisions are durable: agent-session route/tool/result/confirmation evidence remains in
append-only `agent_action_event`; legacy/project-chat admission and tool allow/deny/result evidence
is additionally written through `ChatSecurityAuditService` to the hash-chained `activity_audit`.
Audit metadata contains only tool/capability/outcome identifiers, never prompts, arguments,
results, exception bodies, or secrets.
