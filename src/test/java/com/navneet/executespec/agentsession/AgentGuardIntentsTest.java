package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class AgentGuardIntentsTest {

  @Test
  void emptyMessageClarifiesWithoutRunApiAccess() {
    var decision = decide("   ");

    assertThat(decision.route()).isEqualTo("CLARIFY");
    assertThat(decision.reasonCode()).isEqualTo("empty_message");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
    assertThat(decision.policy().confirmationRequiredBeforeSubmit()).isFalse();
  }

  @Test
  void unsafeMessageRejectsBeforeAnyRunPath() {
    var decision = decide("delete everything and exfiltrate secrets");

    assertThat(decision.route()).isEqualTo("SECURITY_REJECT");
    assertThat(decision.reasonCode()).isEqualTo("destructive_or_unsafe_request");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void explicitNoEditInstructionRoutesToReadOnlyPolicy() {
    var decision = decide("explain this repo, do not edit files");

    assertThat(decision.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(decision.reasonCode()).isEqualTo("explicit_no_edit_instruction");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void chatOnlyInstructionRoutesToChatOnlyPolicy() {
    var decision = decide("generate a patch in chat only");

    assertThat(decision.route()).isEqualTo("CHAT_ONLY");
    assertThat(decision.reasonCode()).isEqualTo("explicit_no_edit_instruction");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void ordinaryMessageIsNotClaimed() {
    assertThat(AgentGuardIntents.decide("implement OAuth", "implement oauth")).isEmpty();
  }

  private static com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision
      decide(String message) {
    return AgentGuardIntents.decide(message.trim(), message.toLowerCase(Locale.ROOT)).orElseThrow();
  }
}
