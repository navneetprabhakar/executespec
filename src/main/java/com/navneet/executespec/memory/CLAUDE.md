# `memory/` — Project memory and ledger

## Prompt authority boundary (2026-07-18)

Project memory, architecture, retrieved documents, ledger inputs, and compacted snapshots are
project-controlled evidence. Project Chat now keeps all of those blocks in a dedicated
`UserMessage`; its `SystemMessage` contains only server-owned role/tool policy. `RunLedgerAgent` and
`MemoryCompactionService` similarly keep their templates/data in wrapped user evidence instead of
assembling project content into system prompts.

Project-scoped run ledgers are compacted into versioned memory snapshots. Memory generation is
best-effort: a failed model call must never fail the customer run.

## Routing contract

`MemoryCompactionService` is a text-only metadata summarizer. It resolves the lightweight
`RunLedgerAgent` model and calls `LlmRouter.callText` with an explicit empty tool set. The canonical
text seam owns provider transport, usage accounting, retry, failover, and telemetry. The retired
compatibility router is not part of the ExecuteSpec 2.0 runtime.

`RunLedgerAgent` also uses `LlmRouter.callText`; its run-completion path is best-effort and catches
all transport failures, while canonical retry/failover and managed-AI accounting remain centralized
in the router. The call uses an explicit empty tool set and stable `run-ledger` / `generate`
attribution.

`MemoryCompactionService` resolves its provider through `ProjectProviderResolver`, preserving the
project setting and legacy account preference before the canonical Anthropic fallback. It no
longer hardcodes OpenAI for compaction.

Project chat compaction and spec compilation use the same resolver, so all project-chat phases keep
the selected provider instead of falling back independently to OpenAI.

Project chat also tracks tool name+arguments per turn. If a provider repeats every read call, the
loop gives it one final no-tools answer turn instead of spending the remaining round budget on
duplicate reads; this is especially important for Gemini project-chat tool selection.
Exact duplicate read/search calls are also served from the first bounded envelope instead of being
dispatched again. This avoids paying for repeated reads while preserving the original result for
the model to use.

Project chat is a strict read-only model tool surface. `ProjectChatTools` classifies every tool as
`PROJECT_READ` / `PROJECT_ANALYSE` / `SPEND_AI` / `WRITE_REPO` and currently binds only
`PROJECT_READ`; architecture/doc generation and code-review scans remain available through their
explicit dashboard/API actions. `ProjectChatService` resolves the immutable
`specchat/policy/ProjectChatPolicy` before send, compaction, or compile dispatch, attaches its exact
V282 execution-context reference, and revalidates its turn guard before each model round. Every
bound callback is wrapped by that guard, which rechecks actor/tenant/role/context/capability and the
tool-call ceiling immediately before invocation; the callback then repeats project read access,
emits allow/deny/result decisions to the hash-chained activity audit, sanitizes/redacts its
envelope, and labels repository/document payloads as untrusted data. `PROJECT_VIEWER` may read
persisted history but cannot create a paid turn.

## Explicit confirmed actions (V292)

Paid/mutating project actions are still absent from the model's callback registry. Web and
CLI/OpenTUI explicitly initiate `REGENERATE_ARCHITECTURE`, `GENERATE_PROJECT_DOC`, or
`RUN_CODE_REVIEW`, show the server-resolved action route and hard cap, and then issue/consume a
one-shot Project Chat confirmation. `ProjectChatController.info` resolves the chat model and the
three action-specific models while the host org's matrix overrides are bound, restoring any prior
thread binding afterward. This prevents a PLANNING chat model from being incorrectly reused for a
WORKHORSE document action.

Each confirmed execution shares one exact V282 provider/model reference across every downstream
architecture/doc/review call; adaptive switching and failover are forbidden, including on Code
Review's async executor. Actor attribution follows the confirming member, while key resolution
stays bound to the host project subject. Caps are architecture 1,000,000 tokens/$10/64 calls,
generated docs 200,000/$3/2, and code review 300,000/$5/2. A missing/drifting exact route fails with
503 and a consumed grant cannot be replayed or revived after failure. This is an explicit action
surface, not a chat-model proposal protocol.

## Persistence

`ProjectRunLedger` stores one idempotent digest per run. `MemoryCompactionService` only marks
entries compacted after a bounded, non-empty snapshot is produced; failures return without
mutating ledger state.

`ProjectMemoryProvider` enforces a conservative 12,000-token (48,000-character) planner-memory
budget. The durable snapshot is selected before recent ledger entries; recent entries are included
newest-first only when complete entries fit, then rendered chronologically. Provider-side context
truncation is therefore not relied upon for project memory.
