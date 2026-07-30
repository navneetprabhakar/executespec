package com.navneet.executespec.integration.scm;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Final deterministic correctness admission shared by SCM publication and permanent deployment.
 *
 * <p>Model-authored scores are intentionally irrelevant here. Publication requires current build
 * and test evidence, an exact workspace-generation binding, and exact physical route evidence for
 * every user-approved continuation.
 */
@Service
@RequiredArgsConstructor
public class PublicationAdmissionService {

  private final ValidationReportRepository validationReportRepository;
  private final AgentTaskRepository taskRepository;
  private final CorrectionWorkspaceTransaction workspaceGenerations;
  private final AgentProperties agentProperties;
  private final ObjectMapper objectMapper;

  public Admission assertEligible(PipelineRun run) {
    if (run == null || run.getRunId() == null || run.getRunId().isBlank()) {
      throw blocked("PUBLICATION_RUN_MISSING", "Publication requires a durable run identity");
    }
    ValidationReport report =
        validationReportRepository
            .findByRunId(run.getRunId())
            .orElseThrow(
                () ->
                    blocked(
                        "PUBLICATION_VALIDATION_MISSING",
                        "No completed validation report exists for this run"));
    if (!"COMPLETE".equalsIgnoreCase(report.getStatus())) {
      throw blocked(
          "PUBLICATION_VALIDATION_INCOMPLETE",
          "Validation evidence is not complete (status=" + report.getStatus() + ")");
    }
    if (report.getWorkspaceGeneration() == null || report.getWorkspaceGeneration().isBlank()) {
      throw blocked(
          "PUBLICATION_EVIDENCE_UNBOUND",
          "Validation evidence is not bound to a workspace generation");
    }
    Path workspace = Path.of(agentProperties.getWorkspace(), run.getRunId());
    String currentGeneration = workspaceGenerations.captureGeneration(workspace);
    if (!report.getWorkspaceGeneration().equals(currentGeneration)) {
      throw blocked(
          "PUBLICATION_EVIDENCE_STALE",
          "Workspace content changed after validation; regenerate deterministic evidence");
    }
    if (!Boolean.TRUE.equals(report.getFinalBuildPassed())) {
      throw blocked(
          "PUBLICATION_BUILD_UNPROVEN", "Publication requires an explicit passing final build");
    }
    if (!Boolean.TRUE.equals(report.getTestExecutionVerified())
        || report.getTestsTotal() == null
        || report.getTestsTotal() <= 0) {
      throw blocked(
          "PUBLICATION_TESTS_UNPROVEN", "Publication requires a real, execution-verified test run");
    }
    if (report.getTestsPassed() == null || report.getTestsPassed() < report.getTestsTotal()) {
      throw blocked(
          "PUBLICATION_TESTS_FAILED",
          "Publication requires every executed test to pass ("
              + String.valueOf(report.getTestsPassed())
              + "/"
              + report.getTestsTotal()
              + ")");
    }
    verifyApprovedContinuationRoutes(run.getRunId());
    return new Admission(currentGeneration, "PUBLICATION_ELIGIBLE");
  }

  private void verifyApprovedContinuationRoutes(String runId) {
    List<AgentTask> tasks = taskRepository.findByRunId(runId);
    for (AgentTask task : tasks) {
      ApprovedRoute approved = latestApprovedRoute(task);
      if (approved == null) {
        continue;
      }
      boolean requestedMatches =
          sameProvider(approved.provider(), task.getProvider())
              && approved.model().equals(task.getModel());
      boolean physicalMatches =
          "LEDGER".equalsIgnoreCase(task.getEffectiveRouteAttribution())
              && sameProvider(approved.provider(), task.getEffectiveProvider())
              && approved.model().equals(task.getEffectiveModel());
      if (!requestedMatches || !physicalMatches) {
        throw blocked(
            "PUBLICATION_APPROVED_ROUTE_MISMATCH",
            "Approved continuation route "
                + approved.provider()
                + "/"
                + approved.model()
                + " does not match requested and physical task evidence for "
                + task.getTaskId());
      }
    }
  }

  private ApprovedRoute latestApprovedRoute(AgentTask task) {
    String json = task.getContinuationActionsJson();
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode actions = objectMapper.readTree(json);
      if (!actions.isArray()) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Continuation route evidence is not a JSON array for " + task.getTaskId());
      }
      for (int i = actions.size() - 1; i >= 0; i--) {
        JsonNode action = actions.get(i);
        if (!"APPROVED".equalsIgnoreCase(action.path("status").asText())) {
          continue;
        }
        String provider = action.path("provider").asText("");
        String model = action.path("model").asText("");
        if (provider.isBlank() || model.isBlank()) {
          throw blocked(
              "PUBLICATION_ROUTE_EVIDENCE_INVALID",
              "Approved continuation route evidence is incomplete for " + task.getTaskId());
        }
        return new ApprovedRoute(provider, model);
      }
      return null;
    } catch (PublicationBlockedException blocked) {
      throw blocked;
    } catch (Exception malformed) {
      throw blocked(
          "PUBLICATION_ROUTE_EVIDENCE_INVALID",
          "Continuation route evidence is malformed for " + task.getTaskId());
    }
  }

  private static boolean sameProvider(String expected, String actual) {
    return expected != null
        && actual != null
        && expected.trim().toUpperCase(Locale.ROOT).equals(actual.trim().toUpperCase(Locale.ROOT));
  }

  private static PublicationBlockedException blocked(String code, String message) {
    return new PublicationBlockedException(code, message);
  }

  public record Admission(String workspaceGeneration, String code) {}

  public static final class PublicationBlockedException extends IllegalStateException {
    private final String code;

    PublicationBlockedException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private record ApprovedRoute(String provider, String model) {}
}
