package com.navneet.executespec.v2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Pins the worker-tool deny list. {@link ToolRegistryV2#bindForWorker} must:
 *
 * <ul>
 *   <li>Exclude every name in {@link ToolRegistryV2#WORKER_DENY_TOOLS} (currently {@code
 *       gitCommit}).
 *   <li>Otherwise return the same set as {@link ToolRegistryV2#bind}, in the same order.
 * </ul>
 *
 * <p>Diagnosed from run {@code a076c876} task T2 (2026-05-03): the worker burned 22 of its 60
 * iterations cycling through {@code git status} / {@code git add} / {@code gitCommit} after the
 * actual code change had already been applied. Removing {@code gitCommit} from the worker's
 * authorities makes that runaway structurally impossible. If a future change adds a new
 * commit-flavored tool, this test pins the default-deny posture so it stays out of the worker bind
 * unless deliberately allowed.
 *
 * <p>Pure unit test — uses fake {@link Tool} stubs so no Spring context is needed.
 */
class ToolRegistryV2WorkerBindTest {

  private static Tool fakeTool(String name) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "stub-" + name;
      }

      @Override
      public String inputSchema() {
        return "{\"type\":\"object\",\"additionalProperties\":false}";
      }

      @Override
      public ToolResult execute(JsonNode args, ExecutionContext ctx) {
        return new ToolResult.Ok("ok");
      }
    };
  }

  private static ExecutionContext ctx() {
    return new ExecutionContext("run-1", "T1", java.nio.file.Path.of("/tmp/ws"));
  }

  @Test
  void bindForWorker_excludes_gitCommit() {
    ToolRegistryV2 registry =
        new ToolRegistryV2(
            List.of(
                fakeTool("readFile"),
                fakeTool("writeFile"),
                fakeTool("editFile"),
                fakeTool("gitStatus"),
                fakeTool("gitDiff"),
                fakeTool("gitCommit"),
                fakeTool("completeTask")),
            new ObjectMapper());

    List<ToolCallback> workerTools = registry.bindForWorker(ctx());
    List<String> workerNames = workerTools.stream().map(t -> t.getToolDefinition().name()).toList();

    assertFalse(
        workerNames.contains("gitCommit"),
        "Worker bind must exclude gitCommit (see ToolRegistryV2.WORKER_DENY_TOOLS).");
    // Read-only git tools should remain available — they're useful for inspecting state.
    assertTrue(workerNames.contains("gitStatus"), "gitStatus should stay in worker bind");
    assertTrue(workerNames.contains("gitDiff"), "gitDiff should stay in worker bind");
    // Core editing tools must remain.
    assertTrue(workerNames.contains("readFile"));
    assertTrue(workerNames.contains("writeFile"));
    assertTrue(workerNames.contains("editFile"));
    assertTrue(workerNames.contains("completeTask"));
    assertEquals(6, workerTools.size(), "Expected 6 tools after deny-list filtering (was 7).");
  }

  @Test
  void bind_keeps_gitCommit_for_non_worker_callers() {
    ToolRegistryV2 registry =
        new ToolRegistryV2(
            List.of(fakeTool("writeFile"), fakeTool("gitCommit"), fakeTool("completeTask")),
            new ObjectMapper());

    List<ToolCallback> all = registry.bind(ctx());
    List<String> names = all.stream().map(t -> t.getToolDefinition().name()).toList();

    assertTrue(
        names.contains("gitCommit"),
        "Non-worker bind() must keep gitCommit — reviewer / orchestration paths still need it.");
    assertEquals(3, all.size());
  }

  @Test
  void deny_set_pins_gitCommit() {
    // Independent of any registry instance — guards against a future commit
    // accidentally emptying the deny set.
    assertNotNull(ToolRegistryV2.WORKER_DENY_TOOLS);
    assertTrue(
        ToolRegistryV2.WORKER_DENY_TOOLS.contains("gitCommit"),
        "WORKER_DENY_TOOLS must keep gitCommit. If you're intentionally re-allowing"
            + " gitCommit for the worker, update this test with the rationale.");
  }
}
