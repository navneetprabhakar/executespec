package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class AnthropicForcedToolClientProviderStartFenceTest {

  @Test
  void invalidLocalToolSchemaFailsBeforeProviderStartSignal() {
    AnthropicForcedToolClient client =
        new AnthropicForcedToolClient(new ObjectMapper(), new AnthropicWorkspaceHeaders("", ""));
    AtomicInteger providerStarted = new AtomicInteger();

    AnthropicForcedToolClient.ForcedToolCallOutcome outcome =
        client.forcedToolCallOutcome(
            "claude-test",
            List.of(new UserMessage("make the edit")),
            List.of(tool("{not-json")),
            128,
            "test-key",
            30,
            providerStarted::incrementAndGet);

    assertThat(outcome.failure()).isNotNull();
    assertThat(providerStarted).hasValue(0);
  }

  @Test
  void durableFenceFailurePropagatesAndPreventsForcedTransport() {
    AnthropicForcedToolClient client =
        new AnthropicForcedToolClient(new ObjectMapper(), new AnthropicWorkspaceHeaders("", ""));
    AtomicInteger providerStarted = new AtomicInteger();

    assertThatThrownBy(
            () ->
                client.forcedToolCallOutcome(
                    "claude-test",
                    List.of(new UserMessage("make the edit")),
                    List.of(tool("{\"type\":\"object\",\"properties\":{}}")),
                    128,
                    "test-key",
                    30,
                    () -> {
                      providerStarted.incrementAndGet();
                      throw new IllegalStateException("durable provider-start fence lost");
                    }))
        .isInstanceOf(ProviderStartFenceException.class)
        .hasRootCauseMessage("durable provider-start fence lost");

    assertThat(providerStarted).hasValue(1);
  }

  private static ToolCallback tool(String schema) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("editFile")
            .description("edit")
            .inputSchema(schema)
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        return "";
      }
    };
  }
}
