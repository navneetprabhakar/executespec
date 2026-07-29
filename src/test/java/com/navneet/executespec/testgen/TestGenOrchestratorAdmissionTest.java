package com.navneet.executespec.testgen;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.deployment.preview.DevDockerfileGenerator;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

class TestGenOrchestratorAdmissionTest {

  @Test
  void emptySourceSurfaceMakesZeroPlannerCallsAndPersistsZeroTasks() {
    TestPlannerAgent planner = mock(TestPlannerAgent.class);
    TestTaskRepository tasks = mock(TestTaskRepository.class);
    TestGenerationAdmissionService admission = mock(TestGenerationAdmissionService.class);
    var operationLifecycle =
        mock(com.navneet.executespec.integration.RunLlmOperationLifecycleService.class);
    when(admission.assess("run-empty", false, false))
        .thenReturn(
            new TestGenerationAdmissionService.Admission(
                false,
                "HTTP runtime has no extracted endpoint surface",
                null,
                java.util.List.of(),
                null,
                null));

    TestGenOrchestrator orchestrator =
        new TestGenOrchestrator(
            planner,
            mock(TestWorkerAgent.class),
            mock(TestCompilationGate.class),
            mock(TestProjectScaffold.class),
            mock(ApiConventionsGenerator.class),
            mock(TestPlanRepository.class),
            tasks,
            mock(ApplicationEventPublisher.class),
            mock(SseBroadcaster.class),
            mock(DevDockerfileGenerator.class),
            mock(ApiProber.class),
            mock(ApiSurfaceExtractor.class),
            mock(com.navneet.executespec.harness.WorkspaceInitService.class),
            admission,
            operationLifecycle,
            mock(ObjectProvider.class));

    orchestrator.runTestGenPipeline("run-empty", "build calculator", "{}");

    verify(planner, never())
        .planTests(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
    verify(operationLifecycle, never()).claimAsyncTestGeneration(anyString(), anyString());
    verify(tasks, never()).save(any());
  }
}
