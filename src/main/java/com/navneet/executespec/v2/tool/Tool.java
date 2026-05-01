package com.navneet.executespec.v2.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * V2 tool contract.
 *
 * <p>A {@code Tool} is a pure capability — it knows its name, schema, and how to execute given
 * parsed args + an execution context. It does NOT know about LLM providers, tool callbacks, or the
 * agent loop. The {@link ToolRegistryV2} adapts tools to provider-facing {@code ToolCallback}s by
 * binding them to a context.
 *
 * <p>All implementations:
 *
 * <ul>
 *   <li>Return {@link ToolResult.Ok} or {@link ToolResult.Err} — never throw for expected failures
 *       (validation, not-found, IO). Exceptions are reserved for programming bugs.
 *   <li>Use a strict JSON schema with {@code "additionalProperties": false} and all required fields
 *       declared, so the LLM can't pass undeclared garbage.
 *   <li>Are stateless — context is always passed in via {@link ExecutionContext}.
 * </ul>
 */
public interface Tool {

  /** Tool name as the LLM sees it (camelCase by convention; e.g. {@code "readFile"}). */
  String name();

  /** Human/LLM-readable description of what this tool does and when to use it. */
  String description();

  /**
   * Strict JSON schema for the tool's input. Must use {@code "type": "object",
   * "additionalProperties": false} and declare every required field in {@code "required": [...]}.
   */
  String inputSchema();

  /**
   * Execute the tool.
   *
   * @param args parsed JSON arguments matching {@link #inputSchema()}
   * @param ctx per-task execution context
   * @return success or typed error
   */
  ToolResult execute(JsonNode args, ExecutionContext ctx);
}
