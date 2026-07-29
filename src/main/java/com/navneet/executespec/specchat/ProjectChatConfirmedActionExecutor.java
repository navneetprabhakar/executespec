package com.navneet.executespec.specchat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.ProjectArchitectureJobService;
import com.navneet.executespec.codeintel.ProjectDocGeneratorService;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codereview.CodeReviewService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.ScmCapability;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.RunModelOverrides;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Executes only a previously consumed, canonical Project Chat action. */
@Service
@RequiredArgsConstructor
public class ProjectChatConfirmedActionExecutor {

  private final ProjectArchitectureJobService architectureJobs;
  private final ProjectDocGeneratorService docGeneratorService;
  private final CodeReviewService codeReviewService;
  private final ProjectRepoService projectRepoService;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final ObjectMapper objectMapper;

  public Map<String, Object> execute(
      ProjectChatConfirmationService.Action action,
      ProjectChatConfirmation confirmation,
      AppUserDetails principal) {
    Map<String, String> target = parseTarget(confirmation.getTargetJson());
    LlmProvider provider = LlmProvider.valueOf(confirmation.getProvider().toUpperCase());
    if (action == ProjectChatConfirmationService.Action.REGENERATE_ARCHITECTURE) {
      return regenerate(confirmation, principal, provider, target);
    }
    String actionId = confirmation.getId().toString();
    LlmExecutionReference actionReference;
    try {
      actionReference =
          executionPolicyFactory.forProject(
              confirmation.getProjectId(),
              "project-chat-confirmed-" + action.name().toLowerCase(),
              "PROJECT_CHAT_CONFIRMED_ACTION",
              actionId,
              actionId,
              confirmation.getProvider(),
              confirmation.getModel(),
              confirmation.getActorUserId(),
              action.budget());
    } catch (RuntimeException unavailable) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Exact confirmed provider/model route is unavailable",
          unavailable);
    }
    Map<String, String> previousOverrides = RunModelOverrides.capture();
    RunModelOverrides.bind(
        RunModelOverrides.withRunPin(Map.of(), provider, confirmation.getModel()));
    try {
      return switch (action) {
        case REGENERATE_ARCHITECTURE -> throw new IllegalStateException("handled before context");
        case GENERATE_PROJECT_DOC ->
            generateDoc(confirmation, principal, provider, target, actionReference);
        case RUN_CODE_REVIEW ->
            runCodeReview(confirmation, principal, provider, target, actionReference);
      };
    } finally {
      RunModelOverrides.bind(previousOverrides);
    }
  }

  private Map<String, Object> regenerate(
      ProjectChatConfirmation confirmation,
      AppUserDetails principal,
      LlmProvider provider,
      Map<String, String> target) {
    projectRepoService.requireProjectCapability(
        confirmation.getProjectId(), ScmCapability.ARCHITECTURE, "regenerate project architecture");
    var job =
        architectureJobs.requestExact(
            confirmation.getProjectId(),
            confirmation.getActorUserId(),
            blankToNull(target.get("branch")),
            provider,
            confirmation.getModel());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("action", ActionName.REGENERATE_ARCHITECTURE.name());
    result.put("jobId", job.id().toString());
    result.put("status", "QUEUED");
    addRouteEvidence(result, confirmation);
    return result;
  }

  private Map<String, Object> generateDoc(
      ProjectChatConfirmation confirmation,
      AppUserDetails principal,
      LlmProvider provider,
      Map<String, String> target,
      LlmExecutionReference actionReference) {
    DocType type = DocType.valueOf(target.get("docType"));
    var document =
        docGeneratorService.generate(
            confirmation.getProjectId(),
            type,
            principal.getUser().getId(),
            provider,
            actionReference);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("action", ActionName.GENERATE_PROJECT_DOC.name());
    result.put("documentId", document.getId().toString());
    result.put("docType", type.name());
    result.put("version", document.getVersion());
    result.put("status", "COMPLETED");
    addRouteEvidence(result, confirmation);
    return result;
  }

  private Map<String, Object> runCodeReview(
      ProjectChatConfirmation confirmation,
      AppUserDetails principal,
      LlmProvider provider,
      Map<String, String> target,
      LlmExecutionReference actionReference) {
    UUID repoId = UUID.fromString(target.get("repoId"));
    var repo =
        projectRepoService.resolveGitHubReviewRepoStrict(confirmation.getProjectId(), repoId, null);
    codeReviewService.assertTierAndQuota(principal.getUser());
    var scan =
        codeReviewService.submitConfirmedScan(
            confirmation.getProjectId(),
            repo.getId(),
            repo.getGithubRepoFullName(),
            principal.getUser(),
            provider,
            confirmation.getModel(),
            actionReference);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("action", ActionName.RUN_CODE_REVIEW.name());
    result.put("scanId", scan.getId().toString());
    result.put("status", scan.getStatus());
    addRouteEvidence(result, confirmation);
    return result;
  }

  private static void addRouteEvidence(
      Map<String, Object> result, ProjectChatConfirmation confirmation) {
    result.put("provider", confirmation.getProvider());
    result.put("model", confirmation.getModel());
    result.put("routePinned", true);
  }

  private Map<String, String> parseTarget(String targetJson) {
    try {
      return objectMapper.readValue(targetJson, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Persisted confirmation target is invalid", e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** Keeps result strings independent of the service enum's Java switch implementation. */
  private enum ActionName {
    REGENERATE_ARCHITECTURE,
    GENERATE_PROJECT_DOC,
    RUN_CODE_REVIEW
  }
}
