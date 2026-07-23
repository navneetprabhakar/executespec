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
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.InputSanitizer;

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

  private static final String ROUTING_ROLE = "AgentIntentService";
  private static final String ROUTING_STEP = "orchestrator.intent.classify";

  /** Deterministic-branch identifiers for {@link RouteAttribution#modelProvider()}. */
  public static final String ATTRIBUTION_DETERMINISTIC = "deterministic";

  /** Prompt identifier recorded when no classifier call shaped the route. */
  public static final String DETERMINISTIC_PROMPT_ID = "orchestrator-deterministic-pre-router";

  /**
   * Which engine actually produced a route decision.
   *
   * <p>The orchestrator trace is durable routing evidence reconciled against {@code
   * run_step_usage}. Recording every route as deterministic while a paid classify attempt exists in
   * the usage ledger makes that reconciliation lie, so the branch is reported explicitly.
   *
   * @param classifierAttempted true when a provider classify call was actually dispatched, even if
   *     its output was unusable and the deterministic fallback decided the route
   */
  public record RouteAttribution(
      String modelProvider, String model, String promptId, boolean classifierAttempted) {

    public static RouteAttribution deterministic(boolean classifierAttempted) {
      return new RouteAttribution(
          ATTRIBUTION_DETERMINISTIC, "none", DETERMINISTIC_PROMPT_ID, classifierAttempted);
    }

    public static RouteAttribution classifier(String provider, String model, String promptId) {
      return new RouteAttribution(provider, model, promptId, true);
    }
  }

  /** A route decision together with the engine that produced it. */
  public record RoutedDecision(OrchestratorDecision decision, RouteAttribution attribution) {}

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
  private final InputSanitizer inputSanitizer;
  private final ContextSanitizer contextSanitizer;
  private final LlmExecutionPolicyFactory executionPolicyFactory;

  /** Compatibility constructor for deterministic callers and tests without a provider transport. */
  public AgentIntentService() {
    this(null, null, new ObjectMapper(), null, new InputSanitizer(), new ContextSanitizer(), null);
  }

  public AgentIntentService(
      LlmRouter llmRouter,
      ModelRegistry modelRegistry,
      ObjectMapper objectMapper,
      OrchestratorRoutingPromptCatalog promptCatalog) {
    this(
        llmRouter,
        modelRegistry,
        objectMapper,
        promptCatalog,
        new InputSanitizer(),
        new ContextSanitizer(),
        null);
  }

  public AgentIntentService(
      LlmRouter llmRouter,
      ModelRegistry modelRegistry,
      ObjectMapper objectMapper,
      OrchestratorRoutingPromptCatalog promptCatalog,
      InputSanitizer inputSanitizer,
      ContextSanitizer contextSanitizer) {
    this(
        llmRouter,
        modelRegistry,
        objectMapper,
        promptCatalog,
        inputSanitizer,
        contextSanitizer,
        null);
  }

  @Autowired
  public AgentIntentService(
      LlmRouter llmRouter,
      ModelRegistry modelRegistry,
      ObjectMapper objectMapper,
      OrchestratorRoutingPromptCatalog promptCatalog,
      InputSanitizer inputSanitizer,
      ContextSanitizer contextSanitizer,
      LlmExecutionPolicyFactory executionPolicyFactory) {
    this.llmRouter = llmRouter;
    this.modelRegistry = modelRegistry;
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    this.promptCatalog = promptCatalog;
    this.inputSanitizer = inputSanitizer;
    this.contextSanitizer = contextSanitizer;
    this.executionPolicyFactory = executionPolicyFactory;
  }

  public IntentDecision interpret(InterpretIntentRequest request) {
    return AgentIntentLegacyAdapter.toLegacy(decide(request));
  }

  public OrchestratorDecision decide(InterpretIntentRequest request) {
    return decide(request, null, null);
  }

  /**
   * Classify using the effective session route. The route decision is advisory, but it must use the
   * same provider/model selected for the session instead of silently spending through a platform
   * default. A blank route skips model classification and uses deterministic routing.
   */
  public OrchestratorDecision decide(
      InterpretIntentRequest request, String effectiveProvider, String effectiveModel) {
    return decideWithAttribution(request, effectiveProvider, effectiveModel).decision();
  }

  /**
   * Classify and report which engine decided. Callers that persist durable routing evidence must
   * use this so the trace cannot claim a deterministic rule for a paid classifier decision.
   */
  public RoutedDecision decideWithAttribution(
      InterpretIntentRequest request, String effectiveProvider, String effectiveModel) {
    RouteAttribution[] attribution = {RouteAttribution.deterministic(false)};
    OrchestratorDecision decision = route(request, effectiveProvider, effectiveModel, attribution);
    return new RoutedDecision(decision, attribution[0]);
  }

  private OrchestratorDecision route(
      InterpretIntentRequest request,
      String effectiveProvider,
      String effectiveModel,
      RouteAttribution[] attribution) {
    String raw = request == null || request.message() == null ? "" : request.message().trim();
    String message = sanitizeMessage(raw);
    if (request != null) {
      request =
          new InterpretIntentRequest(
              request.sessionId(),
              message,
              request.projectId(),
              request.repoContext(),
              request.clientCapabilities());
    }
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
    var structuredDecision =
        decideWithStructuredClassifier(
            message, lower, request, effectiveProvider, effectiveModel, attribution);
    if (structuredDecision.isPresent()) {
      return structuredDecision.get();
    }

    nonRunDecision = AgentNonRunIntents.decideAfterCodeChange(message, lower, request);
    if (nonRunDecision.isPresent()) {
      return nonRunDecision.get();
    }

    return AgentChatFallbackIntent.decide();
  }

  private String sanitizeMessage(String raw) {
    String normalized = inputSanitizer.normalize(raw == null ? "" : raw);
    int localContext = normalized.indexOf("EXECUTESPEC_LOCAL_CONTEXT:");
    String instruction = localContext < 0 ? normalized : normalized.substring(0, localContext);
    inputSanitizer.scan(instruction);
    if (localContext < 0) return normalized;
    return instruction
        + "\n\n"
        + com.navneet.executespec.agent.PromptTrustBoundary.wrap(
            "LOCAL_CONTEXT",
            contextSanitizer.sanitizeInput(
                normalized.substring(localContext), "intent-local-context"));
  }

  private java.util.Optional<OrchestratorDecision> decideWithStructuredClassifier(
      String message,
      String lower,
      InterpretIntentRequest request,
      String effectiveProvider,
      String effectiveModel,
      RouteAttribution[] attribution) {
    if (llmRouter == null || modelRegistry == null || promptCatalog == null) {
      return java.util.Optional.empty();
    }
    String provider = normalizeRoutingProvider(effectiveProvider, effectiveModel);
    // No provider means the caller's account/session/org policy did not resolve an allowed route.
    // Intent classification is advisory, so fail closed to the deterministic classifier instead
    // of spending through a hidden platform-provider default.
    if (provider == null) {
      return java.util.Optional.empty();
    }
    String model =
        effectiveModel == null || effectiveModel.isBlank()
            ? modelRegistry.lightweightModelFor(provider)
            : modelRegistry.canonicalize(effectiveModel);
    if (model == null || model.isBlank()) {
      return java.util.Optional.empty();
    }
    String promptId = "orchestrator-routing-" + promptProvider(provider);
    try {
      String userPrompt =
          "Classify this orchestrator turn. Return only one JSON object matching the schema.\n\n"
              + objectMapper.writeValueAsString(
                  Map.of("message", message, "context", requestContext(request)));
      List<org.springframework.ai.chat.messages.Message> messages =
          List.of(
              new SystemMessage(promptCatalog.fullPromptFor(promptProvider(provider))),
              new UserMessage(userPrompt));
      ChatResponse response;
      if (executionPolicyFactory == null) {
        // The attempt is a fact from here on, regardless of outcome: a transport failure or a
        // malformed reply must still record classifierAttempted in the durable trace.
        attribution[0] = RouteAttribution.deterministic(true);
        response =
            llmRouter.callText(
                provider,
                model,
                messages,
                "intent-routing-"
                    + turnDigest(request == null ? null : request.sessionId(), message),
                ROUTING_ROLE,
                ROUTING_STEP,
                400);
      } else {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
          return java.util.Optional.empty();
        }
        String digest = turnDigest(request.sessionId(), message);
        var executionReference =
            executionPolicyFactory.forAgentSession(request.sessionId(), "intent-" + digest);
        attribution[0] = RouteAttribution.deterministic(true);
        response =
            llmRouter.callText(
                provider,
                model,
                messages,
                "intent-routing-" + digest,
                ROUTING_ROLE,
                ROUTING_STEP,
                400,
                com.navneet.executespec.agent.LlmRequest.PromptCacheStrategy.DEFAULT,
                executionReference);
      }
      String raw =
          response == null
                  || response.getResult() == null
                  || response.getResult().getOutput() == null
              ? ""
              : response.getResult().getOutput().getText();
      var parsed = parseStructuredDecision(raw, message, lower, request);
      if (parsed.isPresent()) {
        attribution[0] = RouteAttribution.classifier(provider, model, promptId);
      }
      return parsed;
    } catch (RuntimeException | java.io.IOException e) {
      // Routing is advisory. The deterministic fallback below remains the source of truth when a
      // provider, prompt resource, serializer, or transport is unavailable.
      return java.util.Optional.empty();
    }
  }

  /**
   * RR-667 — a collision-resistant per-turn execution/step identity. The prior key was {@code
   * Integer.toHexString(message.hashCode())}: a 32-bit hash with trivial chosen collisions, so two
   * different messages in one session could share the same {@code intent-<id>} execution reference
   * and {@code intent-routing-<id>} usage attribution. The digest binds the session to the message
   * under SHA-256, so distinct turns never share attribution. A missing session id still yields a
   * stable per-message digest rather than a colliding hash.
   */
  static String turnDigest(String sessionId, String message) {
    String seed = (sessionId == null ? "" : sessionId) + "|" + (message == null ? "" : message);
    try {
      byte[] hash =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(16);
      for (int i = 0; i < 8; i++) {
        sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
        sb.append(Character.forDigit(hash[i] & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      // SHA-256 is always present on a supported JRE; degrade to a unique, non-colliding token.
      return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
  }

  private String normalizeRoutingProvider(String provider, String model) {
    String requested = provider == null ? "" : provider.trim();
    if (!requested.isBlank()) return requested.toLowerCase(Locale.ROOT);
    if (model != null && !model.isBlank()) {
      String inferred = modelRegistry.providerFor(model);
      if (inferred != null && !inferred.isBlank()) return inferred.toLowerCase(Locale.ROOT);
    }
    return null;
  }

  private String promptProvider(String provider) {
    return switch (provider) {
      case "openrouter", "fireworks" -> "deepseek";
      default -> provider;
    };
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
