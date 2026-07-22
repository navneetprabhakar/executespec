package com.navneet.executespec.testgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.testgen.model.TestCategory;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;

/** RR-678 — test generation must fail closed on a missing run and never optimistically COMPILE. */
class TestWorkerAgentRouteGuardTest {

  private final LlmRouter llmRouter = mock(LlmRouter.class);
  private final ModelResolver modelResolver = mock(ModelResolver.class);
  private final StateStore stateStore = mock(StateStore.class);
  private final TestTaskRepository testTaskRepository = mock(TestTaskRepository.class);
  private final TestPlanRepository testPlanRepository = mock(TestPlanRepository.class);
  private final TestProjectScaffold scaffold = mock(TestProjectScaffold.class);

  private final TestWorkerAgent agent =
      new TestWorkerAgent(
          llmRouter,
          modelResolver,
          stateStore,
          testTaskRepository,
          testPlanRepository,
          scaffold,
          new ObjectMapper());

  // RR-678 — a run that cannot be loaded must not silently route to a default provider (which could
  // spend through the wrong provider/key policy). And the task must not be left COMPILED.
  @Test
  void missingRunFailsClosedAndDoesNotMarkTaskCompiled() {
    agent.setPromptResourceForTest(
        new org.springframework.core.io.ByteArrayResource("{task_name}".getBytes()));
    when(stateStore.findRun(any())).thenReturn(Optional.empty());
    TestTask task =
        TestTask.builder()
            .id(1L)
            .testPlanId(9L)
            .taskName("OrderApiTest")
            .taskDescription("d")
            .testCategory(TestCategory.CRUD)
            .status(TestTaskStatus.PENDING)
            .build();

    assertThatThrownBy(
            () -> agent.generateTestClass("ghost-run", task, "conv", "spec", false, false))
        .isInstanceOf(IllegalStateException.class);

    // The compilation gate is the single transition into COMPILED; a failed generation never is.
    assertThat(task.getStatus()).isNotEqualTo(TestTaskStatus.COMPILED);
  }
}
