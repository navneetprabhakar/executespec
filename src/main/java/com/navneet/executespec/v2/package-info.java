/**
 * ExecuteSpec Harness V2 — sequential-first, native-tool-only redesign.
 *
 * <p>V2 is a full replacement of the V1 harness, built in this isolated package tree.
 * It is gated by feature flag {@code harness.version} and runs alongside V1 until cutover.
 *
 * <p><b>Design principles:</b>
 * <ul>
 *   <li>Sequential execution — one agent per task, no parallel coordination overhead</li>
 *   <li>Native tool calling for all three providers (Anthropic, OpenAI, Google)</li>
 *   <li>Smoke tests after every task merge — ground truth during execution</li>
 *   <li>Checkpointed agent context in Redis (24h TTL) for crash recovery + retry-with-context</li>
 *   <li>Diff-first edits via {@code editFile}; {@code writeFile} reserved for new files</li>
 *   <li>No SubtaskDecomposer, no FileLockTool, no PATH B XML parsing</li>
 * </ul>
 *
 * <p>See {@code docs/harness-v2-remaining-work.md} for open gaps.
 */
package com.navneet.executespec.v2;
