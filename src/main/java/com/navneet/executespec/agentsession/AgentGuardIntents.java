package com.navneet.executespec.agentsession;

import static com.navneet.executespec.agentsession.AgentIntentDecisions.decision;

import java.util.Optional;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;

final class AgentGuardIntents {

  private AgentGuardIntents() {}

  static Optional<OrchestratorDecision> decide(String message, String lower) {
    if (message.isBlank()) {
      return Optional.of(
          decision(
              "CLARIFY",
              0.99,
              "empty_message",
              false,
              "Tell me what you want to inspect, plan, approve, or change.",
              null,
              false,
              false,
              "No user message was provided."));
    }

    if (AgentIntentPatterns.isUnsafe(lower)) {
      return Optional.of(
          decision(
              "SECURITY_REJECT",
              0.94,
              "destructive_or_unsafe_request",
              false,
              "I cannot help with destructive, exfiltration, or unsafe actions.",
              null,
              false,
              false,
              "Unsafe request cannot create a run."));
    }

    if (AgentIntentPatterns.forbidsEdits(lower)) {
      return Optional.of(
          decision(
              AgentIntentPatterns.routeForReadOnly(lower),
              0.96,
              "explicit_no_edit_instruction",
              false,
              "I will keep this as a non-mutating answer and will not create a run.",
              null,
              false,
              false,
              "User explicitly requested no edits or chat-only handling."));
    }

    return Optional.empty();
  }
}
