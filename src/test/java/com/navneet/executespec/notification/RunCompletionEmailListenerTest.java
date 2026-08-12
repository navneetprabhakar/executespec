package com.navneet.executespec.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.notification.pref.NotificationPrefService;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.run.outcome.RunTerminalOutcome;
import com.navneet.executespec.run.outcome.RunTerminalOutcomeProjectionService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceiptRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins {@link RunCompletionEmailListener#friendlyFailureReason} — the guard that keeps a raw
 * exception / stack trace out of the customer failure email. Regression cover for 2026-05-29, when
 * a JDK spawn-helper IOException stack trace leaked into the "Status reason" line of the email.
 */
class RunCompletionEmailListenerTest {

  private static final String GENERIC = "An internal error occurred.";

  @Test
  void rawExceptionTextCollapsesToGenericMessage() {
    // The exact leak from 2026-05-29 — must never reach the customer.
    String leaked =
        "unexpected: RuntimeException: Workspace init failed for run 599db766: Cannot run program"
            + " \"git\": Failed to exec spawn helper ... JDK version mismatch ... Restart JVM";
    assertThat(RunCompletionEmailListener.friendlyFailureReason(leaked))
        .startsWith(GENERIC)
        .doesNotContain("git", "spawn helper", "JDK", "RuntimeException", "Workspace");
  }

  @Test
  void internalErrorTokenIsGeneric() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("INTERNAL_ERROR"))
        .startsWith(GENERIC);
  }

  @Test
  void knownFailureTokensMapToFriendlyText() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("FINAL_BUILD_FAILED"))
        .isEqualTo("The generated code did not compile.");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("ALL_TASKS_FAILED"))
        .isEqualTo("The run could not complete its planned tasks.");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("REVIEWER_FAILED"))
        .isEqualTo("The automated review did not produce clean approval evidence.");
  }

  @Test
  void controlledPlannerAndBudgetStopsAreNotReportedAsInternalErrors() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("PLANNER_RECOVERY_POLICY_BLOCKED"))
        .contains("failed deterministic validation", "No code or pull request was created")
        .doesNotContain("internal error");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("WORKER_BUDGET_EXCEEDED"))
        .contains("configured cost limit", "No pull request was published")
        .doesNotContain("internal error");
  }

  @Test
  void controlledValidationAndContractStopsAreNotReportedAsInternalErrors() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("VALIDATION_REPORT_LOW_QUALITY"))
        .isEqualTo("The generated changes did not meet the required validation threshold.");
    assertThat(
            RunCompletionEmailListener.friendlyFailureReason(
                "DETERMINISTIC_HTTP_REQUIREMENT_MISSING:GET_/api/summary"))
        .isEqualTo("The generated change did not satisfy a required HTTP contract.");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("BROWNFIELD_HTTP_CONTRACT_REMOVED"))
        .isEqualTo("The requested change was blocked by an enforced project policy.");
  }

  @Test
  void controlledAdmissionAndPolicyStopsAreNotReportedAsInternalErrors() {
    assertThat(
            RunCompletionEmailListener.friendlyFailureReason(
                "WORKSPACE_PREFLIGHT_FAILED:repository clone failed"))
        .isEqualTo("The repository workspace could not be prepared.")
        .doesNotContain("clone failed", "internal error");
    assertThat(
            RunCompletionEmailListener.friendlyFailureReason("BROWNFIELD_BASELINE_DIGEST_MISMATCH"))
        .isEqualTo("The requested change was blocked by an enforced project policy.");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("LOCAL_HANDS_DISABLED"))
        .isEqualTo("Local execution is currently unavailable.");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("STANDARDS_BLOCKING_VIOLATION"))
        .isEqualTo("The requested change was blocked by an enforced project policy.");
  }

  @Test
  void testsFailedTokenIsParsedIntoCounts() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("TESTS_FAILED:2_OF_7"))
        .isEqualTo("Some generated tests failed (2 of 7).");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("TESTS_FAILED"))
        .isEqualTo("Some generated tests failed.");
  }

  @Test
  void nullBlankAndLegacyUnknownOmitTheLine() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason(null)).isNull();
    assertThat(RunCompletionEmailListener.friendlyFailureReason("   ")).isNull();
    assertThat(RunCompletionEmailListener.friendlyFailureReason("LEGACY_UNKNOWN")).isNull();
  }

  @Test
  void userCancellationIsNotReportedAsAnInternalError() {
    assertThat(
            RunCompletionEmailListener.friendlyFailureReason(
                "Cancelled by user from the run detail page"))
        .isEqualTo("The run was cancelled.")
        .doesNotContain("internal error");
  }

  @Test
  void partialActionRequiredCompletionUsesFailureEmailPreferenceAndProjectedOutcome() {
    String runId = "123e4567-e89b-12d3-a456-426614174000";
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .createdByUserId("user-1")
            .runTitle("Fixture run")
            .status("PARTIAL")
            .statusReason("VALIDATION_REPORT_LOW_QUALITY")
            .actualCostUsd(0.42)
            .build();
    AppUser user =
        AppUser.builder().id("user-1").email("navneet@executespec.ai").firstName("Navneet").build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AppUserRepository userRepo = mock(AppUserRepository.class);
    ValidationReportRepository reportRepo = mock(ValidationReportRepository.class);
    MailService mailService = mock(MailService.class);
    NotificationPrefService prefService = mock(NotificationPrefService.class);
    when(runRepo.findById(runId)).thenReturn(Optional.of(run));
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(reportRepo.findByRunId(runId)).thenReturn(Optional.empty());
    when(prefService.shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.EMAIL))
        .thenReturn(true);
    RunCompletionEmailListener listener =
        new RunCompletionEmailListener(
            runRepo, userRepo, reportRepo, mailService, prefService, outcomeProjection());

    listener.onRunCompleted(new PipelineEvent.RunCompleted(runId));

    ArgumentCaptor<RunTerminalOutcome> outcome = ArgumentCaptor.forClass(RunTerminalOutcome.class);
    verify(mailService)
        .sendRunCompletionEmail(
            eq("navneet@executespec.ai"),
            eq("Navneet"),
            eq(runId),
            eq("PARTIAL"),
            outcome.capture(),
            eq("Fixture run"),
            isNull(),
            eq(0.42),
            isNull(),
            anyString());
    assertThat(outcome.getValue().category())
        .isEqualTo(RunTerminalOutcome.Category.ACTION_REQUIRED);
    verify(prefService)
        .shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.EMAIL);
    verify(prefService, never())
        .shouldNotify(
            "user-1", ActivityEventType.RUN_COMPLETED, NotificationPrefService.Channel.EMAIL);
  }

  @Test
  void cancelledRunUsesCompletionEmailPreferenceEvenWhenDeliveredAsFailureEvent() {
    String runId = "123e4567-e89b-12d3-a456-426614174001";
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .createdByUserId("user-1")
            .runTitle("Stopped fixture")
            .status("CANCELLED")
            .statusReason("RUN_CANCELLED")
            .build();
    AppUser user =
        AppUser.builder().id("user-1").email("navneet@executespec.ai").firstName("Navneet").build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AppUserRepository userRepo = mock(AppUserRepository.class);
    ValidationReportRepository reportRepo = mock(ValidationReportRepository.class);
    MailService mailService = mock(MailService.class);
    NotificationPrefService prefService = mock(NotificationPrefService.class);
    when(runRepo.findById(runId)).thenReturn(Optional.of(run));
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(reportRepo.findByRunId(runId)).thenReturn(Optional.empty());
    when(prefService.shouldNotify(
            "user-1", ActivityEventType.RUN_COMPLETED, NotificationPrefService.Channel.EMAIL))
        .thenReturn(true);
    RunCompletionEmailListener listener =
        new RunCompletionEmailListener(
            runRepo, userRepo, reportRepo, mailService, prefService, outcomeProjection());

    listener.onRunFailed(new PipelineEvent.RunFailed(runId, "cancelled by user"));

    verify(prefService)
        .shouldNotify(
            "user-1", ActivityEventType.RUN_COMPLETED, NotificationPrefService.Channel.EMAIL);
    verify(prefService, never())
        .shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.EMAIL);
    ArgumentCaptor<RunTerminalOutcome> outcome = ArgumentCaptor.forClass(RunTerminalOutcome.class);
    verify(mailService)
        .sendRunCompletionEmail(
            eq("navneet@executespec.ai"),
            eq("Navneet"),
            eq(runId),
            eq("CANCELLED"),
            outcome.capture(),
            eq("Stopped fixture"),
            isNull(),
            isNull(),
            isNull(),
            anyString());
    assertThat(outcome.getValue().category()).isEqualTo(RunTerminalOutcome.Category.USER_STOPPED);
  }

  private static RunTerminalOutcomeProjectionService outcomeProjection() {
    return new RunTerminalOutcomeProjectionService(
        mock(WorkspaceGenerationAttemptRepository.class),
        mock(WorkspaceGenerationVerificationReceiptRepository.class));
  }
}
