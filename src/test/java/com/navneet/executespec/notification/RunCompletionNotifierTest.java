package com.navneet.executespec.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.notification.pref.NotificationPrefService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunCompletionNotifierTest {

  private final InAppNotificationService notificationService = mock(InAppNotificationService.class);
  private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
  private final StateStore stateStore = mock(StateStore.class);
  private final NotificationPrefService prefService = mock(NotificationPrefService.class);
  private final RunCompletionNotifier notifier =
      new RunCompletionNotifier(
          notificationService, notificationRepository, stateStore, prefService);

  @Test
  void disabledInAppPreferencePreventsCompletedAndFailedNotifications() {
    PipelineRun completed = run("run-complete", "COMPLETE", "CLEAN_COMPLETION", null);
    PipelineRun failed = run("run-failed", "FAILED", "INTERNAL_ERROR", "operator detail");
    when(stateStore.findRun("run-complete")).thenReturn(Optional.of(completed));
    when(stateStore.findRun("run-failed")).thenReturn(Optional.of(failed));
    when(prefService.shouldNotify(
            "user-1", ActivityEventType.RUN_COMPLETED, NotificationPrefService.Channel.IN_APP))
        .thenReturn(false);
    when(prefService.shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.IN_APP))
        .thenReturn(false);

    notifier.onRunCompleted(new PipelineEvent.RunCompleted("run-complete"));
    notifier.onRunFailed(new PipelineEvent.RunFailed("run-failed", "event detail"));

    verify(notificationService, never())
        .create(anyString(), anyString(), anyString(), anyString(), anyString());
    verify(notificationRepository, never()).existsByLinkAndType(anyString(), anyString());
  }

  @Test
  void budgetStopIsActionRequiredAndDoesNotLeakEventFailureText() {
    PipelineRun run =
        run(
            "run-budget",
            "FAILED",
            "WORKER_BUDGET_EXCEEDED",
            "provider secret that must not be shown");
    run.setActualCostUsd(0.42);
    run.setHardCapUsd(0.50);
    when(stateStore.findRun("run-budget")).thenReturn(Optional.of(run));
    allowFailedNotification();

    notifier.onRunFailed(
        new PipelineEvent.RunFailed("run-budget", "raw provider response api_key=secret"));

    NotificationCall call = createdNotification();
    assertThat(call.type()).isEqualTo("RUN_ACTION_REQUIRED");
    assertThat(call.title()).startsWith("Execution limit reached:");
    assertThat(call.body())
        .contains("$0.42", "configured cost limit", "Next:", "higher cost cap")
        .doesNotContain("provider secret", "raw provider response", "api_key", "internal error");
  }

  @Test
  void partialActionRequiredCompletionUsesFailureNotificationPreference() {
    PipelineRun run =
        run("run-partial", "PARTIAL", "VALIDATION_REPORT_LOW_QUALITY", "review detail");
    when(stateStore.findRun("run-partial")).thenReturn(Optional.of(run));
    when(prefService.shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.IN_APP))
        .thenReturn(true);
    when(notificationRepository.existsByLinkAndType(anyString(), anyString())).thenReturn(false);

    notifier.onRunCompleted(new PipelineEvent.RunCompleted("run-partial"));

    NotificationCall call = createdNotification();
    assertThat(call.type()).isEqualTo("RUN_ACTION_REQUIRED");
    verify(prefService)
        .shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.IN_APP);
    verify(prefService, never())
        .shouldNotify(
            "user-1", ActivityEventType.RUN_COMPLETED, NotificationPrefService.Channel.IN_APP);
  }

  @Test
  void cancellationIsNeutralInsteadOfFailedOrPlatformCopy() {
    PipelineRun run = run("run-cancel", "CANCELLED", null, "Cancelled by user from WORKING");
    when(stateStore.findRun("run-cancel")).thenReturn(Optional.of(run));
    when(prefService.shouldNotify(
            "user-1", ActivityEventType.RUN_COMPLETED, NotificationPrefService.Channel.IN_APP))
        .thenReturn(true);
    when(notificationRepository.existsByLinkAndType(anyString(), anyString())).thenReturn(false);

    notifier.onRunFailed(new PipelineEvent.RunFailed("run-cancel", "Cancelled by user"));

    NotificationCall call = createdNotification();
    assertThat(call.type()).isEqualTo("RUN_USER_STOPPED");
    assertThat(call.title()).startsWith("Run cancelled:").doesNotContain("failed", "error");
    assertThat(call.body())
        .contains("The run was cancelled.", "Start a new run when ready.")
        .doesNotContain("internal error", "platform failure");
    verify(prefService)
        .shouldNotify(
            "user-1", ActivityEventType.RUN_COMPLETED, NotificationPrefService.Channel.IN_APP);
    verify(prefService, never())
        .shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.IN_APP);
  }

  @Test
  void unknownPlatformFailureIsLeakSafeAndCarriesSupportCode() {
    PipelineRun run =
        run(
            "123e4567-e89b-12d3-a456-426614174000",
            "FAILED",
            null,
            "java.sql.SQLException password=secret");
    when(stateStore.findRun(run.getRunId())).thenReturn(Optional.of(run));
    allowFailedNotification();

    notifier.onRunFailed(
        new PipelineEvent.RunFailed(run.getRunId(), "provider stack api_key=also-secret"));

    NotificationCall call = createdNotification();
    assertThat(call.type()).isEqualTo("RUN_PLATFORM_FAILURE");
    assertThat(call.title()).startsWith("ExecuteSpec could not complete the run:");
    assertThat(call.body())
        .contains(
            "An internal error occurred.", "Support code: ES-RUN-123E4567E89B12D3A456426614174000")
        .doesNotContain("SQLException", "password", "provider stack", "api_key", "also-secret");
  }

  private void allowFailedNotification() {
    when(prefService.shouldNotify(
            "user-1", ActivityEventType.RUN_FAILED, NotificationPrefService.Channel.IN_APP))
        .thenReturn(true);
    when(notificationRepository.existsByLinkAndType(anyString(), anyString())).thenReturn(false);
  }

  private NotificationCall createdNotification() {
    ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(notificationService)
        .create(
            org.mockito.ArgumentMatchers.eq("user-1"),
            type.capture(),
            title.capture(),
            body.capture(),
            anyString());
    return new NotificationCall(type.getValue(), title.getValue(), body.getValue());
  }

  private static PipelineRun run(
      String runId, String status, String statusReason, String failureReason) {
    return PipelineRun.builder()
        .runId(runId)
        .createdByUserId("user-1")
        .runTitle("Fixture run")
        .status(status)
        .statusReason(statusReason)
        .failureReason(failureReason)
        .build();
  }

  private record NotificationCall(String type, String title, String body) {}
}
