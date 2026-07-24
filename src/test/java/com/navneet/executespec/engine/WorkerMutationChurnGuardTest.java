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
  }

  @Test
  void extractsNormalizedMutationDestination() {
    assertThat(
            WorkerMutationChurnGuard.mutationTarget(
                new ObjectMapper(), "move", "{\"src\":\"old.py\",\"dst\":\"app/new.py\"}"))
        .isEqualTo("app/new.py");
  }
}
