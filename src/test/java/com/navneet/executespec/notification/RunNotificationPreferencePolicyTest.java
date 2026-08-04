package com.navneet.executespec.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.run.outcome.RunTerminalOutcome;
import com.navneet.executespec.run.outcome.RunTerminalOutcomeContract;

import org.junit.jupiter.api.Test;

class RunNotificationPreferencePolicyTest {

  @Test
  void userStoppedUsesNeutralCompletionPreferenceRatherThanFailurePreference() {
    RunTerminalOutcome outcome =
        RunTerminalOutcomeContract.project(
            "run-cancelled", "CANCELLED", null, "Cancelled by user", null, null, null);

    assertThat(outcome.category()).isEqualTo(RunTerminalOutcome.Category.USER_STOPPED);
    assertThat(RunNotificationPreferencePolicy.eventType(outcome, ActivityEventType.RUN_FAILED))
        .isEqualTo(ActivityEventType.RUN_COMPLETED);
  }

  @Test
  void actionRequiredAndPlatformFailureStillUseFailurePreference() {
    RunTerminalOutcome actionRequired =
        RunTerminalOutcomeContract.project(
            "run-budget", "FAILED", "WORKER_BUDGET_EXCEEDED", null, 0.4, 0.5, null);
    RunTerminalOutcome platformFailure =
        RunTerminalOutcomeContract.project(
            "run-platform", "FAILED", null, "raw internal detail", null, null, null);

    assertThat(
            RunNotificationPreferencePolicy.eventType(
                actionRequired, ActivityEventType.RUN_COMPLETED))
        .isEqualTo(ActivityEventType.RUN_FAILED);
    assertThat(
            RunNotificationPreferencePolicy.eventType(
                platformFailure, ActivityEventType.RUN_COMPLETED))
        .isEqualTo(ActivityEventType.RUN_FAILED);
  }
}
