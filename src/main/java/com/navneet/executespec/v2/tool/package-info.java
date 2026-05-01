/**
 * V2 tool layer.
 *
 * <p>Provides a unified {@link com.navneet.executespec.v2.tool.Tool} contract, typed {@link
 * com.navneet.executespec.v2.tool.ToolResult} (Ok/Err with error taxonomy), and a per-task {@link
 * com.navneet.executespec.v2.tool.ExecutionContext}.
 *
 * <h2>Tool inventory (Phase 1)</h2>
 *
 * <ul>
 *   <li>{@code readFile}, {@code writeFile}, {@code editFile}, {@code listFiles}, {@code
 *       searchFiles} — file system (FileSystemToolV2)
 *   <li>{@code shell} — sandboxed shell (ShellToolV2; reuses V1 ShellSandboxPolicy)
 *   <li>{@code gitStatus}, {@code gitDiff}, {@code gitCommit} — git (GitToolV2)
 *   <li>{@code buildProject} — runs detected build command (BuildToolV2)
 * </ul>
 *
 * <h2>Differences from V1</h2>
 *
 * <ul>
 *   <li>No FileLockTool — V2 is sequential, no contention possible
 *   <li>{@code editFile} supports multi-edit (array of {oldStr, newStr} pairs) — most
 *       model-friendly diff format per Phase 1 design notes
 *   <li>{@code writeFile} errors if the file already exists — use {@code editFile} to modify
 *       existing files (cuts write-phase tokens by ~60%)
 *   <li>Strict JSON schemas ({@code additionalProperties: false}) for every tool
 *   <li>Typed errors ({@link com.navneet.executespec.v2.tool.ToolResult.Err}) instead of
 *       stringified failures
 * </ul>
 */
package com.navneet.executespec.v2.tool;
