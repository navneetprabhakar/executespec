package com.navneet.executespec.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.run.outcome.RunTerminalOutcomeProjectionService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceiptRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunActivityFeedListenerTest {

  @Test
  void failedRunSummaryNeverIncludesRawEventOrPersistedFailureDetails() {
    String runId = "123e4567-e89b-12d3-a456-426614174000";
    UUID projectId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .projectId(projectId.toString())
            .runTitle("Fixture run")
            .status("FAILED")
            .failureReason("java.sql.SQLException password=persisted-secret")
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    ActivityEventEmitter emitter = mock(ActivityEventEmitter.class);
    when(runRepo.findById(runId)).thenReturn(Optional.of(run));

    RunTerminalOutcomeProjectionService outcomeProjection =
        new RunTerminalOutcomeProjectionService(
            mock(WorkspaceGenerationAttemptRepository.class),
            mock(WorkspaceGenerationVerificationReceiptRepository.class));
    new RunActivityFeedListener(runRepo, emitter, outcomeProjection)
        .onRunFailed(new PipelineEvent.RunFailed(runId, "provider response api_key=event-secret"));

    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.RUN_FAILED),
            isNull(),
            eq("RUN"),
            eq(runId),
            summary.capture(),
            any(Map.class));
    assertThat(summary.getValue())
        .contains("ExecuteSpec could not complete the run", "Fixture run")
        .doesNotContain(
            "SQLException", "password", "persisted-secret", "provider response", "api_key");
  }
}
