package com.navneet.executespec.agentsession;

import java.util.Locale;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;

import org.springframework.stereotype.Service;

/**
 * Conservative first-pass intent classifier.
 *
 * <p>It intentionally does not call an LLM yet. This gives CLI/VS Code a deterministic typed-action
 * contract while preserving billing: cost-bearing/destructive actions are proposals that require a
 * separate confirmation step.
 */
@Service
public class AgentIntentService {

  public IntentDecision interpret(InterpretIntentRequest request) {
    return AgentIntentLegacyAdapter.toLegacy(decide(request));
  }

  public OrchestratorDecision decide(InterpretIntentRequest request) {
    String message = request == null || request.message() == null ? "" : request.message().trim();
    String lower = message.toLowerCase(Locale.ROOT);
    ClientCapabilities caps = request == null ? null : request.clientCapabilities();

    var guardDecision = AgentGuardIntents.decide(message, lower);
    if (guardDecision.isPresent()) {
      return guardDecision.get();
    }

    var lifecycleDecision = AgentRunLifecycleIntents.decide(message, lower);
    if (lifecycleDecision.isPresent()) {
      return lifecycleDecision.get();
    }

    var nonRunDecision = AgentNonRunIntents.decideBeforeCodeChange(message, lower, request);
    if (nonRunDecision.isPresent()) {
      return nonRunDecision.get();
    }

    var codeChangeDecision = AgentCodeChangeIntents.decide(message, lower, request, caps);
    if (codeChangeDecision.isPresent()) {
      return codeChangeDecision.get();
    }

    nonRunDecision = AgentNonRunIntents.decideAfterCodeChange(message, lower, request);
    if (nonRunDecision.isPresent()) {
      return nonRunDecision.get();
    }

    return AgentChatFallbackIntent.decide();
  }
}
