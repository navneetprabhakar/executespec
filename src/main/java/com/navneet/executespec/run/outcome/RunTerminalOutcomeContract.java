package com.navneet.executespec.run.outcome;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
          "DETERMINISTIC_HTTP_FORBIDDEN_METHOD_PRESENT",
          "DETERMINISTIC_HTTP_METHOD_REJECTION_UNPROVEN",
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

  /**
   * Bounded marker persisted by the auto-push path when a clean run's publication was blocked. Only
   * the stable code token inside the brackets is ever surfaced to customers.
   */
  private static final Pattern PUBLICATION_BLOCK_MARKER =
      Pattern.compile("PUBLICATION_BLOCKED\\[([A-Z][A-Z0-9_]{1,63})\\]");

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
        prUrl,
        autoPublicationExpected(run));
  }

  /**
   * Whether this run was expected to publish a pull request on clean completion: a repo-backed run
   * whose customer did not ask to withhold publication and whose changes live on the server. Local
   * hands runs keep their changes on the customer machine, so no server PR is ever expected.
   */
  public static boolean autoPublicationExpected(PipelineRun run) {
    if (run == null || run.isSkipAutoPush() || run.isLocalExecutionRequested()) return false;
    return hasText(run.getRepoUrl()) || hasText(run.getSelectedRepoIds());
  }

  public static RunTerminalOutcome project(
      String runId,
      String status,
      String statusReason,
      String failureReason,
      Double actualCostUsd,
      Double hardCapUsd,
      String prUrl) {
    return project(
        runId, status, statusReason, failureReason, actualCostUsd, hardCapUsd, prUrl, false);
  }

  public static RunTerminalOutcome project(
      String runId,
      String status,
      String statusReason,
      String failureReason,
      Double actualCostUsd,
      Double hardCapUsd,
      String prUrl,
      boolean autoPublicationExpected) {
    return project(
        runId,
        status,
        statusReason,
        failureReason,
        actualCostUsd,
        hardCapUsd,
        prUrl,
        autoPublicationExpected,
        null);
  }

  /**
   * Full projection including the durable failed-run credit-refund fact. {@code creditsRefunded} is
   * three-valued: {@code TRUE} appends the refund fact to the customer message and surfaces it on
   * {@link Impact#creditsRefunded()}; {@code FALSE} states only the impact field (terminal
   * non-success with no refund evidence); {@code null} leaves refund state unresolved (success or
   * legacy callers that did not consult the refund ledger).
   */
  public static RunTerminalOutcome project(
      String runId,
      String status,
      String statusReason,
      String failureReason,
      Double actualCostUsd,
      Double hardCapUsd,
      String prUrl,
      boolean autoPublicationExpected,
      Boolean creditsRefunded) {
    String normalizedStatus = normalize(status);
    if (!TERMINAL_STATUSES.contains(normalizedStatus)) return null;

    String code = stableCode(normalizedStatus, statusReason, failureReason);
    String reasonDetail =
        statusReason != null && !statusReason.isBlank()
            ? normalize(statusReason)
            : normalize(failureReason);
    Publication publication =
        prUrl == null || prUrl.isBlank() ? Publication.NOT_CREATED : Publication.PR_CREATED;
    RunTerminalOutcomeDefinitions.Definition definition =
        definition(
            normalizedStatus,
            code,
            reasonDetail,
            publication,
            autoPublicationExpected,
            publicationBlockCode(failureReason),
            PlannerRecoveryOutcomeCopy.blockCode(failureReason));
    Impact impact =
        new Impact(
            actualCostUsd,
            hardCapUsd,
            codeChanged(code, publication),
            publication,
            prUrl,
            creditsRefunded);
    String message =
        Boolean.TRUE.equals(creditsRefunded)
            ? definition.message() + " The run credit charged for this run was refunded."
            : definition.message();
    String supportCode =
        definition.category() == Category.PLATFORM_FAILURE ? supportCode(runId) : null;
    return new RunTerminalOutcome(
        1,
        code,
        definition.category(),
        definition.title(),
        message,
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

  private static RunTerminalOutcomeDefinitions.Definition definition(
      String status,
      String code,
      String reasonDetail,
      Publication publication,
      boolean autoPublicationExpected,
      String publicationBlockCode,
      String plannerBlockCode) {
    boolean publicationWithheld = publication == Publication.NOT_CREATED && autoPublicationExpected;
    if ("CLEAN_COMPLETION".equals(code)) {
      if (publicationWithheld) {
        return RunTerminalOutcomeDefinitions.successUnpublished(publicationBlockCode);
      }
      return RunTerminalOutcomeDefinitions.success(
          "Run complete", "The run completed successfully.");
    }
    if (WARNING_CODES.contains(code)) {
      if (publicationWithheld && !"LOCAL_EXECUTION".equals(code)) {
        return RunTerminalOutcomeDefinitions.warningUnpublished(publicationBlockCode);
      }
      return RunTerminalOutcomeDefinitions.warning(
          "Completed with warnings",
          "Changes were generated, but some completion evidence needs attention.",
          "Review the run evidence before publishing or merging the change.");
    }
    if ("RUN_CANCELLED".equals(code)) {
      return RunTerminalOutcomeDefinitions.stopped(
          "Run cancelled", "The run was cancelled.", "Start a new run when ready.");
    }
    if ("RUN_ABORTED".equals(code)) {
      return RunTerminalOutcomeDefinitions.stopped(
          "Run stopped", "The run stopped before completion.", "Start a new run when ready.");
    }
    if ("RUN_REJECTED".equals(code)) {
      return RunTerminalOutcomeDefinitions.stopped(
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
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Execution limit reached", message, action);
    }
    if (POLICY_CODES.contains(code)) {
      if ("PLANNER_RECOVERY_POLICY_BLOCKED".equals(code)) {
        // Nine distinct causes hide behind this one status reason and imply different customer
        // actions; the specific one is persisted on failureReason, not on statusReason.
        return PlannerRecoveryOutcomeCopy.definition(plannerBlockCode, publication);
      }
      return RunTerminalOutcomeDefinitions.policyBlocked(
          "Run blocked by policy",
          "The requested change was blocked by an enforced project policy.",
          "Review the policy evidence before changing the request.");
    }
    if (code.startsWith("TESTS_FAILED")) {
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Generated tests failed",
          testFailureMessage(reasonDetail),
          "Inspect the failing-test evidence and correct the generated change before retrying.");
    }
    if (VALIDATION_CODES.contains(code)) {
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Validation needs attention",
          validationMessage(code),
          "Inspect the validation evidence and address the reported failure before retrying.");
    }
    if (code.startsWith("WORKSPACE_PREFLIGHT_FAILED")) {
      if (reasonDetail != null
          && (reasonDetail.startsWith("WORKSPACE_PREFLIGHT_FAILED:BRANCH_MISMATCH")
              || reasonDetail.startsWith("BRANCH_MISMATCH"))) {
        // Repository access is fine here and a plain retry is deterministically futile, so the
        // generic access wording would be actively misleading.
        return RunTerminalOutcomeDefinitions.actionRequired(
            "Workspace branch mismatch",
            "The run workspace is not on the expected repository branch, so the change could"
                + " not be prepared.",
            "Retry the run; if this repeats, the source run's workspace branch state needs"
                + " attention.");
      }
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Repository workspace unavailable",
          "The repository workspace could not be prepared.",
          "Check repository access and retry the run.");
    }
    if ("BYOK_CONTEXT_UNAVAILABLE".equals(code)
        || "PROJECT_PROVIDER_NOT_CONFIGURED".equals(code)
        || "PROJECT_PROVIDER_INVALID".equals(code)
        || "MODEL_MATRIX_UNAVAILABLE".equals(code)) {
      return RunTerminalOutcomeDefinitions.providerSettingsRequired(
          "AI route unavailable",
          "The configured provider or model route is unavailable.",
          "Open provider settings and correct the route.");
    }
    if ("LOCAL_HANDS_DISABLED".equals(code)) {
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Local execution unavailable",
          "Local execution is currently unavailable.",
          "Use a cloud run or ask an administrator to enable local execution.");
    }
    // These two were one message telling the customer to "revise the requirement". That is right
    // for the validator and WRONG for PLANNER_UNAVAILABLE, which is reached when the planning call
    // itself could not be completed — a provider that returned nothing, a transport timeout, or an
    // output ceiling that truncated the plan. Dev runs 350fd2fc and f86d1ad1 both failed for those
    // reasons with perfectly plannable specs, and were told to go rewrite the spec.
    if ("PLAN_VALIDATION_REJECTED".equals(code)) {
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Plan did not pass its checks",
          "The plan was produced but rejected by the safety and consistency checks.",
          "Review the planning evidence and revise the requirement before retrying.");
    }
    if ("PLANNER_UNAVAILABLE".equals(code)) {
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Planning could not be completed",
          "The planning step did not finish — the model route did not return a usable plan. This"
              + " is not a problem with your request.",
          "Retry the run. If you pinned an exact model, try another model or provider.");
    }
    if ("EXECUTION_ABORTED".equals(code)) {
      return RunTerminalOutcomeDefinitions.actionRequired(
          "Execution stopped",
          "Execution stopped before the run could complete.",
          "Review the run evidence before deciding whether to retry.");
    }
    if ("PARTIAL".equals(status)) {
      return RunTerminalOutcomeDefinitions.warning(
          "Completed with warnings",
          "The run completed partially and needs review.",
          "Review the run evidence before publishing or merging the change.");
    }
    return RunTerminalOutcomeDefinitions.platformFailure();
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
      case "DETERMINISTIC_HTTP_FORBIDDEN_METHOD_PRESENT" ->
          "The generated change exposed an HTTP method that must be rejected.";
      case "DETERMINISTIC_HTTP_METHOD_REJECTION_UNPROVEN" ->
          "The generated change could not prove the required HTTP 405 behavior.";
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
    if (Set.of(
                "PLANNER_RECOVERY_POLICY_BLOCKED",
                "PLANNER_UNAVAILABLE",
                "PLAN_VALIDATION_REJECTED",
                "BYOK_CONTEXT_UNAVAILABLE",
                "LOCAL_HANDS_DISABLED",
                "PROJECT_PROVIDER_NOT_CONFIGURED",
                "PROJECT_PROVIDER_INVALID",
                "MODEL_MATRIX_UNAVAILABLE")
            .contains(code)
        || code.startsWith("WORKSPACE_PREFLIGHT_FAILED")) {
      return false;
    }
    // A pre-existing PR proves publication history, not that this terminal attempt changed code or
    // updated the PR. A clean/warning completion is strong enough for the positive claim on its
    // own: the run's validated product diff proves mutation even when publication was blocked or
    // withheld. Failure/limit outcomes remain honestly unknown unless a future attempt-scoped
    // mutation receipt proves otherwise.
    if (SUCCESS_CODES.contains(code) || WARNING_CODES.contains(code)) {
      return true;
    }
    return null;
  }

  /**
   * Extracts the stable publication-block code the auto-push path persisted as a bounded {@code
   * PUBLICATION_BLOCKED[<code>]} marker. Only the validated code token is returned; the surrounding
   * detail never reaches customer copy through this path.
   */
  private static String publicationBlockCode(String failureReason) {
    if (failureReason == null) return null;
    Matcher marker = PUBLICATION_BLOCK_MARKER.matcher(failureReason);
    return marker.find() ? marker.group(1) : null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
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
}
