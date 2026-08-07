package com.navneet.executespec.run.outcome;

import com.navneet.executespec.run.outcome.RunTerminalOutcome.AutomationDisposition;
import com.navneet.executespec.run.outcome.RunTerminalOutcome.Category;

/**
 * Customer-facing terminal-outcome definition factories, extracted from {@link
 * RunTerminalOutcomeContract} so the contract stays below the architecture line ceiling. Pure
 * copy/category/disposition tuples; the contract keeps all classification logic.
 */
final class RunTerminalOutcomeDefinitions {

  private RunTerminalOutcomeDefinitions() {}

  /**
   * Clean completion whose expected pull request was not created. The run itself succeeded, so the
   * category and automation disposition stay SUCCESS, but the customer copy must not imply a PR
   * exists and the primary action points at the publication evidence carrying the exact gate.
   */
  static Definition successUnpublished(String publicationBlockCode) {
    return new Definition(
        Category.SUCCESS,
        "Run complete — pull request not created",
        "The run completed successfully, but its pull request was not created."
            + blockedBySentence(publicationBlockCode),
        false,
        "OPEN_PUBLICATION_ELIGIBILITY",
        "Open the run's publication evidence for the exact blocking gate and remediation.",
        null,
        AutomationDisposition.SUCCEEDED,
        0);
  }

  static Definition warningUnpublished(String publicationBlockCode) {
    return new Definition(
        Category.WARNING,
        "Completed with warnings — pull request not created",
        "Changes were generated, but some completion evidence needs attention and the pull"
            + " request was not created."
            + blockedBySentence(publicationBlockCode),
        false,
        "OPEN_PUBLICATION_ELIGIBILITY",
        "Open the run's publication evidence for the exact blocking gate and remediation.",
        null,
        AutomationDisposition.COMPLETED_WITH_WARNINGS,
        3);
  }

  private static String blockedBySentence(String publicationBlockCode) {
    return publicationBlockCode == null
        ? ""
        : " Publication was blocked by " + publicationBlockCode + ".";
  }

  static Definition success(String title, String message) {
    return new Definition(
        Category.SUCCESS,
        title,
        message,
        false,
        null,
        null,
        null,
        AutomationDisposition.SUCCEEDED,
        0);
  }

  static Definition warning(String title, String message, String nextAction) {
    return new Definition(
        Category.WARNING,
        title,
        message,
        false,
        "REVIEW_EVIDENCE",
        nextAction,
        null,
        AutomationDisposition.COMPLETED_WITH_WARNINGS,
        3);
  }

  static Definition actionRequired(String title, String message, String nextAction) {
    return new Definition(
        Category.ACTION_REQUIRED,
        title,
        message,
        true,
        "RETRY_RUN",
        nextAction,
        null,
        AutomationDisposition.ACTION_REQUIRED,
        3);
  }

  static Definition providerSettingsRequired(String title, String message, String nextAction) {
    return new Definition(
        Category.ACTION_REQUIRED,
        title,
        message,
        true,
        "OPEN_PROVIDER_SETTINGS",
        nextAction,
        "/provider-keys",
        AutomationDisposition.ACTION_REQUIRED,
        3);
  }

  /**
   * A policy stop the customer can clear themselves by resubmitting with a different setting — an
   * exact model pin, a confirmation requirement, a benchmark requirement. Deliberately NOT {@link
   * #policyBlocked}: that shape reports {@code retryable=false}, which is wrong here, because the
   * work was never attempted and the same request with the setting changed proceeds normally. The
   * {@code START_NEW_RUN} type already routes to compose, where those settings live.
   */
  static Definition resubmitRequired(String title, String message, String nextAction) {
    return new Definition(
        Category.ACTION_REQUIRED,
        title,
        message,
        true,
        "START_NEW_RUN",
        nextAction,
        null,
        AutomationDisposition.ACTION_REQUIRED,
        3);
  }

  static Definition policyBlocked(String title, String message, String nextAction) {
    return new Definition(
        Category.POLICY_BLOCKED,
        title,
        message,
        false,
        "REVIEW_POLICY",
        nextAction,
        null,
        AutomationDisposition.ACTION_REQUIRED,
        3);
  }

  static Definition stopped(String title, String message, String nextAction) {
    return new Definition(
        Category.USER_STOPPED,
        title,
        message,
        false,
        "START_NEW_RUN",
        nextAction,
        null,
        AutomationDisposition.STOPPED,
        3);
  }

  static Definition platformFailure() {
    return new Definition(
        Category.PLATFORM_FAILURE,
        "ExecuteSpec could not complete the run",
        "An internal error occurred. No internal exception details were exposed.",
        true,
        "CONTACT_SUPPORT",
        "Retry once, and contact support with the support code if the problem continues.",
        null,
        AutomationDisposition.FAILED,
        1);
  }

  record Definition(
      Category category,
      String title,
      String message,
      boolean retryable,
      String nextActionType,
      String nextAction,
      String nextActionHref,
      AutomationDisposition automationDisposition,
      int automationExitCode) {}
}
