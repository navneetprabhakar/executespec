package com.navneet.executespec.run.outcome;

import com.navneet.executespec.run.outcome.RunTerminalOutcome.AutomationDisposition;
import com.navneet.executespec.run.outcome.RunTerminalOutcome.Category;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationFailureProjection;

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

  static Definition workspaceGenerationFailure(boolean recoveryFailed) {
    return new Definition(
        Category.PLATFORM_FAILURE,
        recoveryFailed
            ? "Workspace recovery could not be proven"
            : "Generated change did not pass the BUILD boundary",
        recoveryFailed
            ? "Generated changes were rejected, but ExecuteSpec could not prove that the workspace"
                + " was restored. Publication was blocked."
            : "The generated change did not cross the required BUILD acceptance boundary. No"
                + " unverified generation was accepted or published.",
        true,
        "CONTACT_SUPPORT",
        "Retry after the BUILD trust-boundary issue is corrected; contact support with the support"
            + " code if it repeats.",
        null,
        AutomationDisposition.FAILED,
        1);
  }

  static Definition workspaceGeneration(WorkspaceGenerationFailureProjection failure) {
    return switch (failure.category()) {
      case ROLLBACK_PROVEN ->
          new Definition(
              Category.ACTION_REQUIRED,
              "Generated code was rejected safely",
              "Code was generated, deterministic verification did not pass, and the accepted"
                  + " workspace was restored. Publication remains blocked.",
              true,
              "REVIEW_EVIDENCE",
              "Review the failed deterministic checks before starting a new generation attempt.",
              null,
              AutomationDisposition.ACTION_REQUIRED,
              3);
      case VERIFICATION_ENVIRONMENT_UNAVAILABLE ->
          new Definition(
              Category.PLATFORM_FAILURE,
              "Verification environment unavailable",
              "Code was generated, but the disposable verification environment could not produce"
                  + " product evidence. This is not a product-code failure.",
              true,
              "RETRY_RUN",
              "Retry after the verification environment is available.",
              null,
              AutomationDisposition.FAILED,
              1);
      case NO_CANDIDATE ->
          new Definition(
              Category.ACTION_REQUIRED,
              "Generation did not produce a candidate",
              "No candidate generation was frozen. The run stopped before generated product bytes"
                  + " could be verified.",
              true,
              "RETRY_RUN",
              "Retry generation from the accepted workspace.",
              null,
              AutomationDisposition.ACTION_REQUIRED,
              3);
      case CHECKPOINT_FAILED ->
          protocolFailure(
              "Candidate checkpoint was not proven",
              "Generated code may exist, but its durable checkpoint was not acknowledged."
                  + " Publication remains blocked.");
      case FRONTIER_CONFLICT ->
          protocolFailure(
              "Accepted workspace frontier changed",
              "The candidate could not be reconciled with the current accepted workspace."
                  + " Publication remains blocked while convergence is unresolved.");
      case AUTHORITY_LOST ->
          protocolFailure(
              "Generation authority was lost",
              "The active generation lease was no longer authoritative. No publication is allowed"
                  + " from this attempt.");
      case ROLLBACK_UNPROVEN ->
          protocolFailure(
              "Workspace recovery is unresolved",
              "A candidate existed, but restoration to the accepted workspace is not proven."
                  + " Publication and retry remain blocked.");
      case VERIFICATION_REJECTED ->
          protocolFailure(
              "Generated code did not pass verification",
              "A candidate existed, but deterministic verification rejected it and rollback is not"
                  + " yet proven. Publication remains blocked.");
      case INTERNAL_PROTOCOL_CONFLICT ->
          protocolFailure(
              "Generation protocol conflict",
              "The durable generation evidence is internally inconsistent. Publication and"
                  + " automatic retry remain blocked.");
    };
  }

  private static Definition protocolFailure(String title, String message) {
    return new Definition(
        Category.PLATFORM_FAILURE,
        title,
        message,
        false,
        "CONTACT_SUPPORT",
        "Review the run evidence and contact support with the support code.",
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
