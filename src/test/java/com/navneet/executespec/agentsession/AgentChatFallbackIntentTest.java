package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentChatFallbackIntentTest {

  @Test
  void chatFallbackNeverAllowsRunApi() {
    var decision = AgentChatFallbackIntent.decide();

    assertThat(decision.route()).isEqualTo("CHAT_ONLY");
    assertThat(decision.confidence()).isEqualTo(0.72);
    assertThat(decision.reasonCode()).isEqualTo("general_chat");
    assertThat(decision.requiresUserConfirmation()).isFalse();
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
    assertThat(decision.policy().confirmationRequiredBeforeSubmit()).isFalse();
    assertThat(decision.policy().reason()).isEqualTo("General chat must not create runs.");
  }
}
