package com.navneet.executespec.run.outcome;

/**
 * Customer-safe, server-owned interpretation of a terminal run.
 *
 * <p>The underlying {@code statusReason} and {@code failureReason} remain available as forensic
 * evidence. Customer surfaces should render this projection instead of independently translating
 * those fields or exposing a legacy raw exception.
 */
public record RunTerminalOutcome(
    int schemaVersion,
    String code,
    Category category,
    String customerTitle,
    String customerMessage,
    Impact impact,
    boolean retryable,
    NextAction nextAction,
    String supportCode,
    AutomationDisposition automationDisposition,
    int automationExitCode) {

  public enum Category {
    SUCCESS,
    WARNING,
    ACTION_REQUIRED,
    POLICY_BLOCKED,
    USER_STOPPED,
    PLATFORM_FAILURE
  }

  public enum AutomationDisposition {
    SUCCEEDED,
    COMPLETED_WITH_WARNINGS,
    ACTION_REQUIRED,
    STOPPED,
    FAILED
  }

  public enum Publication {
    PR_CREATED,
    NOT_CREATED
  }

  public record Impact(
      Double actualCostUsd,
      Double capUsd,
      Boolean codeChanged,
      Publication publication,
      String prUrl) {}

  public record NextAction(String type, String label, String href) {}
}
