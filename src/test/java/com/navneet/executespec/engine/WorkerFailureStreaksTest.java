package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class WorkerFailureStreaksTest {

  @Test
  void groupsPolicyAndRuntimeUnavailabilityByTool() {
    var first = call("{\"command\":\"python3.12 -V\"}");
    var second = call("{\"command\":\"pip3.12 -V\"}");

    assertThat(
            WorkerFailureStreaks.signature(
                first, "{\"ok\":false,\"kind\":\"FORBIDDEN\",\"message\":\"x\"}"))
        .isEqualTo("shell|FORBIDDEN");
    assertThat(
            WorkerFailureStreaks.signature(
                second, "{\"ok\":false,\"kind\":\"UNAVAILABLE\",\"message\":\"x\"}"))
        .isEqualTo("shell|UNAVAILABLE");
  }

  private static AssistantMessage.ToolCall call(String arguments) {
    return new AssistantMessage.ToolCall("id", "function", "shell", arguments);
  }
}
