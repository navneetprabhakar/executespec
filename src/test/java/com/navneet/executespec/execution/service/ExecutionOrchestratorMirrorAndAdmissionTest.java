package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.event.ExecutionEventTypes;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.integration.PipelineExecutorPool;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-345 — a V2 PARTIAL must mirror to a first-class PARTIAL launch status carrying its reason, not
 * a clean COMPLETE. RR-527 — active-run admission is atomic: an existing active run is rejected.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionOrchestratorMirrorAndAdmissionTest {

  @Mock ExecutionRunRepository runRepo;
  @Mock com.navneet.executespec.execution.repository.ExecutionSolutionRepository solutionRepo;
  @Mock ProjectArchitectureService archService;
  @Mock com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  @Mock com.navneet.executespec.project.service.ProjectRepoService projectRepoService;
  @Mock AppUserRepository appUserRepo;
  @Mock SolutionDependencyResolver depResolver;
  @Mock SolutionExecutionService solutionExecService;
  @Mock ExecutionBranchService branchService;
  @Mock SseBroadcaster broadcaster;
  @Mock GitHubRepoService gitHub;
  @Mock RunOrchestrator v2Orchestrator;
  @Mock com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  @Mock com.navneet.executespec.project.pr.PullRequestRecordRepository prRecordRepo;
  @Mock com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  @Mock ExecutionPlannerService plannerService;
  @Mock ExecutionCostAggregator costAggregator;
  @Mock EntityManager em;

  ExecutionOrchestrator orchestrator;
  ExecutionRunCasUpdater runCas;

  @BeforeEach
  void wire() {
    runCas = new ExecutionRunCasUpdater(runRepo);
    final ExecutionOrchestrator[] selfHolder = new ExecutionOrchestrator[1];
    orchestrator =
        new ExecutionOrchestrator(
            runRepo,
            solutionRepo,
            archService,
            projectRepoRepo,
            projectRepoService,
            appUserRepo,
            depResolver,
            solutionExecService,
            branchService,
            broadcaster,
            gitHub,
            v2Orchestrator,
            runLifecycleService,
            prRecordRepo,
            runCas,
            pipelineRunRepo,
            plannerService,
            costAggregator,
            new org.springframework.beans.factory.ObjectProvider<ExecutionOrchestrator>() {
              @Override
              public ExecutionOrchestrator getObject() {
                return selfHolder[0];
              }
            },
            new PipelineExecutorPool());
    selfHolder[0] = orchestrator;
    ReflectionTestUtils.setField(orchestrator, "em", em);
  }

  @Test
  void mirrorV2Terminal_partialStaysPartialAndPropagatesReason() {
    ExecutionRun row = ExecutionRun.builder().id("exec-1").status("EXECUTING").build();
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    PipelineRun v2 =
        PipelineRun.builder()
            .runId("pr-1")
            .status("PARTIAL")
            .statusReason("TESTS_FAILED:2_OF_7")
            .build();

    orchestrator.mirrorV2Terminal("exec-1", v2);

    // RR-345 — the launch is PARTIAL (not COMPLETE) and the failure reason is surfaced.
    assertThat(row.getStatus()).isEqualTo("PARTIAL");
    assertThat(row.getFailureReason()).isEqualTo("TESTS_FAILED:2_OF_7");

    ArgumentCaptor<RunEvent> event = ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emit(anyString(), event.capture());
    // Terminal-with-a-PR reaches the completion event so the frontend stops polling.
    assertThat(event.getValue().type()).isEqualTo(ExecutionEventTypes.EXEC_COMPLETE);
  }

  @Test
  void admitAndCreateExecution_rejectsWhenActiveRunExists() {
    Query lockQuery = org.mockito.Mockito.mock(Query.class);
    when(em.createNativeQuery(anyString())).thenReturn(lockQuery);
    when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
    when(lockQuery.getSingleResult()).thenReturn(1);
    when(runRepo.findActiveByProjectId("p1"))
        .thenReturn(List.of(ExecutionRun.builder().id("active-1").status("EXECUTING").build()));

    Project project = Project.builder().id("p1").build();
    AppUser user = AppUser.builder().id("u1").build();

    assertThatThrownBy(() -> orchestrator.admitAndCreateExecution(project, user, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }
}
