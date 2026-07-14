package com.navneet.executespec.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class ProjectChatToolLoopGuardTest {

  @Test
  void onlyForcesFinalizationAfterTheSameToolAndArgumentsRepeat() {
    Map<String, Integer> counts = new HashMap<>();
    AssistantMessage.ToolCall first =
        new AssistantMessage.ToolCall("1", "function", "list_repos", "{}");
    AssistantMessage.ToolCall second =
        new AssistantMessage.ToolCall("2", "function", "get_project_memory", "{}");

    assertFalse(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first)));
    assertFalse(
        ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first, second)));
    assertTrue(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first)));
    assertTrue(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(second)));
    assertTrue(ProjectChatService.registerToolCallsAndDetectRepeat(counts, List.of(first)));
  }
}
