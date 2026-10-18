# ExecuteSpec contributor context

Read [AGENTS.md](AGENTS.md) for repository instructions and [README.md](README.md) for setup.
This file is intentionally a short entrypoint rather than a second architecture/history ledger.

Backend package notes live beside their implementations as `CLAUDE.md`. Frontend notes live in
[frontend/CLAUDE.md](frontend/CLAUDE.md). Historical package notes are context, not authoritative
proof of current runtime behavior.

## Core paths

- `integration/RunOrchestrator.java`: BUILD lifecycle coordination.
- `harness/SequentialExecutor.java`: task execution and continuation.
- `harness/BuildTaskGenerationCoordinator.java` and `workspacegeneration/`: durable BUILD generations.
- `engine/WorkerAgent.java`: worker tool loop.
- `reviewer/ReviewerAgent.java`: review.
- `agent/LlmRouter.java`: governed provider dispatch and usage accounting.
- `chat/ChatService.java`: post-run read-only chat; the unreferenced legacy ConversationAgent is removed.
- `frontend/src/console/`: protected web shell.

The public cleanup removes generated audit captures, private result artifacts, duplicate Vite output,
and unused conversation code. It does not qualify a deployment or make previously disabled runtime
capabilities available. The build and integration evidence required for each change remains explicit.

Applied migrations are immutable: never rewrite an applied V-file. Any future consolidation must
use a clean-install baseline migration plus forward migrations, preserving upgrade compatibility.

For the maintained cross-cutting contract and verification limits, read
[BUILD execution and evidence](docs/architecture/build-path-contract.md).

## BUILD acceptance and recovery

Current migration head is **V403**, with **389 migration files**; next available is **V404**.
New shared-kernel contracts use schema 3: bootstrap happens once, healthy initial baselines admit
feature/refactor/documentation work, and deterministic final acceptance owns task decisions.
Corrections replace an exact accepted prefix through V401; ordinary task execution stays contiguous.
V402 orders generation-bound asynchronous test evidence. V403 freezes plan-bound design and
continuation settings for recovery. Package notes in `workspacegeneration/`, `harness/`, `integration/`,
`report/`, `state/`, `cost/`, and `deployment/` describe these boundaries.

Local-hands BUILD submission and its advertised runtime capability are unavailable until local
clients can supply generation-bound acceptance and checkpoint receipts. The transport flag alone
cannot enable that missing protocol. No rollout flag is enabled by these changes.

Task operation claims bind the logical task context before minting generation authority. Ignored
cache cleanup removes local ignore rules last so pytest cannot invalidate its own classification.
Single-shot runtime/project-type inference excludes explicit negative constraints; a library
request that excludes CLI/HTTP remains a library. Local live fixtures cover both BUILD protocols
and keep proof uncertainty distinct from workflow completion. The isolated E2E fixture selects
explicit provider/model pairs via `E2E_PROVIDER`/`E2E_MODEL`; exact run-bound budget reservations
remain mandatory. See README for the bounded routes and credential-free startup command.

Cross-provider repair (2026-10-17): verification ownership now covers materialization, model calls,
reads and commands. A dedicated lifecycle-managed heartbeat renews sessions; expired/taken-over
capabilities fail closed for new tool/provider dispatch and cannot reacquire authority. OpenRouter
and Fireworks translate the native DeepSeek bounded-review reasoning policy into their own API
parameters. Accuracy assessment preserves leading filename letters and reads README/dependency
pins through its bounded config-evidence lane. Fireworks selects the current V4.1 Flash route from
YAML; obsolete pins require explicit reselection, and benchmark defaults read the same registry.
Fresh OpenRouter, Fireworks and OpenAI BUILD runs each accepted three tasks, finished CLEAN and
passed independent checks. These are local scenario proofs, not provider-wide model qualification.

Anthropic platform credentials can optionally specify `ANTHROPIC_WORKSPACE_ID`; both normal and
forced tool transports attach the workspace header only for the matching configured platform key.
Unrelated BYOK credentials do not inherit that platform workspace.
