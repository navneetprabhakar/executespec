package com.navneet.executespec.api.controller;

import com.navneet.executespec.agent.requirement.RequirementGateEvidenceDto;
import com.navneet.executespec.agent.requirement.RequirementGateEvidenceService;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Authorized read-only RequirementIR evidence for canonical runs and execution compatibility. */
@RestController
@RequiredArgsConstructor
public class RequirementGateEvidenceController {
  private final RequirementGateEvidenceService evidenceService;
  private final RunAccessGuard runAccessGuard;
  private final ProjectAccessGuard projectAccessGuard;
  private final ExecutionRunRepository executionRunRepository;

  @GetMapping("/api/runs/{runId}/requirement-gate")
  public ResponseEntity<RequirementGateEvidenceDto> getForRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails principal) {
    runAccessGuard.requireRunAccess(runId, principal);
    return response(runId);
  }

  @GetMapping("/api/executions/{id}/requirement-gate")
  public ResponseEntity<RequirementGateEvidenceDto> getForExecution(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    ExecutionRun execution =
        executionRunRepository
            .findById(id)
            .filter(row -> row.getDeletedAt() == null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "execution run"));
    projectAccessGuard.assertCanRead(execution.getProjectId(), principal);
    String pipelineRunId = execution.getPipelineRunId();
    if (pipelineRunId == null || pipelineRunId.isBlank()) {
      return ResponseEntity.noContent().build();
    }
    runAccessGuard.requireRunAccess(pipelineRunId, principal);
    return response(pipelineRunId);
  }

  private ResponseEntity<RequirementGateEvidenceDto> response(String runId) {
    try {
      return evidenceService
          .findForRun(runId)
          .map(ResponseEntity::ok)
          .orElseGet(() -> ResponseEntity.noContent().build());
    } catch (RequirementGateEvidenceService.RequirementGateEvidenceException corrupt) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, corrupt.getMessage(), corrupt);
    }
  }
}
