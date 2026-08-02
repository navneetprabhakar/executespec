package com.navneet.executespec.run.outcome;

import java.util.Locale;
import java.util.Set;

import com.navneet.executespec.run.outcome.RunTerminalOutcome.AutomationDisposition;
import com.navneet.executespec.run.outcome.RunTerminalOutcome.Category;
import com.navneet.executespec.run.outcome.RunTerminalOutcome.Impact;
import com.navneet.executespec.run.outcome.RunTerminalOutcome.NextAction;
import com.navneet.executespec.run.outcome.RunTerminalOutcome.Publication;
import com.navneet.executespec.state.entity.PipelineRun;

/** Single policy for customer-facing terminal semantics across API, Web, email, and CLI. */
public final class RunTerminalOutcomeContract {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of(
          "COMPLETE",
          "COMPLETED",
          "PARTIAL",
          "FAILED",
          "ERROR",
          "CANCELLED",
          "ABORTED",
          "REJECTED");

  private static final Set<String> SUCCESS_CODES = Set.of("CLEAN_COMPLETION");

  private static final Set<String> WARNING_CODES =
      Set.of(
          "CLEAN_WITH_INTERMEDIATE_WARNINGS",
          "FINAL_TASK_WARNING",
          "PATCH_STOPPED_UNVERIFIED",
          "PATCH_STAGNATION",
          "PATCH_STOPPED_WITHOUT_COMPLETE",
          "LOCAL_EXECUTION",
          "PARTIAL_UNCLASSIFIED");

  private static final Set<String> POLICY_CODES =
      Set.of(
          "PLANNER_RECOVERY_POLICY_BLOCKED",
          "BROWNFIELD_HTTP_CONTRACT_REMOVED",
          "BROWNFIELD_BASELINE_DIGEST_MISMATCH",
          "BROWNFIELD_BASELINE_UNAVAILABLE",
          "STANDARDS_BLOCKING_VIOLATION",
          "ARCHITECTURE_INVARIANT_VIOLATION");

  private static final Set<String> VALIDATION_CODES =
      Set.of(
          "FINAL_BUILD_FAILED",
          "ALL_TASKS_FAILED",
          "REVIEWER_FAILED",
          "REVIEWER_UNRESOLVED",
          "NO_REVIEW_RESULT",
          "VALIDATION_REPORT_LOW_QUALITY",
          "TASK_PROOF_INCOMPLETE",
          "VALIDATION_REPORT_UNAVAILABLE",
          "VALIDATION_EVIDENCE_FAILED",
          "RE_REVIEW_FAILED",
          "RE_REVIEW_NO_WORKSPACE",
          "DETERMINISTIC_HTTP_REQUIREMENT_MISSING",
          "STANDARDS_ENFORCEMENT_UNAVAILABLE",
          "INVARIANT_ENFORCEMENT_UNAVAILABLE");

  private static final Set<String> LIMIT_CODES =
      Set.of(
          "WORKER_BUDGET_EXCEEDED",
          "PATCH_BUDGET_EXCEEDED",
          "WORKER_WALL_CLOCK_EXCEEDED",
          "WORKER_ABSOLUTE_DEADLINE_EXCEEDED",
          "PATCH_WALL_CLOCK_EXCEEDED",
          "WORKER_ITERATION_LIMIT_EXCEEDED",
          "PATCH_ITER_LIMIT");

  private static final Set<String> PLATFORM_CODES =
      Set.of("INTERNAL_ERROR", "PATCH_ENGINE_ERROR", "PATCH_UNKNOWN_OUTCOME");

  private RunTerminalOutcomeContract() {}

  public static RunTerminalOutcome project(PipelineRun run) {
    if (run == null) return null;
    String prUrl = run.getScmPrUrl() != null ? run.getScmPrUrl() : run.getGithubPrUrl();
    Double hardCap = run.getHardCapUsd() != null ? run.getHardCapUsd() : run.getUserCostCap();
    return project(
        run.getRunId(),
        run.getStatus(),
        run.getStatusReason(),
        run.getFailureReason(),
        run.getActualCostUsd(),
        hardCap,
        prUrl);
  }

  public static RunTerminalOutcome project(
      String runId,
      String status,
      String statusReason,
      String failureReason,
      Double actualCostUsd,
      Double hardCapUsd,
      String prUrl) {
    String normalizedStatus = normalize(status);
    if (!TERMINAL_STATUSES.contains(normalizedStatus)) return null;

    String code = stableCode(normalizedStatus, statusReason, failureReason);
    String reasonDetail =
        statusReason != null && !statusReason.isBlank()
            ? normalize(statusReason)
            : normalize(failureReason);
    Publication publication =
        prUrl == null || prUrl.isBlank() ? Publication.NOT_CREATED : Publication.PR_CREATED;
    Definition definition = definition(normalizedStatus, code, reasonDetail, publication);
    Impact impact =
        new Impact(actualCostUsd, hardCapUsd, codeChanged(code, publication), publication, prUrl);
    String supportCode =
        definition.category() == Category.PLATFORM_FAILURE ? supportCode(runId) : null;
    return new RunTerminalOutcome(
        1,
        code,
        definition.category(),
        definition.title(),
        definition.message(),
        impact,
        definition.retryable(),
        definition.nextAction() == null
            ? null
            : new NextAction(
                definition.nextActionType(), definition.nextAction(), definition.nextActionHref()),
        supportCode,
        definition.automationDisposition(),
        definition.automationExitCode());
  }

  private static String stableCode(String status, String statusReason, String failureReason) {
    if ("CANCELLED".equals(status)) {
      return "RUN_CANCELLED";
    }
    if ("ABORTED".equals(status)) return "RUN_ABORTED";
    if ("REJECTED".equals(status)) return "RUN_REJECTED";

    String reason = normalize(statusReason);
    if (!reason.isBlank() && !"LEGACY_UNKNOWN".equals(reason)) {
      String candidate = codePrefix(reason);
      return admissibleForStatus(status, candidate) ? candidate : "INTERNAL_ERROR";
    }
    String failure = normalize(failureReason);
    if (isKnownFailureToken(failure)) {
      String candidate = codePrefix(failure);
      return admissibleForStatus(status, candidate) ? candidate : "INTERNAL_ERROR";
    }
    if ("COMPLETE".equals(status) || "COMPLETED".equals(status)) return "CLEAN_COMPLETION";
    if ("PARTIAL".equals(status)) return "PARTIAL_UNCLASSIFIED";
    return "INTERNAL_ERROR";
  }

  private static boolean admissibleForStatus(String status, String code) {
    if (!isKnownCode(code)) return false;
    if ("FAILED".equals(status) || "ERROR".equals(status)) {
      return !SUCCESS_CODES.contains(code) && !WARNING_CODES.contains(code);
    }
    return true;
  }

  private static boolean isKnownCode(String code) {
    return SUCCESS_CODES.contains(code)
        || WARNING_CODES.contains(code)
        || POLICY_CODES.contains(code)
        || VALIDATION_CODES.contains(code)
        || LIMIT_CODES.contains(code)
        || PLATFORM_CODES.contains(code)
        || code.startsWith("TESTS_FAILED")
        || code.startsWith("WORKSPACE_PREFLIGHT_FAILED")
        || Set.of(
                "PLANNER_UNAVAILABLE",
                "PLAN_VALIDATION_REJECTED",
                "BYOK_CONTEXT_UNAVAILABLE",
                "LOCAL_HANDS_DISABLED",
                "EXECUTION_ABORTED",
                "PROJECT_PROVIDER_NOT_CONFIGURED",
                "PROJECT_PROVIDER_INVALID",
                "MODEL_MATRIX_UNAVAILABLE")
            .contains(code);
  }

  private static boolean isKnownFailureToken(String failure) {
    return isKnownCode(codePrefix(failure));
  }

  private static Definition definition(
      String status, String code, String reasonDetail, Publication publication) {
    if ("CLEAN_COMPLETION".equals(code)) {
      return success("Run complete", "The run completed successfully.");
    }
    if (WARNING_CODES.contains(code)) {
      return warning(
          "Completed with warnings",
          "Changes were generated, but some completion evidence needs attention.",
          "Review the run evidence before publishing or merging the change.");
    }
    if ("RUN_CANCELLED".equals(code)) {
      return stopped("Run cancelled", "The run was cancelled.", "Start a new run when ready.");
    }
    if ("RUN_ABORTED".equals(code)) {
      return stopped(
          "Run stopped", "The run stopped before completion.", "Start a new run when ready.");
    }
    if ("RUN_REJECTED".equals(code)) {
      return stopped(
          "Run rejected", "The run was rejected before execution.", "Start a new run when ready.");
    }
    if (LIMIT_CODES.contains(code)) {
      String message =
          code.contains("BUDGET")
              ? publication == Publication.PR_CREATED
                  ? "The run stopped at its configured cost limit. Its existing pull request was"
                      + " not updated by this attempt."
                  : "The run stopped at its configured cost limit. No pull request was published."
              : "The run reached its configured execution limit before it could complete.";
      String action =
          code.contains("BUDGET")
              ? "Review the recorded spend and retry with a higher cost cap if appropriate."
              : "Review progress and retry with an appropriate execution limit.";
      return actionRequired("Execution limit reached", message, action);
    }
    if (POLICY_CODES.contains(code)) {
      String message =
          "PLANNER_RECOVERY_POLICY_BLOCKED".equals(code)
              ? publication == Publication.PR_CREATED
                  ? "The plan failed deterministic validation and bounded recovery could not"
                      + " produce an approvable update to the existing pull request."
                  : "The plan failed deterministic validation and bounded recovery could not"
                      + " produce an approvable plan. No code or pull request was created."
              : "The requested change was blocked by an enforced project policy.";
      return policyBlocked(
          "Run blocked by policy",
          message,
          "Review the policy evidence before changing the request.");
    }
    if (code.startsWith("TESTS_FAILED")) {
      return actionRequired(
          "Generated tests failed",
          testFailureMessage(reasonDetail),
          "Inspect the failing-test evidence and correct the generated change before retrying.");
    }
    if (VALIDATION_CODES.contains(code)) {
      return actionRequired(
          "Validation needs attention",
          validationMessage(code),
          "Inspect the validation evidence and address the reported failure before retrying.");
    }
    if (code.startsWith("WORKSPACE_PREFLIGHT_FAILED")) {
      return actionRequired(
          "Repository workspace unavailable",
          "The repository workspace could not be prepared.",
          "Check repository access and retry the run.");
    }
    if ("BYOK_CONTEXT_UNAVAILABLE".equals(code)
        || "PROJECT_PROVIDER_NOT_CONFIGURED".equals(code)
        || "PROJECT_PROVIDER_INVALID".equals(code)
        || "MODEL_MATRIX_UNAVAILABLE".equals(code)) {
      return actionRequired(
          "AI route unavailable",
          "The configured provider or model route is unavailable.",
          "Open provider settings, correct the route, and retry.");
    }
    if ("LOCAL_HANDS_DISABLED".equals(code)) {
      return actionRequired(
          "Local execution unavailable",
          "Local execution is currently unavailable.",
          "Use a cloud run or ask an administrator to enable local execution.");
    }
    if ("PLANNER_UNAVAILABLE".equals(code) || "PLAN_VALIDATION_REJECTED".equals(code)) {
      return actionRequired(
          "Planning needs attention",
          "The planning step could not produce a safely executable plan.",
          "Review the planning evidence and revise the requirement before retrying.");
    }
    if ("EXECUTION_ABORTED".equals(code)) {
      return actionRequired(
          "Execution stopped",
          "Execution stopped before the run could complete.",
          "Review the run evidence before deciding whether to retry.");
    }
    if ("PARTIAL".equals(status)) {
      return warning(
          "Completed with warnings",
          "The run completed partially and needs review.",
          "Review the run evidence before publishing or merging the change.");
    }
    return platformFailure();
  }

  private static String validationMessage(String code) {
    return switch (code) {
      case "FINAL_BUILD_FAILED" -> "The generated code did not compile.";
      case "ALL_TASKS_FAILED" -> "The run could not complete its planned tasks.";
      case "REVIEWER_FAILED", "REVIEWER_UNRESOLVED", "NO_REVIEW_RESULT" ->
          "The automated review did not produce clean approval evidence.";
      case "VALIDATION_REPORT_LOW_QUALITY", "TASK_PROOF_INCOMPLETE" ->
          "The generated changes did not meet the required validation threshold.";
      case "VALIDATION_REPORT_UNAVAILABLE", "VALIDATION_EVIDENCE_FAILED" ->
          "The required validation evidence could not be completed.";
      case "RE_REVIEW_FAILED" -> "The requested re-review could not be completed.";
      case "RE_REVIEW_NO_WORKSPACE" ->
          "The requested re-review could not access the run workspace.";
      case "DETERMINISTIC_HTTP_REQUIREMENT_MISSING" ->
          "The generated change did not satisfy a required HTTP contract.";
      case "STANDARDS_ENFORCEMENT_UNAVAILABLE" ->
          "The required project-standards checks could not be completed.";
      case "INVARIANT_ENFORCEMENT_UNAVAILABLE" ->
          "The required architecture checks could not be completed.";
      default -> "The generated change needs validation attention.";
    };
  }

  private static String testFailureMessage(String code) {
    int separator = code.indexOf(':');
    if (separator < 0 || separator == code.length() - 1) return "Some generated tests failed.";
    String counts = code.substring(separator + 1).replace("_OF_", " of ").replace('_', ' ').trim();
    return "Some generated tests failed (" + counts + ").";
  }

  private static Boolean codeChanged(String code, Publication publication) {
    if (publication == Publication.PR_CREATED) return true;
    if (Set.of(
            "PLANNER_RECOVERY_POLICY_BLOCKED",
            "PLANNER_UNAVAILABLE",
            "PLAN_VALIDATION_REJECTED",
            "PROJECT_PROVIDER_NOT_CONFIGURED",
            "PROJECT_PROVIDER_INVALID",
            "MODEL_MATRIX_UNAVAILABLE")
        .contains(code)) {
      return false;
    }
    return null;
  }

  private static Definition success(String title, String message) {
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

  private static Definition warning(String title, String message, String nextAction) {
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

  private static Definition actionRequired(String title, String message, String nextAction) {
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

  private static Definition policyBlocked(String title, String message, String nextAction) {
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

  private static Definition stopped(String title, String message, String nextAction) {
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

  private static Definition platformFailure() {
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

  private static String codePrefix(String value) {
    if (value == null) return "";
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    int colon = normalized.indexOf(':');
    return colon < 0 ? normalized : normalized.substring(0, colon);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String supportCode(String runId) {
    String safe = runId == null ? "UNKNOWN" : runId.replaceAll("[^A-Za-z0-9]", "");
    if (safe.isBlank()) safe = "UNKNOWN";
    return "ES-RUN-" + safe.toUpperCase(Locale.ROOT);
  }

  private record Definition(
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
