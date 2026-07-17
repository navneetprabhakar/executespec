package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;

class RunStepUsageRecorderTest {

  private final RunStepUsageRepository repository =
      org.mockito.Mockito.mock(RunStepUsageRepository.class);
  private final LlmUsageAttributionResolver attributionResolver =
      org.mockito.Mockito.mock(LlmUsageAttributionResolver.class);
  private final LlmUsageAttemptIntentRepository intentRepository =
      org.mockito.Mockito.mock(LlmUsageAttemptIntentRepository.class);
  private final Metrics metrics = org.mockito.Mockito.mock(Metrics.class);
  private final ModelRegistry modelRegistry = org.mockito.Mockito.mock(ModelRegistry.class);
  private final LlmUsageRecoverySpool recoverySpool =
      org.mockito.Mockito.mock(LlmUsageRecoverySpool.class);
  private final RunStepUsageRecorder recorder =
      new RunStepUsageRecorder(
          repository, intentRepository, attributionResolver, metrics, modelRegistry, recoverySpool);

  @Test
  @DisplayName("records role/step/retry attribution with per-call usage")
  void recordsAttribution() {
    RunCostTracker.CallUsage usage =
        new RunCostTracker.CallUsage("deepseek", "deepseek-v4", 11, 7, 3, 5, 0.0123, 4, 2);
    when(repository.save(any(RunStepUsage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    recorder.record(
        "run-1",
        "task-1",
        "WorkerAgent",
        "worker.iteration-3",
        "WorkerAgent:worker.iteration-3#attempt-1",
        "",
        null,
        usage);

    ArgumentCaptor<RunStepUsage> captor = ArgumentCaptor.forClass(RunStepUsage.class);
    verify(repository).save(captor.capture());
    RunStepUsage row = captor.getValue();
    assertThat(row.getRunId()).isEqualTo("run-1");
    assertThat(row.getTaskId()).isEqualTo("task-1");
    assertThat(row.getAgentRole()).isEqualTo("WorkerAgent");
    assertThat(row.getStepId()).isEqualTo("worker.iteration-3");
    assertThat(row.getRetryOf()).isEqualTo("WorkerAgent:worker.iteration-3#attempt-1");
    assertThat(row.getProvider()).isEqualTo("deepseek");
    assertThat(row.getModel()).isEqualTo("deepseek-v4");
    assertThat(row.getInputTokens()).isEqualTo(11);
    assertThat(row.getOutputTokens()).isEqualTo(7);
    assertThat(row.getCacheWriteTokens()).isEqualTo(3);
    assertThat(row.getCacheReadTokens()).isEqualTo(5);
    assertThat(row.getReasoningTokens()).isEqualTo(2);
    assertThat(row.getCostUsd()).isEqualTo(0.0123);
    assertThat(row.getCallIndex()).isEqualTo(4);
    assertThat(row.getAttemptStatus()).isEqualTo("SUCCESS");
    assertThat(row.getKeySource()).isEqualTo("UNKNOWN");
    assertThat(row.getCreatedAt()).isNotNull();
    assertThat(row.getId()).isNotBlank();
    verify(metrics).recordLlmUsagePersistence("SUCCESS", "ok");
  }

  @Test
  @DisplayName("records failed physical attempts with tenant attribution and redacted errors")
  void recordsFailedAttempt() {
    LlmRequest request = request();
    when(attributionResolver.resolve(request))
        .thenReturn(
            new LlmUsageAttributionResolver.Attribution(
                "llmctx-1",
                "org-1",
                "user-1",
                "project-1",
                null,
                "WORKER",
                "PIPELINE_RUN",
                "run-1",
                "corr-1"));
    when(modelRegistry.chainTierFor("deepseek-v4")).thenReturn("CAPABLE");
    when(repository.save(any(RunStepUsage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    recorder.recordFailure(
        request,
        "deepseek",
        "deepseek-v4",
        "ORG",
        2,
        345,
        new IllegalStateException("Authorization: Bearer sk-secret-value-123456789"));

    ArgumentCaptor<RunStepUsage> captor = ArgumentCaptor.forClass(RunStepUsage.class);
    verify(repository).save(captor.capture());
    RunStepUsage row = captor.getValue();
    assertThat(row.getAttemptStatus()).isEqualTo("FAILED");
    assertThat(row.getAttemptNumber()).isEqualTo(2);
    assertThat(row.getLatencyMs()).isEqualTo(345);
    assertThat(row.getLlmExecutionContextId()).isEqualTo("llmctx-1");
    assertThat(row.getOrgId()).isEqualTo("org-1");
    assertThat(row.getUserId()).isEqualTo("user-1");
    assertThat(row.getProjectId()).isEqualTo("project-1");
    assertThat(row.getCorrelationId()).isEqualTo("corr-1");
    assertThat(row.getModelTier()).isEqualTo("CAPABLE");
    assertThat(row.getKeySource()).isEqualTo("ORG");
    assertThat(row.getRetryOf()).isEqualTo("WorkerAgent:worker.iteration#attempt-1");
    assertThat(row.getErrorCode()).isEqualTo("ILLEGALSTATEEXCEPTION");
    assertThat(row.getErrorRedacted())
        .isEqualTo("IllegalStateException:PROVIDER_ERROR_DETAIL_OMITTED");
    assertThat(row.getErrorRedacted()).doesNotContain("sk-secret-value");
    assertThat(row.getInputTokens()).isZero();
    assertThat(row.getCostUsd()).isZero();
    verify(metrics).recordLlmUsagePersistence("FAILED", "ok");
  }

  @Test
  @DisplayName("persistence failure is an alerting signal and never escapes")
  void persistenceFailureSignalsMetric() {
    LlmRequest request = request();
    when(attributionResolver.resolve(request))
        .thenReturn(LlmUsageAttributionResolver.Attribution.unattributed("run-1"));
    when(repository.save(any(RunStepUsage.class))).thenThrow(new IllegalStateException("db down"));

    recorder.recordFailure(
        request, "openai", "gpt", "PLATFORM", 1, 1, new RuntimeException("timeout"));

    verify(metrics).recordLlmUsagePersistence("FAILED", "failed");
    verify(recoverySpool).enqueue(any(RunStepUsage.class));
  }

  @Test
  void prepareAttemptWritesImmutableIntentBeforeTransport() {
    LlmRequest request = request();
    when(attributionResolver.resolve(request))
        .thenReturn(
            new LlmUsageAttributionResolver.Attribution(
                "llmctx-1",
                "org-1",
                "user-1",
                "project-1",
                null,
                "WORKER",
                "PIPELINE_RUN",
                "run-1",
                "corr-1"));
    when(modelRegistry.chainTierFor("deepseek-v4")).thenReturn("CAPABLE");

    RunStepUsageRecorder.AttemptHandle handle =
        recorder.prepareAttempt(request, "deepseek", "deepseek-v4", "ORG", 1);

    ArgumentCaptor<LlmUsageAttemptIntent> captor =
        ArgumentCaptor.forClass(LlmUsageAttemptIntent.class);
    verify(intentRepository).save(captor.capture());
    LlmUsageAttemptIntent intent = captor.getValue();
    assertThat(intent.getPhysicalAttemptId()).isEqualTo(handle.physicalAttemptId());
    assertThat(intent.getLogicalCallId()).isEqualTo(request.usageLogicalCallId());
    assertThat(intent.getCorrelationId()).isEqualTo("corr-1");
    assertThat(intent.getOrgId()).isEqualTo("org-1");
    assertThat(intent.getModelTier()).isEqualTo("CAPABLE");
    assertThat(intent.getAttemptKind()).isEqualTo("PRIMARY");
    assertThat(intent.getStartedAt()).isNotNull();
    verify(metrics).recordLlmUsagePersistence("STARTED", "ok");
  }

  @Test
  void nestedProviderAttemptPreservesExplicitKindOrdinalAndParent() {
    LlmRequest request = request();
    when(attributionResolver.resolve(request))
        .thenReturn(LlmUsageAttributionResolver.Attribution.unattributed("run-1"));

    RunStepUsageRecorder.AttemptHandle handle =
        recorder.prepareAttempt(
            request,
            "gemini",
            "gemini-flash",
            "ORG",
            3,
            "RETRY",
            request.usageLogicalCallId() + ":gemini:2");

    ArgumentCaptor<LlmUsageAttemptIntent> captor =
        ArgumentCaptor.forClass(LlmUsageAttemptIntent.class);
    verify(intentRepository).save(captor.capture());
    LlmUsageAttemptIntent intent = captor.getValue();
    assertThat(handle.attemptOrdinal()).isEqualTo(3);
    assertThat(handle.attemptKind()).isEqualTo("RETRY");
    assertThat(handle.parentAttemptId()).isEqualTo(request.usageLogicalCallId() + ":gemini:2");
    assertThat(intent.getAttemptOrdinal()).isEqualTo(3);
    assertThat(intent.getAttemptKind()).isEqualTo("RETRY");
    assertThat(intent.getParentAttemptId()).isEqualTo(request.usageLogicalCallId() + ":gemini:2");
  }

  @Test
  void prepareAttemptFailureIsFailClosed() {
    LlmRequest request = request();
    when(attributionResolver.resolve(request))
        .thenReturn(LlmUsageAttributionResolver.Attribution.unattributed("run-1"));
    when(intentRepository.save(any(LlmUsageAttemptIntent.class)))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> recorder.prepareAttempt(request, "deepseek", "deepseek-v4", "ORG", 1))
        .isInstanceOf(RunStepUsageRecorder.LlmUsagePersistenceException.class)
        .hasMessageContaining("Provider call blocked");
    verify(metrics).recordLlmUsagePersistence("STARTED", "failed");
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("skips empty run ids and null usage")
  void skipsIncompleteInput() {
    recorder.record("", "task-1", "WorkerAgent", "worker.iteration-1", null, "openai", "gpt", null);
    recorder.record(
        null, "task-1", "WorkerAgent", "worker.iteration-1", null, "openai", "gpt", null);

    verify(repository, never()).save(any());
  }

  @Test
  void governedEmbeddingAttemptUsesDurableProjectAttribution() {
    LlmExecutionReference reference =
        new LlmExecutionReference("llmctx-embed", LlmExecutionScope.customer("org-1"));
    when(attributionResolver.resolve(reference))
        .thenReturn(
            new LlmUsageAttributionResolver.Attribution(
                "llmctx-embed",
                "org-1",
                "user-1",
                "project-1",
                null,
                "projectdocumentembedding",
                "PROJECT_DOCUMENT",
                "doc-1",
                "corr-embed"));
    when(repository.save(any(RunStepUsage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    RunCostTracker.CallUsage call =
        new RunCostTracker.CallUsage(
            "openai", "text-embedding-3-small", 42, 0, 0, 0, 0.00000084, 1, 0);

    recorder.recordProviderCapabilitySuccess(
        reference,
        "embedding:doc-1",
        "ProjectDocumentEmbedding",
        "embedding.batch",
        "openai",
        "text-embedding-3-small",
        "ORG",
        1,
        91,
        call);

    ArgumentCaptor<RunStepUsage> captor = ArgumentCaptor.forClass(RunStepUsage.class);
    verify(repository).save(captor.capture());
    RunStepUsage row = captor.getValue();
    assertThat(row.getLlmExecutionContextId()).isEqualTo("llmctx-embed");
    assertThat(row.getOrgId()).isEqualTo("org-1");
    assertThat(row.getUserId()).isEqualTo("user-1");
    assertThat(row.getProjectId()).isEqualTo("project-1");
    assertThat(row.getSourceType()).isEqualTo("PROJECT_DOCUMENT");
    assertThat(row.getSourceId()).isEqualTo("doc-1");
    assertThat(row.getProvider()).isEqualTo("openai");
    assertThat(row.getModel()).isEqualTo("text-embedding-3-small");
    assertThat(row.getKeySource()).isEqualTo("ORG");
    assertThat(row.getInputTokens()).isEqualTo(42);
    assertThat(row.getAttemptStatus()).isEqualTo("SUCCESS");
  }

  private static LlmRequest request() {
    return LlmRequest.builder()
        .provider("deepseek")
        .model("deepseek-v4")
        .messages(java.util.List.of(new UserMessage("test")))
        .context(new ExecutionContext("run-1", "task-1", Path.of(".").toAbsolutePath().normalize()))
        .agentRole("WorkerAgent")
        .stepId("worker.iteration")
        .build();
  }
}
