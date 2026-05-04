package com.navneet.executespec.v2.tool;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 completeTask tool. The structured signal that a task is finished.
 *
 * <p>V1 detected completion by checking whether the worker's text response started with
 * {@code "{"} (a strict JSON-prefix heuristic). That broke whenever the model emitted
 * a non-JSON preamble or escaped quotes weirdly. V2 replaces the heuristic with this
 * registered Tool — when the worker calls it, {@link com.navneet.executespec.v2.agent.WorkerAgent}
 * recognizes the structured tool name and exits the loop with the supplied summary.
 *
 * <p>The tool itself is intentionally a no-op at the dispatch level — calling it from
 * outside the worker loop returns a benign Ok envelope with the summary echoed back.
 * The actual loop-termination logic lives in {@code WorkerAgent}, which intercepts
 * the call before dispatch.
 */
@Component
@Slf4j
public class CompleteTaskTool implements Tool {

  public static final String NAME = "completeTask";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Signals that the task is complete. Call this when you have finished all required "
        + "work (files written, edits applied, smoke test passed). Provide a brief summary "
        + "of what you did. After this is called, the agent loop terminates — do not call "
        + "any further tools.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "summary": {
                 "type": "string",
                 "description": "Brief description of what was accomplished"
               }
             },
             "required": ["summary"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String summary = args.path("summary").asText("(no summary)");
    log.info("CompleteTaskTool called (run={} task={}): {}", ctx.runId(), ctx.taskId(), summary);
    return ToolResult.ok("task marked complete: " + summary);
  }
}
