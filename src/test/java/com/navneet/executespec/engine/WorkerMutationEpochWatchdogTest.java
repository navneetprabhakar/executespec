package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerMutationEpochWatchdogTest {

  @Test
  void creditsOnlyOneSuccessfulVerificationPerMutationEpoch() {
    WorkerMutationEpochWatchdog watchdog = new WorkerMutationEpochWatchdog();

    watchdog.recordMutation();

    assertThat(watchdog.recordVerification("buildProject", true)).isTrue();
    assertThat(watchdog.recordVerification(" buildproject ", true)).isFalse();
    assertThat(watchdog.recordVerification("runTests", true)).isFalse();
    assertThat(watchdog.distinctVerificationSignatures()).isEqualTo(2);
  }

  @Test
  void newMutationStartsOneFreshVerificationCredit() {
    WorkerMutationEpochWatchdog watchdog = new WorkerMutationEpochWatchdog();
    watchdog.recordMutation();
    assertThat(watchdog.recordVerification("buildProject", true)).isTrue();

    watchdog.recordMutation();

    assertThat(watchdog.recordVerification("buildProject", true)).isTrue();
    assertThat(watchdog.recordVerification("buildProject", true)).isFalse();
  }

  @Test
  void repeatedVerificationCannotDelayNudgeOrForce() {
    WorkerMutationEpochWatchdog watchdog = new WorkerMutationEpochWatchdog();
    watchdog.recordMutation();

    for (int iteration = 1; iteration <= 8; iteration++) {
      observeSuccessfulBuild(watchdog, "build-" + iteration);
      WorkerMutationEpochWatchdog.Decision decision = watchdog.afterIteration(false);
      if (iteration == 4)
        assertThat(decision).isEqualTo(WorkerMutationEpochWatchdog.Decision.NUDGE);
      else if (iteration == 8)
        assertThat(decision).isEqualTo(WorkerMutationEpochWatchdog.Decision.FORCE);
      else assertThat(decision).isEqualTo(WorkerMutationEpochWatchdog.Decision.NONE);
    }
  }

  @Test
  void eightUniqueInvestigativeReadsAfterEditDoNotForce() {
    WorkerMutationEpochWatchdog watchdog = new WorkerMutationEpochWatchdog();
    watchdog.recordMutation();

    for (int iteration = 1; iteration <= 8; iteration++) {
      watchdog.observe("readFile", ok("source"), "read:src/File" + iteration);
      assertThat(watchdog.afterIteration(false))
          .isEqualTo(WorkerMutationEpochWatchdog.Decision.NONE);
    }
  }

  @Test
  void novelReadBetweenVerificationLoopsResetsTheVerificationStreak() {
    WorkerMutationEpochWatchdog watchdog = new WorkerMutationEpochWatchdog();
    watchdog.recordMutation();
    for (int iteration = 0; iteration < 3; iteration++) {
      observeSuccessfulBuild(watchdog, "before-" + iteration);
      assertThat(watchdog.afterIteration(false))
          .isEqualTo(WorkerMutationEpochWatchdog.Decision.NONE);
    }

    watchdog.observe("searchFiles", ok("new evidence"), "search:new-symbol");
    assertThat(watchdog.afterIteration(false)).isEqualTo(WorkerMutationEpochWatchdog.Decision.NONE);

    for (int iteration = 1; iteration <= 7; iteration++) {
      observeSuccessfulBuild(watchdog, "after-" + iteration);
      assertThat(watchdog.afterIteration(false))
          .isNotEqualTo(WorkerMutationEpochWatchdog.Decision.FORCE);
    }
    observeSuccessfulBuild(watchdog, "after-8");
    assertThat(watchdog.afterIteration(false))
        .isEqualTo(WorkerMutationEpochWatchdog.Decision.FORCE);
  }

  private static void observeSuccessfulBuild(
      WorkerMutationEpochWatchdog watchdog, String signature) {
    watchdog.observe("buildProject", ok("build passed\\nexit=0\\n"), signature);
  }

  private static String ok(String content) {
    return "{\"ok\":true,\"content\":\"" + content + "\"}";
  }
}
