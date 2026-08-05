package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class ChatToolPolicyTest {

  /**
   * Deliberately a literal, not a derivation: the 2026-08-01 pipeline retry-ladder bump silently
   * inflated this ceiling 24 → 36 because it was derived from the pipeline constant. Chat turns run
   * on the INTERACTIVE retry profile; if this assertion fails, a retry-envelope change leaked into
   * the chat provider-call budget again.
   */
  @Test
  void providerCallCeilingPerTurnIsPinnedAtTwentyFour() {
    assertThat(ChatToolPolicy.MAX_PROVIDER_CALLS_PER_TURN).isEqualTo(24);
    assertThat(ChatToolPolicy.MAX_PROVIDER_ATTEMPTS_PER_MODEL_ROUND).isEqualTo(4);
  }

  @Test
  void explicitlyClassifiesReadAnalyseSpendAndWriteTools() {
    assertThat(ChatToolPolicy.capability("list_repos"))
        .isEqualTo(ChatToolPolicy.Capability.PROJECT_READ);
    assertThat(ChatToolPolicy.capability("read_repo_file_range"))
        .isEqualTo(ChatToolPolicy.Capability.PROJECT_READ);
    assertThat(ChatToolPolicy.capability("list_public_http_surface"))
        .isEqualTo(ChatToolPolicy.Capability.PROJECT_READ);
    assertThat(ChatToolPolicy.capability("search_code"))
        .isEqualTo(ChatToolPolicy.Capability.PROJECT_ANALYSE);
    assertThat(ChatToolPolicy.capability("submit_run"))
        .isEqualTo(ChatToolPolicy.Capability.SPEND_AI);
    assertThat(ChatToolPolicy.capability("approve_plan"))
        .isEqualTo(ChatToolPolicy.Capability.WRITE_REPO);
    assertThat(ChatToolPolicy.requiredCapabilities("regenerate_architecture"))
        .containsExactlyInAnyOrder(
            ChatToolPolicy.Capability.PROJECT_ANALYSE,
            ChatToolPolicy.Capability.SPEND_AI,
            ChatToolPolicy.Capability.WRITE_REPO);
  }

  @Test
  void unknownToolFailsClosedAndReadOnlyBindingDropsSpendAndMutation() {
    assertThat(ChatToolPolicy.isExplicitlyClassified("invented_tool")).isFalse();
    assertThat(ChatToolPolicy.capability("invented_tool"))
        .isEqualTo(ChatToolPolicy.Capability.WRITE_REPO);

    List<ToolCallback> filtered =
        ChatToolPolicy.readOnly(
            List.of(tool("list_repos"), tool("submit_run"), tool("approve_plan"), tool("new")));

    assertThat(filtered)
        .extracting(tool -> tool.getToolDefinition().name())
        .containsExactly("list_repos");
  }

  @Test
  void everyNonReadToolHasAConfirmationHandlerOrExplicitFailSafeDisposition() {
    assertThat(ChatToolPolicy.classifications())
        .allSatisfy(
            (name, capability) -> {
              if (capability != ChatToolPolicy.Capability.PROJECT_READ) {
                assertThat(ChatToolPolicy.confirmationDisposition(name))
                    .as("confirmation disposition for %s", name)
                    .isNotNull();
              }
            });
    assertThat(ChatToolPolicy.confirmationDisposition("regenerate_architecture"))
        .isEqualTo(ChatToolPolicy.ConfirmationDisposition.PROJECT_CHAT_CONFIRMATION);
    assertThat(ChatToolPolicy.confirmationDisposition("submit_run"))
        .isEqualTo(ChatToolPolicy.ConfirmationDisposition.EXTERNAL_STRUCTURED_PROTOCOL);
    assertThat(ChatToolPolicy.confirmationDisposition("spawn_subagent"))
        .isEqualTo(ChatToolPolicy.ConfirmationDisposition.PERMANENTLY_UNBOUND);
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
