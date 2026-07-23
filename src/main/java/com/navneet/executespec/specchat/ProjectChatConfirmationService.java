package com.navneet.executespec.specchat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.executioncontext.LlmExecutionBudget;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.RunModelOverrides;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Issues and consumes restart-safe Project Chat confirmations. The model never receives the bearer
 * token. Web/CLI request one typed scope, display it to the human, then echo that exact scope with
 * the one-time token to execute it.
 */
@Service
@RequiredArgsConstructor
public class ProjectChatConfirmationService {

  public enum Action {
    REGENERATE_ARCHITECTURE(
        "ArchitectureDocGenerator",
        "regenerate_architecture",
        List.of(
            ChatToolPolicy.Capability.PROJECT_ANALYSE,
            ChatToolPolicy.Capability.SPEND_AI,
            ChatToolPolicy.Capability.WRITE_REPO),
        new LlmExecutionBudget(null, null, 1_000_000L, BigDecimal.valueOf(10), 64)),
    GENERATE_PROJECT_DOC(
        "ProjectDocGeneratorService",
        "generate_project_doc",
        List.of(ChatToolPolicy.Capability.SPEND_AI),
        new LlmExecutionBudget(null, null, 200_000L, BigDecimal.valueOf(3), 2)),
    RUN_CODE_REVIEW(
        "CodeReviewAgent",
        "run_code_review",
        List.of(ChatToolPolicy.Capability.PROJECT_ANALYSE, ChatToolPolicy.Capability.SPEND_AI),
        new LlmExecutionBudget(null, null, 300_000L, BigDecimal.valueOf(5), 2));

    private final String agentRole;
    private final String toolName;
    private final List<ChatToolPolicy.Capability> capabilities;
    private final LlmExecutionBudget budget;

    Action(
        String agentRole,
        String toolName,
        List<ChatToolPolicy.Capability> capabilities,
        LlmExecutionBudget budget) {
      this.agentRole = agentRole;
      this.toolName = toolName;
      this.capabilities = List.copyOf(capabilities);
      this.budget = budget;
    }

    String agentRole() {
      return agentRole;
    }

    String toolName() {
      return toolName;
    }

    List<ChatToolPolicy.Capability> capabilities() {
      return capabilities;
    }

    LlmExecutionBudget budget() {
      return budget;
    }
  }

  public record ActionScope(
      String action, String provider, String model, Map<String, String> target) {}

  /** Secret-free reference safe to persist in agent policy/action state. */
  public record ConfirmationReference(
      UUID confirmationId,
      String projectId,
      String action,
      List<String> capabilities,
      String provider,
      String model,
      String targetHash,
      Instant expiresAt,
      boolean consumed,
      ConfirmationBudget budget) {}

  public record ConfirmationBudget(
      long maxTotalTokens, BigDecimal maxCostUsd, int maxProviderCalls) {}

  /** Bearer token appears exactly once in this issue response and is never persisted or logged. */
  public record IssuedConfirmation(ConfirmationReference confirmation, String confirmationToken) {}

  public record ExecutionResult(ConfirmationReference confirmation, Map<String, Object> result) {}

  private static final int TOKEN_BYTES = 32;
  private static final int MAX_TARGET_VALUE_CHARS = 256;

  private final ProjectChatConfirmationRepository repository;
  private final ProjectChatConfirmationConsumer consumer;
  private final ProjectChatConfirmedActionExecutor actionExecutor;
  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectProviderResolver projectProviderResolver;
  private final ModelResolver modelResolver;
  private final OrgModelOverrideService orgModelOverrideService;
  private final ChatSecurityAuditService auditService;
  private final ObjectMapper objectMapper;
  private final SecureRandom secureRandom = new SecureRandom();

  @Value("${chat.confirmation.ttl-seconds:300}")
  private long configuredTtlSeconds = 300;

  public IssuedConfirmation issue(
      String projectId, AppUserDetails principal, ActionScope requestedScope) {
    Project project = authorize(projectId, principal, "CHAT_CONFIRMATION_ISSUE_DENIED", null);
    Action action;
    NormalizedScope scope;
    try {
      action = parseAction(requestedScope == null ? null : requestedScope.action());
      scope = normalizeAndVerify(project, principal, action, requestedScope);
    } catch (RuntimeException denied) {
      audit("CHAT_CONFIRMATION_DENIED", project, principal, null, requestedScope, null, "DENIED");
      throw denied;
    }

    audit(
        "CHAT_CONFIRMATION_ALLOWED",
        project,
        principal,
        null,
        requestedScope,
        scope.targetHash(),
        "ALLOWED");

    byte[] tokenBytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(tokenBytes);
    String token =
        "es_chat_confirm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Instant expiresAt = issuedAt.plusSeconds(Math.max(30, Math.min(600, configuredTtlSeconds)));
    String actorId = principal.getUser().getId();
    String scopeHash =
        scopeHash(
            projectId,
            project.getOrgId(),
            actorId,
            action,
            scope.provider(),
            scope.model(),
            scope.targetJson());
    ProjectChatConfirmation confirmation =
        repository.save(
            ProjectChatConfirmation.builder()
                .projectId(projectId)
                .orgId(project.getOrgId())
                .actorUserId(actorId)
                .action(action.name())
                .capability(joinCapabilities(action))
                .provider(scope.provider())
                .model(scope.model())
                .targetJson(scope.targetJson())
                .targetHash(scope.targetHash())
                .scopeHash(scopeHash)
                .tokenHash(sha256(token))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build());
    audit(
        "CHAT_CONFIRMATION_ISSUED",
        project,
        principal,
        confirmation.getId(),
        requestedScope,
        scope.targetHash(),
        "ISSUED");
    return new IssuedConfirmation(reference(confirmation), token);
  }

  /**
   * Re-authorizes, consumes in a separate committed transaction, then executes. A downstream
   * failure does not resurrect authority: callers must request a new explicit confirmation.
   */
  public ExecutionResult consumeAndExecute(
      String projectId,
      UUID confirmationId,
      AppUserDetails principal,
      String confirmationToken,
      ActionScope echoedScope) {
    Project project =
        authorize(projectId, principal, "CHAT_CONFIRMATION_CONSUME_DENIED", confirmationId);
    Action action;
    NormalizedScope normalized;
    try {
      action = parseAction(echoedScope == null ? null : echoedScope.action());
      normalized = normalizeAndVerify(project, principal, action, echoedScope);
      if (confirmationToken == null || !confirmationToken.startsWith("es_chat_confirm_")) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid confirmation token");
      }
    } catch (RuntimeException denied) {
      audit(
          "CHAT_CONFIRMATION_DENIED",
          project,
          principal,
          confirmationId,
          echoedScope,
          null,
          "DENIED");
      throw denied;
    }

    String actorId = principal.getUser().getId();
    String echoedScopeHash =
        scopeHash(
            projectId,
            project.getOrgId(),
            actorId,
            action,
            normalized.provider(),
            normalized.model(),
            normalized.targetJson());
    ProjectChatConfirmationConsumer.Attempt attempt =
        consumer.consume(
            confirmationId,
            projectId,
            project.getOrgId(),
            actorId,
            sha256(confirmationToken),
            echoedScopeHash);
    auditAttempt(project, principal, confirmationId, echoedScope, normalized.targetHash(), attempt);
    if (attempt.outcome() != ProjectChatConfirmationConsumer.Outcome.CONSUMED) {
      throw consumeFailure(attempt.outcome());
    }

    Map<String, Object> result;
    try {
      result = actionExecutor.execute(action, attempt.confirmation(), principal);
      audit(
          "CHAT_CONFIRMED_ACTION_COMPLETED",
          project,
          principal,
          confirmationId,
          echoedScope,
          normalized.targetHash(),
          "COMPLETED");
    } catch (RuntimeException failed) {
      audit(
          "CHAT_CONFIRMED_ACTION_FAILED",
          project,
          principal,
          confirmationId,
          echoedScope,
          normalized.targetHash(),
          "FAILED");
      throw failed;
    }
    return new ExecutionResult(reference(attempt.confirmation()), Map.copyOf(result));
  }

  private Project authorize(
      String projectId, AppUserDetails principal, String deniedEvent, UUID confirmationId) {
    try {
      return projectAccessGuard.assertCanWrite(projectId, principal);
    } catch (RuntimeException denied) {
      auditService.recordConfirmation(
          deniedEvent,
          projectId,
          null,
          principal,
          confirmationId == null ? null : confirmationId.toString(),
          null,
          null,
          null,
          null,
          "DENIED");
      throw denied;
    }
  }

  private NormalizedScope normalizeAndVerify(
      Project project, AppUserDetails principal, Action action, ActionScope requested) {
    if (requested == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action scope is required");
    }
    LlmProvider provider = parseProvider(requested.provider());
    LlmProvider projectProvider = projectProviderResolver.resolve(project);
    if (provider != projectProvider) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "provider does not match current project policy");
    }
    Map<String, String> previousOverrides = RunModelOverrides.capture();
    var resolved =
        resolveWithOrgOverrides(
            project.getOrgId(), action.agentRole(), provider, previousOverrides);
    if (resolved.getActualProvider() != provider) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "adaptive provider routing cannot be used for a scoped confirmed action");
    }
    String model = requireText(requested.model(), "model", 160);
    if (!MessageDigest.isEqual(
        resolved.getModel().getBytes(StandardCharsets.UTF_8),
        model.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "model does not match the current action route");
    }
    Map<String, String> target = canonicalTarget(action, requested.target());
    String targetJson = writeCanonical(target);
    return new NormalizedScope(
        provider.name().toLowerCase(Locale.ROOT), model, targetJson, sha256(targetJson));
  }

  private com.navneet.executespec.router.ResolvedModel resolveWithOrgOverrides(
      String orgId, String agentRole, LlmProvider provider, Map<String, String> previousOverrides) {
    RunModelOverrides.bind(orgModelOverrideService.overridesForRun(orgId));
    try {
      return modelResolver.resolve(agentRole, provider);
    } finally {
      RunModelOverrides.bind(previousOverrides);
    }
  }

  private Map<String, String> canonicalTarget(Action action, Map<String, String> raw) {
    Map<String, String> input = raw == null ? Map.of() : raw;
    Set<String> allowed =
        switch (action) {
          case REGENERATE_ARCHITECTURE -> Set.of("branch");
          case GENERATE_PROJECT_DOC -> Set.of("docType");
          case RUN_CODE_REVIEW -> Set.of("repoId");
        };
    if (!allowed.containsAll(input.keySet())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "target contains fields not allowed for " + action.name());
    }
    Map<String, String> canonical = new TreeMap<>();
    switch (action) {
      case REGENERATE_ARCHITECTURE -> {
        String branch = input.get("branch");
        if (branch != null && !branch.isBlank()) validateBranch(branch);
        canonical.put("branch", branch == null ? "" : branch.trim());
      }
      case GENERATE_PROJECT_DOC -> {
        String docType = requireText(input.get("docType"), "target.docType", 24).toUpperCase();
        if (!Set.of("EXPLANATION", "SETUP").contains(docType)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "target.docType must be EXPLANATION or SETUP");
        }
        canonical.put("docType", docType);
      }
      case RUN_CODE_REVIEW -> {
        String repoId = requireText(input.get("repoId"), "target.repoId", 64);
        try {
          canonical.put("repoId", UUID.fromString(repoId).toString());
        } catch (IllegalArgumentException invalid) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target.repoId must be a UUID");
        }
      }
    }
    return canonical;
  }

  private void auditAttempt(
      Project project,
      AppUserDetails principal,
      UUID id,
      ActionScope scope,
      String targetHash,
      ProjectChatConfirmationConsumer.Attempt attempt) {
    String event =
        switch (attempt.outcome()) {
          case CONSUMED -> "CHAT_CONFIRMATION_CONSUMED";
          case REPLAY -> "CHAT_CONFIRMATION_REPLAY_DENIED";
          case EXPIRED -> "CHAT_CONFIRMATION_EXPIRED";
          default -> "CHAT_CONFIRMATION_DENIED";
        };
    audit(event, project, principal, id, scope, targetHash, attempt.outcome().name());
  }

  private void audit(
      String event,
      Project project,
      AppUserDetails principal,
      UUID confirmationId,
      ActionScope scope,
      String targetHash,
      String outcome) {
    auditService.recordConfirmation(
        event,
        project == null ? null : project.getId(),
        project == null ? null : project.getOrgId(),
        principal,
        confirmationId == null ? null : confirmationId.toString(),
        scope == null ? null : scope.action(),
        scope == null ? null : scope.provider(),
        scope == null ? null : scope.model(),
        targetHash,
        outcome);
  }

  private static ResponseStatusException consumeFailure(
      ProjectChatConfirmationConsumer.Outcome outcome) {
    return switch (outcome) {
      case NOT_FOUND -> new ResponseStatusException(HttpStatus.NOT_FOUND, "confirmation not found");
      case EXPIRED -> new ResponseStatusException(HttpStatus.GONE, "confirmation expired");
      case REPLAY ->
          new ResponseStatusException(HttpStatus.CONFLICT, "confirmation already consumed");
      case TENANT_MISMATCH, ACTOR_MISMATCH, SCOPE_MISMATCH, TOKEN_MISMATCH ->
          new ResponseStatusException(HttpStatus.FORBIDDEN, "confirmation scope rejected");
      case CONSUMED -> throw new IllegalArgumentException("consumed is not a failure");
    };
  }

  private static Action parseAction(String raw) {
    try {
      return Action.valueOf(requireText(raw, "action", 48).toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported confirmed action");
    }
  }

  private static LlmProvider parseProvider(String raw) {
    String normalized = requireText(raw, "provider", 32).toUpperCase(Locale.ROOT);
    if ("GOOGLE".equals(normalized)) normalized = "GEMINI";
    try {
      return LlmProvider.valueOf(normalized);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported provider");
    }
  }

  private static String requireText(String value, String field, int max) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    String normalized = value.trim();
    if (normalized.length() > max) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
    }
    return normalized;
  }

  private static void validateBranch(String raw) {
    String branch = requireText(raw, "target.branch", MAX_TARGET_VALUE_CHARS);
    if (!branch.matches("[A-Za-z0-9._/-]+")
        || branch.contains("..")
        || branch.startsWith("/")
        || branch.endsWith("/")
        || branch.endsWith(".")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target.branch is invalid");
    }
  }

  private String writeCanonical(Map<String, String> target) {
    try {
      return objectMapper.writeValueAsString(new TreeMap<>(target));
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("Could not canonicalize confirmation target", impossible);
    }
  }

  private static String joinCapabilities(Action action) {
    return action.capabilities().stream()
        .map(Enum::name)
        .sorted()
        .reduce((a, b) -> a + "," + b)
        .orElseThrow();
  }

  private static String scopeHash(
      String projectId,
      String orgId,
      String actorId,
      Action action,
      String provider,
      String model,
      String targetJson) {
    return sha256(
        String.join(
            "\u001f",
            projectId,
            orgId,
            actorId,
            action.name(),
            provider,
            model,
            targetJson,
            budgetFingerprint(action.budget())));
  }

  private static String budgetFingerprint(LlmExecutionBudget budget) {
    return String.join(
        ":",
        String.valueOf(budget.maxTotalTokens()),
        String.valueOf(budget.maxCostUsd()),
        String.valueOf(budget.maxProviderCalls()));
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static ConfirmationReference reference(ProjectChatConfirmation confirmation) {
    return new ConfirmationReference(
        confirmation.getId(),
        confirmation.getProjectId(),
        confirmation.getAction(),
        List.of(confirmation.getCapability().split(",")),
        confirmation.getProvider(),
        confirmation.getModel(),
        confirmation.getTargetHash(),
        confirmation.getExpiresAt(),
        confirmation.getStatus() == ProjectChatConfirmation.Status.CONSUMED,
        referenceBudget(Action.valueOf(confirmation.getAction())));
  }

  private static ConfirmationBudget referenceBudget(Action action) {
    return new ConfirmationBudget(
        action.budget().maxTotalTokens(),
        action.budget().maxCostUsd(),
        action.budget().maxProviderCalls());
  }

  private record NormalizedScope(
      String provider, String model, String targetJson, String targetHash) {}
}
