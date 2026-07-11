package com.navneet.executespec.agentsession;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Typed orchestrator intent classifier.
 *
 * <p>Explicit commands and safety boundaries are deterministic. Unresolved natural-language turns
 * use the shared structured routing prompt through {@link LlmRouter#callText}; malformed,
 * unavailable, or unsafe model output falls back to the existing non-run classifier.
 */
@Service
public class AgentIntentService {

  private static final String ROUTING_PROVIDER = "anthropic";
  private static final String ROUTING_ROLE = "AgentIntentService";
  private static final String ROUTING_STEP = "orchestrator.intent.classify";

  private record StructuredDecision(
      String route,
      Double confidence,
      String reasonCode,
      Boolean requiresUserConfirmation,
      List<String> negativeEvidence) {}

  private final LlmRouter llmRouter;
  private final ModelRegistry modelRegistry;
  private final ObjectMapper objectMapper;
  private final OrchestratorRoutingPromptCatalog promptCatalog;

  /** Compatibility constructor for deterministic callers and tests without a provider transport. */
  public AgentIntentService() {
    this(null, null, new ObjectMapper(), null);
  }

  @Autowired
  public AgentIntentService(
      LlmRouter llmRouter,
      ModelRegistry modelRegistry,
      ObjectMapper objectMapper,
      OrchestratorRoutingPromptCatalog promptCatalog) {
    this.llmRouter = llmRouter;
    this.modelRegistry = modelRegistry;
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    this.promptCatalog = promptCatalog;
  }

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

    // Natural-language turns are classified by the typed routing contract. Explicit commands and
    // safety guards above remain deterministic so an unavailable or malformed model response can
    // never turn a guarded request into a run.
    var structuredDecision = decideWithStructuredClassifier(message, lower, request);
    if (structuredDecision.isPresent()) {
      return structuredDecision.get();
    }

    nonRunDecision = AgentNonRunIntents.decideAfterCodeChange(message, lower, request);
    if (nonRunDecision.isPresent()) {
      return nonRunDecision.get();
    }

    return AgentChatFallbackIntent.decide();
  }

  private java.util.Optional<OrchestratorDecision> decideWithStructuredClassifier(
      String message, String lower, InterpretIntentRequest request) {
    if (llmRouter == null || modelRegistry == null || promptCatalog == null) {
      return java.util.Optional.empty();
    }
    String model = modelRegistry.lightweightModelFor(ROUTING_PROVIDER);
    if (model == null || model.isBlank()) {
      return java.util.Optional.empty();
    }
    try {
      String userPrompt =
          "Classify this orchestrator turn. Return only one JSON object matching the schema.\n\n"
              + objectMapper.writeValueAsString(
                  Map.of("message", message, "context", requestContext(request)));
      ChatResponse response =
          llmRouter.callText(
              ROUTING_PROVIDER,
              model,
              List.of(
                  new SystemMessage(promptCatalog.fullPromptFor(ROUTING_PROVIDER)),
                  new UserMessage(userPrompt)),
              "intent-routing-" + Integer.toHexString(message.hashCode()),
              ROUTING_ROLE,
              ROUTING_STEP,
              400);
      String raw =
          response == null
                  || response.getResult() == null
                  || response.getResult().getOutput() == null
              ? ""
              : response.getResult().getOutput().getText();
      return parseStructuredDecision(raw, message, lower, request);
    } catch (RuntimeException | java.io.IOException e) {
      // Routing is advisory. The deterministic fallback below remains the source of truth when a
      // provider, prompt resource, serializer, or transport is unavailable.
      return java.util.Optional.empty();
    }
  }

  private Map<String, Object> requestContext(InterpretIntentRequest request) {
    if (request == null) return Map.of();
    return Map.of(
        "projectId", request.projectId() == null ? "" : request.projectId(),
        "repoContext", request.repoContext() == null ? Map.of() : request.repoContext(),
        "clientCapabilities",
            request.clientCapabilities() == null ? Map.of() : request.clientCapabilities());
  }

  private java.util.Optional<OrchestratorDecision> parseStructuredDecision(
      String raw, String message, String lower, InterpretIntentRequest request) {
    if (raw == null || raw.isBlank()) return java.util.Optional.empty();
    try {
      JsonNode node = objectMapper.readTree(raw);
      if (node == null
          || !node.isObject()
          || node.size() != 5
          || !node.has("route")
          || !node.has("confidence")
          || !node.has("reasonCode")
          || !node.has("requiresUserConfirmation")
          || !node.has("negativeEvidence")
          || !node.get("route").isTextual()
          || !node.get("confidence").isNumber()
          || !node.get("reasonCode").isTextual()
          || !node.get("requiresUserConfirmation").isBoolean()
          || !node.get("negativeEvidence").isArray()) {
        return java.util.Optional.empty();
      }
      StructuredDecision structured = objectMapper.treeToValue(node, StructuredDecision.class);
      String route = structured.route();
      if (!OrchestratorRoutingPromptCatalog.ROUTES.contains(route)) {
        return java.util.Optional.empty();
      }
      double confidence = structured.confidence();
      String reasonCode = structured.reasonCode();
      if (confidence < 0
          || confidence > 1
          || reasonCode.isBlank()
          || confidence < 0.60
          || structured.negativeEvidence() == null) {
        return java.util.Optional.empty();
      }
      boolean requiresConfirmation = structured.requiresUserConfirmation();
      boolean actionRoute =
          route.equals("CODE_RUN_REQUIRED")
              || route.equals("IMPLEMENTATION_PROPOSAL")
              || route.equals("RUN_LIFECYCLE_ACTION")
              || route.equals("LOCAL_TOOL_ACTION")
              || route.equals("SECURITY_REJECT");
      if (!actionRoute && requiresConfirmation) {
        // The schema is typed but this contradiction would still produce a confusing confirmation
        // card with no action. Treat it as malformed and use the deterministic fallback.
        return java.util.Optional.empty();
      }

      // A model cannot manufacture a lifecycle action or a local side effect. Those actions must
      // continue through their deterministic command parsers and otherwise fail closed.
      if (route.equals("RUN_LIFECYCLE_ACTION") || route.equals("LOCAL_TOOL_ACTION")) {
        return java.util.Optional.empty();
      }
      if (route.equals("SECURITY_REJECT")) {
        return java.util.Optional.of(
            AgentIntentDecisions.decision(
                "SECURITY_REJECT",
                confidence,
                reasonCode,
                false,
                "I cannot help with destructive, exfiltration, or unsafe actions.",
                null,
                false,
                false,
                "Structured classifier marked this request unsafe."));
      }
      if (route.equals("CODE_RUN_REQUIRED") || route.equals("IMPLEMENTATION_PROPOSAL")) {
        // Never let a model promote an ambiguous request to a paid/run-producing action. The
        // deterministic mutation grammar must also recognize an actionable mutation.
        if (!AgentIntentPatterns.looksLikeCodeChange(lower)) {
          return java.util.Optional.of(clarifyDecision());
        }
        var deterministic =
            AgentCodeChangeIntents.decide(message, lower, request, requestCaps(request));
        return deterministic.isPresent() ? deterministic : java.util.Optional.of(clarifyDecision());
      }
      return java.util.Optional.of(
          readOnlyDecision(route, confidence, reasonCode, requiresConfirmation));
    } catch (RuntimeException | java.io.IOException e) {
      return java.util.Optional.empty();
    }
  }

  private ClientCapabilities requestCaps(InterpretIntentRequest request) {
    return request == null || request.clientCapabilities() == null
        ? new ClientCapabilities(false, false, false, false)
        : request.clientCapabilities();
  }

  private OrchestratorDecision clarifyDecision() {
    return AgentIntentDecisions.decision(
        "CLARIFY",
        0.88,
        "structured_classifier_ambiguous_mutation",
        false,
        "What exactly should I change? I need a repo, file, failing test, or error before proposing"
            + " implementation.",
        null,
        false,
        false,
        "Ambiguous mutation cannot create a run without deterministic target evidence.");
  }

  private OrchestratorDecision readOnlyDecision(
      String route, double confidence, String reasonCode, boolean requiresConfirmation) {
    String safeRoute =
        switch (route) {
          case "READ_ONLY_CODE_HELP", "PLAN_ONLY", "REVIEW_ONLY", "CLARIFY", "CHAT_ONLY" -> route;
          default -> "CLARIFY";
        };
    String reply =
        switch (safeRoute) {
          case "READ_ONLY_CODE_HELP" -> "I will inspect or explain this without creating a run.";
          case "PLAN_ONLY" -> "I will write the plan without creating a run.";
          case "REVIEW_ONLY" -> "I will review and return findings without creating a run.";
          case "CLARIFY" -> "Tell me the target or outcome you want me to handle.";
          default -> "I can answer this conversationally without creating a run.";
        };
    return AgentIntentDecisions.decision(
        safeRoute,
        confidence,
        reasonCode,
        requiresConfirmation,
        reply,
        null,
        false,
        false,
        "Structured non-run route forbids new run submission.");
  }
}
