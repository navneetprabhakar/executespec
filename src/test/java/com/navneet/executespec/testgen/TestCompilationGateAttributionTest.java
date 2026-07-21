package com.navneet.executespec.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;

/** RR-1584 — a compile that did not succeed can never produce a COMPILED task. */
class TestCompilationGateAttributionTest {

  private static TestTask task(String name, TestTaskStatus status) {
    TestTask t = new TestTask();
    t.setTaskName(name);
    t.setStatus(status);
    return t;
  }

  private TestCompilationGate gateWith(List<TestTask> tasks) {
    TestTaskRepository repo = mock(TestTaskRepository.class);
    when(repo.findByTestPlanId(1L)).thenReturn(tasks);
    when(repo.save(any(TestTask.class))).thenAnswer(inv -> inv.getArgument(0));
    return new TestCompilationGate(repo);
  }

  @Test
  void unattributableFailureFailsEveryInFlightTaskInsteadOfCompilingIt() {
    TestTask alpha = task("AlphaTest", TestTaskStatus.GENERATING);
    TestTask beta = task("BetaTest", TestTaskStatus.GENERATING);
    TestCompilationGate gate = gateWith(List.of(alpha, beta));

    // e.g. dependency resolution failure — the [ERROR] …Test.java regex matches nothing.
    gate.markFailedTasks(1L, List.of(), "[ERROR] Failed to resolve dependencies");

    assertEquals(TestTaskStatus.FAILED, alpha.getStatus());
    assertEquals(TestTaskStatus.FAILED, beta.getStatus());
  }

  @Test
  void namedFailureDoesNotPromoteTheUnnamedTasksToCompiled() {
    TestTask alpha = task("AlphaTest", TestTaskStatus.GENERATING);
    TestTask beta = task("BetaTest", TestTaskStatus.GENERATING);
    TestCompilationGate gate = gateWith(List.of(alpha, beta));

    gate.markFailedTasks(1L, List.of("AlphaTest"), "[ERROR] /AlphaTest.java:3 cannot find symbol");

    assertEquals(TestTaskStatus.FAILED, alpha.getStatus());
    assertEquals(TestTaskStatus.GENERATING, beta.getStatus());
  }

  @Test
  void compileTimeoutOutputDoesNotCompileAnything() {
    TestTask alpha = task("AlphaTest", TestTaskStatus.GENERATING);
    TestCompilationGate gate = gateWith(List.of(alpha));

    gate.markFailedTasks(1L, List.of(), "Compilation timed out after 120s");

    assertEquals(TestTaskStatus.FAILED, alpha.getStatus());
  }

  @Test
  void markCompiledIsTheOnlyTransitionIntoCompiled() {
    TestTask alpha = task("AlphaTest", TestTaskStatus.GENERATING);
    TestTask failed = task("BetaTest", TestTaskStatus.FAILED);
    TestCompilationGate gate = gateWith(List.of(alpha, failed));

    gate.markCompiled(1L);

    assertEquals(TestTaskStatus.COMPILED, alpha.getStatus());
    assertEquals(TestTaskStatus.FAILED, failed.getStatus());
  }
}
