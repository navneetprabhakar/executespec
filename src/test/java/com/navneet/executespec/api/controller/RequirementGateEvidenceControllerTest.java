package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.requirement.RequirementGateEvidenceDto;
import com.navneet.executespec.agent.requirement.RequirementGateEvidenceService;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class RequirementGateEvidenceControllerTest {
  private final RequirementGateEvidenceService service = mock(RequirementGateEvidenceService.class);
  private final RunAccessGuard runGuard = mock(RunAccessGuard.class);
  private final ProjectAccessGuard projectGuard = mock(ProjectAccessGuard.class);
  private final ExecutionRunRepository executions = mock(ExecutionRunRepository.class);
  private final RequirementGateEvidenceController controller =
      new RequirementGateEvidenceController(service, runGuard, projectGuard, executions);
  private final AppUserDetails principal = mock(AppUserDetails.class);

  @Test
  void runRouteRequiresReadAccessAndReturnsEvidence() {
    RequirementGateEvidenceDto evidence = evidence();
    when(service.findForRun("run-1")).thenReturn(Optional.of(evidence));

    var response = controller.getForRun("run-1", principal);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(evidence);
    verify(runGuard).requireRunAccess("run-1", principal);
  }

  @Test
  void runRouteReturns204OnlyWhenThePlanGateIsAbsent() {
    when(service.findForRun("run-1")).thenReturn(Optional.empty());

    var response = controller.getForRun("run-1", principal);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(runGuard).requireRunAccess("run-1", principal);
  }

  @Test
  void corruptEvidenceMapsToConflict() {
    when(service.findForRun("run-1"))
        .thenThrow(
            new RequirementGateEvidenceService.RequirementGateEvidenceException("CORRUPT", null));

    assertThatThrownBy(() -> controller.getForRun("run-1", principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void deniedRunReadNeverQueriesRequirementEvidence() {
    doThrow(new AccessDeniedException("denied"))
        .when(runGuard)
        .requireRunAccess("run-1", principal);

    assertThatThrownBy(() -> controller.getForRun("run-1", principal))
        .isInstanceOf(AccessDeniedException.class);
    verify(service, never()).findForRun(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void executionCompatibilityRouteRequiresProjectAndCanonicalRunReadGuards() {
    ExecutionRun execution =
        ExecutionRun.builder()
            .id("execution-1")
            .projectId("project-1")
            .pipelineRunId("run-1")
            .build();
    RequirementGateEvidenceDto evidence = evidence();
    when(executions.findById("execution-1")).thenReturn(Optional.of(execution));
    when(service.findForRun("run-1")).thenReturn(Optional.of(evidence));

    var response = controller.getForExecution("execution-1", principal);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(evidence);
    verify(projectGuard).assertCanRead("project-1", principal);
    verify(runGuard).requireRunAccess("run-1", principal);
  }

  @Test
  void legacyExecutionWithoutCanonicalPipelineRunReturnsNoContentAfterProjectGuard() {
    ExecutionRun execution =
        ExecutionRun.builder().id("execution-1").projectId("project-1").build();
    when(executions.findById("execution-1")).thenReturn(Optional.of(execution));

    var response = controller.getForExecution("execution-1", principal);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(projectGuard).assertCanRead("project-1", principal);
    verify(runGuard, never())
        .requireRunAccess(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    verify(service, never()).findForRun(org.mockito.ArgumentMatchers.anyString());
  }

  private static RequirementGateEvidenceDto evidence() {
    return new RequirementGateEvidenceDto(
        "run-1",
        "gate:plan:run-1",
        "PENDING",
        "ENFORCED",
        "BOUND",
        "NONE",
        true,
        true,
        1,
        "compiler",
        "source",
        "plan",
        "artifact",
        "bound",
        "READY",
        0,
        0,
        0,
        0,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
