package com.navneet.executespec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
import com.navneet.executespec.state.entity.PipelineRun;

import lombok.extern.slf4j.Slf4j;

/** Projects physical-route and durable validation facts onto the run-detail transport. */
@Slf4j
final class RunDetailEvidenceProjection {

  private RunDetailEvidenceProjection() {}

  static RunDetail.RunDetailBuilder apply(
      RunDetail.RunDetailBuilder builder,
      PipelineRun run,
      ValidationReport report,
      EffectiveRouteProjection route,
      ObjectMapper objectMapper) {
    WorkflowSummary workflow = workflow(report, objectMapper);
    return builder
        .effectiveProvider(route.provider())
        .effectiveModel(route.model())
        .effectiveRouteAttribution(route.attribution().name())
        .routeDiverged(
            route.divergesFromRequestedRoute(run.getRequestedProvider(), run.getRequestedModel()))
        .reviewerVerdict(report != null ? report.getReviewerVerdict() : null)
        .reviewerProvider(run.getReviewerProvider())
        .reviewerEffectiveModel(run.getReviewerActualModel())
        .finalBuildPassed(report != null ? report.getFinalBuildPassed() : null)
        .testsPassed(report != null ? report.getTestsPassed() : null)
        .testsTotal(report != null ? report.getTestsTotal() : null)
        .testExecutionVerified(report != null ? report.getTestExecutionVerified() : null)
        .validationEvidenceStatus(report != null ? report.getStatus() : null)
        .evidenceWorkspaceGeneration(report != null ? report.getWorkspaceGeneration() : null)
        .workflowConfigStatus(workflow.status())
        .workflowConfigReason(workflow.reason())
        .brownfieldHttpBaselineDigest(run.getBrownfieldHttpBaselineDigest())
        .brownfieldHttpCurrentDigest(run.getBrownfieldHttpCurrentDigest())
        .brownfieldHttpCompatibilityStatus(run.getBrownfieldHttpCompatibilityStatus());
  }

  private static WorkflowSummary workflow(ValidationReport report, ObjectMapper objectMapper) {
    if (report == null
        || report.getWorkflowConfigEvidenceJson() == null
        || report.getWorkflowConfigEvidenceJson().isBlank()) {
      return new WorkflowSummary(null, null);
    }
    try {
      WorkflowConfigEvidence evidence =
          objectMapper.readValue(
              report.getWorkflowConfigEvidenceJson(), WorkflowConfigEvidence.class);
      return new WorkflowSummary(evidence.status().name(), evidence.reason());
    } catch (Exception malformed) {
      log.warn("Run {} has malformed workflow/config evidence", report.getRunId());
      return new WorkflowSummary("UNAVAILABLE", "Persisted workflow evidence is malformed");
    }
  }

  private record WorkflowSummary(String status, String reason) {}
}
