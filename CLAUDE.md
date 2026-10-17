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
