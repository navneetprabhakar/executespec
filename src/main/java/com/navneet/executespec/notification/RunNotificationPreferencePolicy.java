package com.navneet.executespec.notification;

import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.run.outcome.RunTerminalOutcome;

/** Selects the user preference that matches the projected customer impact of a terminal run. */
final class RunNotificationPreferencePolicy {

  private RunNotificationPreferencePolicy() {}

  static ActivityEventType eventType(
      RunTerminalOutcome outcome, ActivityEventType fallbackEventType) {
    if (outcome == null) return fallbackEventType;
    return switch (outcome.category()) {
      case SUCCESS, WARNING -> ActivityEventType.RUN_COMPLETED;
      case ACTION_REQUIRED, POLICY_BLOCKED, USER_STOPPED, PLATFORM_FAILURE ->
          ActivityEventType.RUN_FAILED;
    };
  }
}
