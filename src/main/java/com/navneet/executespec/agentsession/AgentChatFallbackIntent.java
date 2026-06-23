package com.navneet.executespec.agentsession;

import static com.navneet.executespec.agentsession.AgentIntentDecisions.decision;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;

final class AgentChatFallbackIntent {

  private AgentChatFallbackIntent() {}

  static OrchestratorDecision decide() {
    return decision(
        "CHAT_ONLY",
        0.72,
        "general_chat",
        false,
        "I can answer this conversationally, or turn it into a typed action if you ask to run,"
            + " approve, inspect, or change something.",
        null,
        false,
        false,
        "General chat must not create runs.");
  }
}
