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
 * preparation; cross-run consumption remains a follow-up until a durable project-scoped handoff
 * store is wired.
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
 * headroom decisions. {@code ContextEvidenceAssembler} uses the selector for optional handoff lists
 * while preserving goal, diff, reviewer and decision text as mandatory evidence; it never selects
 * or reorders tool messages. The assembler removes repeated search hits and estimates each optional
 * entry at the bundle writer's 1,000-character cap before spending its 6,000-token budget, so
 * duplicate or overlong hits cannot crowd out distinct evidence that will actually be serialized.
 * This is a local deterministic optimization; it does not require a FastContext/Microsoft runtime
 * dependency. {@code FastContextExperiment} remains an isolated, measured adapter seam until a
 * paired recall/latency/cost evaluation justifies using an external index.
 */
package com.navneet.executespec.context;
