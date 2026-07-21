package com.navneet.executespec.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.deployment.preview.DevDockerfileGenerator;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.event.TestsReadyEvent;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestPlanStatus;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * RR-1584 — a test project that does not compile must not be announced as "N tests compiled" nor
 * released for execution, whether or not the compiler output names a test class.
 */
class TestGenOrchestratorCompileGateTest {

  private static final String RUN_ID = "run-compile-gate";

  @Test
  void unattributableCompileFailureEndsThePlanFailedWithNoTestsReadyEvent() {
    TestCompilationGate gate = mock(TestCompilationGate.class);
    // Whole-project failure: no [ERROR] …Test.java token, so nothing can be attributed.
    when(gate.compileTests(RUN_ID))
        .thenReturn(
            new TestCompilationGate.CompilationResult(
                false, "[ERROR] Failed to resolve dependencies", List.of()));

    TestPlan plan = TestPlan.builder().id(7L).runId(RUN_ID).build();
    TestPlanRepository plans = mock(TestPlanRepository.class);
    when(plans.save(any(TestPlan.class))).thenAnswer(inv -> inv.getArgument(0));

    TestTask task =
        TestTask.builder()
            .id(1L)
            .testPlanId(7L)
            .taskName("AlphaTest")
            .targetEntity("Alpha")
            .status(TestTaskStatus.GENERATING)
            .build();
    TestTaskRepository tasks = mock(TestTaskRepository.class);
    when(tasks.findByTestPlanId(7L)).thenReturn(List.of(task));
    when(tasks.findByTestPlanIdAndStatus(7L, TestTaskStatus.FAILED)).thenReturn(List.of());

    TestPlannerAgent planner = mock(TestPlannerAgent.class);
    when(planner.planTests(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(plan);

    TestGenerationAdmissionService admission = mock(TestGenerationAdmissionService.class);
    when(admission.assess(RUN_ID, false, false))
        .thenReturn(
            new TestGenerationAdmissionService.Admission(
                true,
                null,
                java.nio.file.Path.of("workspace", RUN_ID),
                List.of("/api/alpha"),
                "src-fp",
                "ws-fp"));
    when(admission.targetExists(any(), any())).thenReturn(true);

    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    TestGenOrchestrator orchestrator =
        new TestGenOrchestrator(
            planner,
            mock(TestWorkerAgent.class),
            gate,
            mock(TestProjectScaffold.class),
            mock(ApiConventionsGenerator.class),
            plans,
            tasks,
            events,
            mock(SseBroadcaster.class),
            mock(DevDockerfileGenerator.class),
            mock(ApiProber.class),
            mock(ApiSurfaceExtractor.class),
            mock(com.navneet.executespec.harness.WorkspaceInitService.class),
            admission,
            mock(ObjectProvider.class));

    orchestrator.runTestGenPipeline(RUN_ID, "build alpha", "{}");

    assertEquals(TestPlanStatus.FAILED, plan.getStatus());
    verify(events, never()).publishEvent(any(TestsReadyEvent.class));
    verify(gate, never()).markCompiled(any());
  }
}
