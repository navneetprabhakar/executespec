/**
 * V2 context persistence layer.
 *
 * <p>{@code ContextStoreV2} (Phase 8) persists compacted agent message history to Redis
 * for crash recovery and retry-with-context. This package-info documents the Redis
 * schema so future implementers don't drift.
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
 * <h2>Access patterns</h2>
 * <ul>
 *   <li><b>Write:</b> every 5 worker iterations, on every compaction event, before shell/build calls</li>
 *   <li><b>Read:</b> on task retry (replay last context), on harness boot ({@code StartupRunResumerV2})</li>
 *   <li><b>Delete:</b> on task COMPLETE (success path), on run terminal state</li>
 * </ul>
 *
 * <h2>Sizing</h2>
 * <p>Compacted context typically 20–40 KB per task. A run with 5 tasks ≈ 200 KB peak.
 * 1000 concurrent in-flight runs ≈ 200 MB Redis memory. Monitor
 * {@code redis_memory_used_bytes} and tune TTL down if pressure observed.
 */
package com.navneet.executespec.v2.context;
