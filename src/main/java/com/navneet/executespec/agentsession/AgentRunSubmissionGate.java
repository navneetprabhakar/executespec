package com.navneet.executespec.agentsession;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.run.RunRequestFingerprint;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * The only agent-session component allowed to submit a new cloud run from an orchestrator turn.
 *
 * <p>Normal turns remain decision-only. This gate accepts only a stored {@code CODE_RUN_REQUIRED}
 * decision, explicit user confirmation, and a policy that already allows the run API. The actual
 * BUILD admission is delegated to {@link CustomerRunSubmissionService}; agent confirmations must
 * not grow a second authorization, repository, idempotency, quota, or ownership path.
 */
@Service
@RequiredArgsConstructor
public class AgentRunSubmissionGate {

  private static final String IDEMPOTENCY_PREFIX = "agent-orchestrator:";

  private final CustomerRunSubmissionService customerRunSubmissionService;
  private final FeatureFlagService featureFlagService;

  public Submission submitConfirmed(
      AgentSession session,
      AgentTurn turn,
      OrchestratorTurnResponse decision,
      OrchestratorRunConfirmationRequest request,
      AppUserDetails user) {
    return submitConfirmed(session, turn, decision, request, user, false);
  }

  public Submission submitConfirmed(
      AgentSession session,
      AgentTurn turn,
      OrchestratorTurnResponse decision,
      OrchestratorRunConfirmationRequest request,
      AppUserDetails user,
      boolean patAuthenticated) {
    if (!"CODE_RUN_REQUIRED".equals(decision.route())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored orchestrator decision is not a run route");
    }
    if (!decision.policy().runApiAllowed()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored orchestrator policy does not allow run submission");
    }
    if (decision.policy().confirmationRequiredBeforeSubmit()
        && (request == null || !Boolean.TRUE.equals(request.confirmed()))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "explicit run confirmation required");
    }
    TypedAction action = decision.proposedAction();
    if (action == null || !"create_cloud_run".equals(action.type())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no cloud-run action is proposed");
    }

    String spec = request == null ? null : AgentText.blankToNull(request.spec());
    if (spec == null) {
      spec = AgentActionArguments.stringArg(action.arguments(), "spec");
    }
    if (spec == null || spec.isBlank()) {
      spec = turn.getUserMessage();
    }
    if (spec == null || spec.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "run spec is required");
    }
    String projectId =
        AgentText.firstNonBlank(
            AgentActionArguments.stringArg(action.arguments(), "projectId"),
            session.getProjectId());
    if (projectId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "project context is required");
    }

    boolean localExecutionRequested =
        request != null && Boolean.TRUE.equals(request.localExecution());
    if (localExecutionRequested && !featureFlagService.isEnabled("worker.local-hands.enabled")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Local-hands execution is not enabled on this backend. Use remote run submit or ask an"
              + " operator to enable worker.local-hands.enabled.");
    }
    if (localExecutionRequested && !patAuthenticated) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Local-hands execution requires a CLI or VS Code Personal Access Token session; web UI"
              + " runs execute remotely.");
    }
    if (localExecutionRequested) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Agent proposal confirmation cannot bind a local connector. Submit through the CLI/VS"
              + " Code local-run command so localInstallationId is verified before any run or"
              + " provider spend.");
    }

    String explicitModel = request == null ? null : AgentText.blankToNull(request.modelName());
    // Session defaults route the conversational agent only. A generated-code run uses org/task
    // matrix routing unless the confirmation request explicitly pins a run route.
    String modelName = explicitModel;
    String providerOverride = request == null ? null : AgentText.blankToNull(request.aiProvider());
    String provider = providerOverride;
    String lowStartTier =
        request == null
            ? null
            : normalizeLowComplexityWorkerStartTier(request.lowComplexityWorkerStartTier());
    String executionMode = normalizeExecutionMode(request == null ? null : request.executionMode());
    List<UUID> selectedRepoIds = request == null ? null : request.selectedRepoIds();
    Double userCostCap = request == null ? null : request.userCostCap();
    String idempotencyKey = IDEMPOTENCY_PREFIX + turn.getId();

    var command =
        CustomerRunSubmissionService.Command.builder()
            .userRequest(spec)
            .source("AGENT_ORCHESTRATOR")
            .repoMode("EXISTING")
            .modelName(modelName)
            .aiProvider(provider)
            .rememberProviderPreference(providerOverride != null || explicitModel != null)
            .inferProviderFromModelFirst(explicitModel != null)
            .projectId(projectId)
            .executionMode(executionMode)
            .selectedRepoIds(selectedRepoIds)
            .userCostCap(userCostCap)
            .lowComplexityWorkerStartTier(lowStartTier)
            .localExecution(false)
            .patAuthenticated(patAuthenticated)
            .idempotencyKey(idempotencyKey)
            .requestFingerprint(
                fingerprint(
                    projectId,
                    spec,
                    modelName,
                    provider,
                    lowStartTier,
                    userCostCap,
                    executionMode,
                    selectedRepoIds))
            .build();

    var admitted = customerRunSubmissionService.submit(command, user);
    return new Submission(
        admitted.run().getRunId(),
        admitted.created(),
        admitted.created() ? "SUBMITTED" : "ALREADY_SUBMITTED");
  }

  private static String fingerprint(
      String projectId,
      String spec,
      String modelName,
      String provider,
      String lowStartTier,
      Double userCostCap,
      String executionMode,
      List<UUID> selectedRepoIds) {
    return RunRequestFingerprint.of(
        Arrays.asList(
            projectId,
            spec,
            modelName,
            provider,
            lowStartTier,
            String.valueOf(userCostCap),
            executionMode,
            canonicalRepoIds(selectedRepoIds)));
  }

  private static String canonicalRepoIds(List<UUID> ids) {
    if (ids == null) return "null";
    return ids.stream()
        .map(id -> id == null ? "" : id.toString())
        .sorted()
        .collect(Collectors.joining(","));
  }

  public record Submission(String runId, boolean calledRunApi, String status) {}

  static String normalizeLowComplexityWorkerStartTier(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return "LIGHTWEIGHT".equals(normalized) || "WORKHORSE".equals(normalized) ? normalized : null;
  }

  static String normalizeExecutionMode(String value) {
    if (value == null || value.isBlank()) {
      return "PRODUCTION";
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PROTOTYPE" -> "PROTOTYPE";
      case "PRODUCTION" -> "PRODUCTION";
      default -> "PRODUCTION";
    };
  }
}
