/**
 * V2 context persistence layer.
 *
 * <p>{@code ContextStore} (Phase 8) persists compacted agent message history to Redis for crash
 * recovery and retry-with-context. This package-info documents the Redis schema so future
 * implementers don't drift.
 *
 * <h2>Redis Schema</h2>
 *
 * <pre>
 * Key pattern : v2:agent-ctx:{runId}:{taskId}
 * Value type  : String (JSON-serialized {@code List<Message>} compacted form)
 * TTL         : 86400 seconds (24 hours), set on every write via SET ... EX 86400
 * Eviction    : Redis default (allkeys-lru) — TTL is the primary lifecycle control
 *
 * Workspace checkpoint (enabled by {@code durability.worker-context.enabled}):
 *   {@code <workspace>/.agent-context/worker/{taskId}.json}, written atomically on each snapshot
 *   save. Redis remains the fast path; the workspace checkpoint is the bounded restart fallback
 *   when Redis is unavailable or the key has expired. It restores conversation state only when
 *   the workspace is available; it cannot resurrect an in-memory provider call or prove a
 *   cross-JVM worker handoff. The run journal envelope now records the run identity and reload
 *   rejects a copied journal whose identity does not match the active run.
 *
 * Run journal:
 *   {@code <workspace>/.agent-context/run-journal.json}, a bounded atomic, versioned envelope of
 *   the latest task outcome summaries (including an update timestamp). Readers retain compatibility
 *   with the pre-envelope bare-array format, while unknown schema versions fail closed instead of
 *   being mistaken for trusted recovery context. The executor reloads it on restart and replaces
 *   entries by task id; it is prompt context only, never authoritative task state or proof of hot
 *   handoff.
 *
 * Auxiliary keys (Phase 8):
 *   v2:agent-ctx-index:{runId}    Set of taskIds with persisted context (TTL 86400)
 *   v2:agent-ctx-meta:{runId}:{taskId}  Hash of metadata (iteration, lastWriteAt, byteSize) (TTL 86400)
 * </pre>
 *
 * <p>Completed context-agent runs also write a workspace-local {@code
 * .agent-context/evidence-bundle.json}. It is a bounded, schema-versioned handoff dossier
 * containing deduplicated evidence summaries and SHA-256 fingerprints of {@code
 * CODEBASE_CONTEXT.md} and {@code decisions.md}. Any reader must use {@code
 * ContextEvidenceBundleWriter.readCurrent(...)} so a source edit invalidates the bundle instead of
 * replaying stale evidence. The current release writes this sidecar for inspection and recovery
 * preparation; its additive {@code handoff} object carries goal, explicit hypothesis (empty when
 * the context agent did not provide one), changed files, observed failures, and do-not-repeat
 * decisions. Cross-run consumption remains a follow-up until a durable project-scoped handoff store
 * is wired.
 *
 * <h2>Access patterns</h2>
 *
 * <ul>
 *   <li><b>Write:</b> every 5 worker iterations, on every compaction event, before shell/build
 *       calls
 *   <li><b>Read:</b> Redis context is replayed on task retry; the evidence sidecar has no active
 *       cross-run consumer yet
 *   <li><b>Delete:</b> on task COMPLETE (success path), on run terminal state
 * </ul>
 *
 * <h2>Sizing</h2>
 *
 * <p>Compacted context typically 20–40 KB per task. A run with 5 tasks ≈ 200 KB peak. 1000
 * concurrent in-flight runs ≈ 200 MB Redis memory. Monitor {@code redis_memory_used_bytes} and tune
 * TTL down if pressure observed.
 *
 * <p>{@code ContextPlanner} provides deterministic priority/freshness selection and model-window
 * headroom decisions. Worker headroom subtracts the model's output reservation, provider safety
 * margin, a bounded next-tool-result reserve, a verification/repair reserve, and a handoff/journal
 * reserve before deciding whether the history must compact. This keeps the full context window from
 * being treated as usable input and preserves room for the next action after a large tool response
 * or provider handoff. {@code ContextEvidenceAssembler} uses the selector for optional handoff
 * lists while preserving goal, diff, reviewer and decision text as mandatory evidence; it never
 * selects or reorders tool messages. The assembler removes repeated search hits and estimates each
 * optional entry at the bundle writer's 1,000-character cap before spending its 6,000-token budget,
 * so duplicate or overlong hits cannot crowd out distinct evidence that will actually be
 * serialized. Worker headroom decisions also emit low-cardinality Micrometer evidence through
 * {@code executespec.context.headroom.decisions} and {@code
 * executespec.context.headroom.available_tokens}; provider is bounded and model names are
 * deliberately excluded from metric tags so enterprise model overrides cannot create unbounded time
 * series. This is a local deterministic optimization; it does not require a FastContext/Microsoft
 * runtime dependency. {@code FastContextExperiment} remains an isolated, measured adapter seam
 * until a paired recall/latency/cost evaluation justifies using an external index.
 */
package com.navneet.executespec.context;
