# Package: `agentsession`

Backend protocol for the CLI and VS Code agent shells. This package owns durable agent sessions,
turns, action events, the action catalog, deterministic first-pass intent classification, repo to
project resolution, gate decision cards, and the streaming chat bridge that records open-ended
turns separately from runs.

## Boundaries

- `agent_session` / `agent_turn` are **not** `pipeline_run`. Open-ended conversation can inspect,
  interpret, or propose work without becoming a billable execution run.
- Cost-bearing or destructive actions are returned as typed confirmation-required proposals. This
  package must not approve a plan gate or create/approve a run implicitly from ambiguous chat text.
- `agent_usage_ledger` is the future meter for open-ended CLI/VS Code conversation usage. Run
  credits and refunds remain owned by the existing run approval path. Streaming chat writes usage
  here with `billingPolicy=INCLUDED` until a separate conversation billing/fair-use policy exists.
- CLI and VS Code should consume the same `/api/agent/**` protocol instead of implementing separate
  agents.

## Key Files

| File | Purpose |
| --- | --- |
| `AgentSessionController.java` | REST surface under `/api/agent`: sessions, streaming + recorded turns, action catalog, context resolution, gate decision cards. |
| `AgentSessionService.java` | Persistence/access checks, turn recording, event append, gate card construction, repo/project resolution. |
| `AgentIntentService.java` | Conservative rule-based intent classifier. LLM intent routing can replace/augment this later, but must preserve typed actions + confirmations. |
| `AgentActionCatalog.java` | Static action descriptors exposed to clients and future LLM intent prompts. |
| `entity/*` | `AgentSession`, `AgentTurn`, `AgentActionEvent`, `AgentUsageLedger`. |
| `dto/AgentSessionDtos.java` | Shared DTO records for CLI/VS Code. |

## Migration Trail

- `V239__agent_session_protocol.sql` creates `agent_session`, `agent_turn`,
  `agent_action_event`, and `agent_usage_ledger`.

## Tests

- `AgentIntentServiceTest` pins the billing/confirmation safety contract: open-ended text may
  propose `create_run`, `local_code_task`, or `approve_run`, but those proposals require an
  explicit follow-up confirmation.
