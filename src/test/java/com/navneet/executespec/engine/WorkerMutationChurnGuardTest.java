package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class WorkerMutationChurnGuardTest {

  @Test
  void passingVerificationResetsSamePathChurn() {
    WorkerMutationChurnGuard guard = new WorkerMutationChurnGuard();
    for (int i = 0; i < WorkerMutationChurnGuard.NUDGE_AT; i++) {
      guard.recordMutation("smoke_test.py");
    }
    assertThat(guard.evaluate(true, "run", "task", 6))
        .isEqualTo(WorkerMutationChurnGuard.Decision.NUDGE);

    guard.recordPassingVerification();

    assertThat(guard.evaluate(true, "run", "task", 7))
        .isEqualTo(WorkerMutationChurnGuard.Decision.NONE);
    for (int i = 0; i < WorkerMutationChurnGuard.NUDGE_AT; i++) {
      guard.recordMutation("src/App.java");
    }
    assertThat(guard.evaluate(true, "run", "task", 13))
        .isEqualTo(WorkerMutationChurnGuard.Decision.NUDGE);
  }

  @Test
  void extractsNormalizedMutationDestination() {
    assertThat(
            WorkerMutationChurnGuard.mutationTarget(
                new ObjectMapper(), "move", "{\"src\":\"old.py\",\"dst\":\"app/new.py\"}"))
        .isEqualTo("app/new.py");
  }

  @Test
  void configuredRunLimitForcesAtExactThreshold() {
    WorkerMutationChurnGuard guard = new WorkerMutationChurnGuard(3);
    guard.recordMutation("src/App.java");
    guard.recordMutation("src/App.java");
    assertThat(guard.evaluate(true, "run", "task", 2))
        .isEqualTo(WorkerMutationChurnGuard.Decision.NUDGE);

    guard.recordMutation("src/App.java");

    assertThat(guard.evaluate(true, "run", "task", 3))
        .isEqualTo(WorkerMutationChurnGuard.Decision.FORCE);
  }

  @Test
  void mutationsAreCountedPerExactPath() {
    WorkerMutationChurnGuard guard = new WorkerMutationChurnGuard(3);
    guard.recordMutation("src/A.java");
    guard.recordMutation("src/B.java");
    guard.recordMutation("src/A.java");

    assertThat(guard.evaluate(true, "run", "task", 3))
        .isEqualTo(WorkerMutationChurnGuard.Decision.NUDGE);
  }
}
