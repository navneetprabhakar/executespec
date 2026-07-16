package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LlmRouterGaneshRoleGuardTest {
  @Test
  void onlyWorkerAgentMayReachGaneshTransport() {
    assertThatCode(() -> LlmRouter.requireGaneshWorkerRole("WorkerAgent"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> LlmRouter.requireGaneshWorkerRole("OutlinePlannerAgent"))
        .hasMessageContaining("restricted to WorkerAgent");
    assertThatThrownBy(() -> LlmRouter.requireGaneshWorkerRole("ReviewerAgent"))
        .hasMessageContaining("restricted to WorkerAgent");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ganeshWorkerCallsExplicitlyDisableGemmaThinking() {
    Map<String, Object> extraBody = LlmRouter.ganeshExtraBody();

    assertThat(extraBody).containsOnlyKeys("chat_template_kwargs");
    assertThat((Map<String, Object>) extraBody.get("chat_template_kwargs"))
        .containsExactlyEntriesOf(Map.of("enable_thinking", false));
  }
}
