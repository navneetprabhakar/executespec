package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.cost.LlmUsageAttributionResolver;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
import com.navneet.executespec.executioncontext.LlmExecutionDispatchGuard;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EmbeddingServiceTest {

  private final EmbeddingPolicyResolver policy = mock(EmbeddingPolicyResolver.class);
  private final LlmExecutionPolicyFactory contexts = mock(LlmExecutionPolicyFactory.class);
  private final LlmExecutionDispatchGuard guard = mock(LlmExecutionDispatchGuard.class);
  private final OpenAiEmbeddingTransport transport = mock(OpenAiEmbeddingTransport.class);
  private final RunStepUsageRecorder usage = mock(RunStepUsageRecorder.class);
  private final ManagedAiUsageService managedUsage = mock(ManagedAiUsageService.class);
  private final RunStepUsageRecorder.AttemptHandle attempt =
      new RunStepUsageRecorder.AttemptHandle(
          "attempt-1",
          "logical-1",
          null,
          "CAPABILITY",
          1,
          "openai",
          EmbeddingService.DEFAULT_MODEL,
          "EMBEDDING",
          "ORG",
          1,
          new LlmUsageAttributionResolver.Attribution(
              "ctx-1",
              "org-1",
              "user-1",
              "project-1",
              null,
              "ProjectCodeEmbedding",
              "PROJECT_REPOSITORY",
              "repo-1",
              "repo-1"));
  private final LlmExecutionReference reference =
      new LlmExecutionReference("ctx-1", LlmExecutionScope.customer("org-1"));
  private EmbeddingService service;

  @BeforeEach
  void setUp() {
    service = new EmbeddingService(policy, contexts, guard, transport, usage, managedUsage);
    when(policy.resolve("project-1")).thenReturn(semantic());
    when(contexts.forProjectEmbedding(
            eq("project-1"),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq("openai"),
            nullable(String.class)))
        .thenReturn(reference);
    when(usage.prepareProviderCapabilityAttempt(
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq(1)))
        .thenReturn(attempt);
    when(usage.recordProviderCapabilitySuccess(
            eq(attempt), anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
        .thenReturn("usage-event-1");
  }

  @Test
  void ownKeyDispatchNeverPassesTheConfiguredGlobalKeyToTransportAndIsAttributed() {
    when(guard.authorizeProviderCapability(reference, "openai"))
        .thenReturn(
            new LlmExecutionDispatchGuard.ResolvedDispatch(
                "customer-openai-key", RunKeyContext.Source.ORG, null));
    when(transport.embed(
            eq("customer-openai-key"), eq(EmbeddingService.DEFAULT_MODEL), eq(List.of("hello"))))
        .thenReturn(new OpenAiEmbeddingTransport.Result(List.of(new float[] {0.1f}), 7));

    assertThat(
            service.embedForProject(
                "project-1",
                List.of("hello"),
                "ProjectCodeEmbedding",
                "PROJECT_REPOSITORY",
                "repo-1"))
        .hasSize(1);

    verify(transport, never()).embed(eq("global-key-must-not-win"), anyString(), any());
    verify(usage)
        .prepareProviderCapabilityAttempt(
            eq(reference),
            eq("embedding:repo-1"),
            eq("embedding.batch.1"),
            eq("EmbeddingService"),
            eq("embedding.batch"),
            eq("openai"),
            eq(EmbeddingService.DEFAULT_MODEL),
            eq("ORG"),
            eq(1));
    verify(usage)
        .recordProviderCapabilitySuccess(
            eq(attempt),
            eq("embedding:repo-1"),
            eq("embedding.batch.1"),
            eq("EmbeddingService"),
            eq("embedding.batch"),
            anyLong(),
            any());
    verify(managedUsage)
        .authorizeFixedCapabilityCall(
            eq("embedding:repo-1"),
            eq("openai"),
            eq(EmbeddingService.DEFAULT_MODEL),
            eq("ProjectCodeEmbedding"),
            nullable(RunKeyContext.class),
            eq(RunKeyContext.Source.ORG));
    verify(managedUsage)
        .recordSuccessfulCall(
            eq("usage-event-1"),
            eq("embedding:repo-1"),
            eq("ProjectCodeEmbedding"),
            eq("ProjectCodeEmbedding"),
            eq("embedding.batch"),
            eq("openai"),
            eq(EmbeddingService.DEFAULT_MODEL),
            any(RunCostTracker.CallUsage.class),
            nullable(RunKeyContext.class),
            eq(RunKeyContext.Source.ORG));
  }

  @Test
  void lexicalPolicyFailsBeforeContextCreationOrTransport() {
    when(policy.resolve("project-1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.LEXICAL,
                false,
                true,
                "deepseek",
                null,
                "SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL"));

    assertThatThrownBy(
            () ->
                service.embedForProject(
                    "project-1", List.of("hello"), "Feature", "PROJECT", "source"))
        .isInstanceOf(EmbeddingUnavailableException.class)
        .extracting(error -> ((EmbeddingUnavailableException) error).reason())
        .isEqualTo("SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL");
    verify(contexts, never())
        .forProjectEmbedding(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class));
    verify(transport, never()).embed(anyString(), anyString(), any());
  }

  @Test
  void durableProviderRejectionFailsBeforeTransport() {
    when(guard.authorizeProviderCapability(reference, "openai"))
        .thenThrow(
            new LlmDispatchPolicyException(
                LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED, "not allowed"));

    assertThatThrownBy(
            () ->
                service.embedForProject(
                    "project-1", List.of("hello"), "Feature", "PROJECT", "source"))
        .isInstanceOf(EmbeddingUnavailableException.class)
        .extracting(error -> ((EmbeddingUnavailableException) error).reason())
        .isEqualTo("PROVIDER_NOT_ALLOWED");
    verify(transport, never()).embed(anyString(), anyString(), any());
  }

  @Test
  void failedPhysicalAttemptIsRecordedWithoutResponseOrSecretMaterial() {
    when(guard.authorizeProviderCapability(reference, "openai"))
        .thenReturn(
            new LlmExecutionDispatchGuard.ResolvedDispatch(
                "customer-openai-key", RunKeyContext.Source.USER, null));
    when(transport.embed(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("provider down"));

    assertThatThrownBy(
            () ->
                service.embedForProject(
                    "project-1", List.of("hello"), "Feature", "PROJECT", "source"))
        .isInstanceOf(IllegalStateException.class);
    verify(usage)
        .recordProviderCapabilityFailure(
            eq(attempt),
            eq("embedding:source"),
            eq("embedding.batch.1"),
            eq("EmbeddingService"),
            eq("embedding.batch"),
            anyLong(),
            any(IllegalStateException.class));
  }

  @Test
  void rejectsConfiguredModelOrDimensionDriftBeforeServingVectors() {
    ReflectionTestUtils.setField(service, "model", "text-embedding-4-experimental");
    assertThatThrownBy(service::validateVectorContract)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("migrate persisted vector provenance");

    ReflectionTestUtils.setField(service, "model", EmbeddingService.DEFAULT_MODEL);
    ReflectionTestUtils.setField(service, "dimensions", 3072);
    assertThatThrownBy(service::validateVectorContract)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1536");
  }

  private static EmbeddingPolicyResolver.Availability semantic() {
    return new EmbeddingPolicyResolver.Availability(
        EmbeddingPolicyResolver.Mode.SEMANTIC,
        true,
        true,
        "openai",
        EmbeddingService.DEFAULT_MODEL,
        "AVAILABLE");
  }
}
