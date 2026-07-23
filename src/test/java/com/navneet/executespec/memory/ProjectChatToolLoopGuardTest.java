package com.navneet.executespec.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class ProjectChatToolLoopGuardTest {

  @Test
  void legacyProjectChatExposesOnlyPureReadToolsToTheModel() {
    List<ToolCallback> filtered =
        ProjectChatService.readOnlyModelTools(
            List.of(
                tool("list_repos"),
                tool("search_code"),
                tool("regenerate_architecture"),
                tool("generate_project_doc"),
                tool("run_code_review")));

    assertThat(filtered)
        .extracting(tool -> tool.getToolDefinition().name())
        .containsExactly("list_repos");
  }

  @Test
  void onlyForcesFinalizationAfterTheSameToolAndArgumentsRepeat() {
    Map<String, Integer> counts = new HashMap<>();
    AssistantMessage.ToolCall first =
        new AssistantMessage.ToolCall("1", "function", "list_repos", "{}");
    AssistantMessage.ToolCall second =
        new AssistantMessage.ToolCall("2", "function", "get_project_memory", "{}");

    assertFalse(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first)));
    assertTrue(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first, second)));
    assertTrue(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first)));
    assertTrue(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(second)));
    assertTrue(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first)));
  }

  private static ToolCallback tool(String name) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name(name)
            .description("test")
            .inputSchema("{\"type\":\"object\"}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String input) {
        return "{}";
      }
    };
  }
}
