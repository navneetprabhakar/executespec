package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.cost.LlmUsageAttemptIntent;
import com.navneet.executespec.cost.LlmUsageAttributionResolver;
import com.navneet.executespec.cost.RunStepUsageRecorder;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class PhysicalProviderAttemptAdmissionServiceTest {

  private static final Instant DEADLINE = Instant.parse("2026-07-28T01:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final Duration SAFETY = Duration.ofSeconds(15);

  private final LlmExecutionDispatchGuard guard = mock(LlmExecutionDispatchGuard.class);
  private final RunStepUsageRecorder recorder = mock(RunStepUsageRecorder.class);
  private final LlmExecutionOperationAdmissionService admission =
      mock(LlmExecutionOperationAdmissionService.class);

  private PhysicalProviderAttemptAdmissionService service;

  @BeforeEach
  void setUp() {
    service = new PhysicalProviderAttemptAdmissionService(guard, recorder, admission);
  }

  @Test
  void chatAttemptReauthorizesDraftsThenAtomicallyAdmitsWithoutLegacyReservation() {
    Fixture fixture = fixture("ctx-chat");
    LlmRequest request = request(fixture, 2048);
    RunStepUsageRecorder.AttemptDraft draft = draft("logical-chat", "openai", "gpt-test", "USER");
    LlmExecutionContext durable = mock(LlmExecutionContext.class);
    when(guard.reauthorize(request))
        .thenReturn(
            new LlmExecutionDispatchGuard.ResolvedDispatch(
                "rotated-key", RunKeyContext.Source.USER, durable));
    when(recorder.draftAttempt(
            request, "openai", "gpt-test", "USER", 2, "RETRY", "logical-chat:openai:1"))
        .thenReturn(draft);
    when(admission.admit(
            fixture.reference(), fixture.authority(), draft.intent(), 2048, TIMEOUT, SAFETY))
        .thenReturn(
            new LlmExecutionOperationAdmissionService.AdmittedAttempt(draft.intent(), DEADLINE));

    var admitted =
        service.admitChatAttempt(request, 2, "RETRY", "logical-chat:openai:1", TIMEOUT, SAFETY);

    assertThat(admitted.handle()).isSameAs(draft.handle());
    assertThat(admitted.intent()).isSameAs(draft.intent());
    assertThat(admitted.apiKey()).isEqualTo("rotated-key");
    assertThat(admitted.keySource()).isEqualTo(RunKeyContext.Source.USER);
    assertThat(admitted.transportDeadline()).isEqualTo(DEADLINE);
    var order = inOrder(guard, recorder, admission);
    order.verify(guard).reauthorize(request);
    order
        .verify(recorder)
        .draftAttempt(request, "openai", "gpt-test", "USER", 2, "RETRY", "logical-chat:openai:1");
    order
        .verify(admission)
        .admit(fixture.reference(), fixture.authority(), draft.intent(), 2048, TIMEOUT, SAFETY);
    verify(guard, never()).authorize(request);
  }

  @Test
  void capabilityAttemptSupportsExactZeroOutputCapAndReturnsFreshCredential() {
    Fixture fixture = fixture("ctx-capability");
    RunStepUsageRecorder.AttemptDraft draft =
        draft("logical-capability", "openai", "text-embedding-3-small", "ORG");
    LlmExecutionContext durable = mock(LlmExecutionContext.class);
    when(guard.reauthorizeProviderCapability(fixture.reference(), "openai"))
        .thenReturn(
            new LlmExecutionDispatchGuard.ResolvedDispatch(
                "fresh-org-key", RunKeyContext.Source.ORG, durable));
    when(recorder.draftCapabilityAttempt(
            fixture.reference(),
            "run-1",
            "embedding-batch-1",
            "EmbeddingService",
            "embedding.batch",
            "openai",
            "text-embedding-3-small",
            "ORG",
            1))
        .thenReturn(draft);
    when(admission.admit(
            fixture.reference(), fixture.authority(), draft.intent(), 0, TIMEOUT, SAFETY))
        .thenReturn(
            new LlmExecutionOperationAdmissionService.AdmittedAttempt(draft.intent(), DEADLINE));
    var capability =
        new PhysicalProviderAttemptAdmissionService.CapabilityAttempt(
            fixture.reference(),
            fixture.authority(),
            "run-1",
            "embedding-batch-1",
            "EmbeddingService",
            "embedding.batch",
            "openai",
            "text-embedding-3-small",
            1,
            0,
            TIMEOUT,
            SAFETY);

    var admitted = service.admitCapabilityAttempt(capability);

    assertThat(admitted.handle()).isSameAs(draft.handle());
    assertThat(admitted.intent()).isSameAs(draft.intent());
    assertThat(admitted.apiKey()).isEqualTo("fresh-org-key");
    assertThat(admitted.keySource()).isEqualTo(RunKeyContext.Source.ORG);
    assertThat(admitted.transportDeadline()).isEqualTo(DEADLINE);
    var order = inOrder(guard, recorder, admission);
    order.verify(guard).reauthorizeProviderCapability(fixture.reference(), "openai");
    order
        .verify(recorder)
        .draftCapabilityAttempt(
            fixture.reference(),
            "run-1",
            "embedding-batch-1",
            "EmbeddingService",
            "embedding.batch",
            "openai",
            "text-embedding-3-small",
            "ORG",
            1);
    order
        .verify(admission)
        .admit(fixture.reference(), fixture.authority(), draft.intent(), 0, TIMEOUT, SAFETY);
    verify(guard, never()).authorizeProviderCapability(fixture.reference(), "openai");
  }

  @Test
  void missingReferenceOrAuthorityFailsBeforeReauthorizationDraftOrAdmission() {
    LlmRequest missingReference =
        LlmRequest.builder()
            .provider("openai")
            .model("gpt-test")
            .messages(List.of(new UserMessage("test")))
            .context(new ExecutionContext("run-1", "task-1", Path.of("/tmp")))
            .build();
    Fixture fixture = fixture("ctx-missing-authority");
    LlmRequest missingAuthority =
        LlmRequest.builder()
            .provider("openai")
            .model("gpt-test")
            .messages(List.of(new UserMessage("test")))
            .context(new ExecutionContext("run-1", "task-1", Path.of("/tmp"), fixture.reference()))
            .build();

    assertThatThrownBy(
            () -> service.admitChatAttempt(missingReference, 1, null, null, TIMEOUT, SAFETY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reference");
    assertThatThrownBy(
            () -> service.admitChatAttempt(missingAuthority, 1, null, null, TIMEOUT, SAFETY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
    verifyNoInteractions(guard, recorder, admission);
  }

  @Test
  void authorityForAnotherReferenceFailsBeforeReauthorizationDraftOrAdmission() {
    Fixture requestFixture = fixture("ctx-request");
    Fixture otherFixture = fixture("ctx-other");
    ExecutionContext context =
        new ExecutionContext(
            "run-1",
            "task-1",
            Path.of("/tmp"),
            requestFixture.reference(),
            otherFixture.authority());
    LlmRequest request =
        LlmRequest.builder()
            .provider("openai")
            .model("gpt-test")
            .messages(List.of(new UserMessage("test")))
            .context(context)
            .build();

    assertThatThrownBy(() -> service.admitChatAttempt(request, 1, null, null, TIMEOUT, SAFETY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different durable execution reference");
    verifyNoInteractions(guard, recorder, admission);
  }

  @Test
  void staleDurableAuthorityFailsAtAtomicAdmissionAfterFreshPolicyAndPairedDraft() {
    Fixture fixture = fixture("ctx-stale");
    LlmRequest request = request(fixture, 1024);
    RunStepUsageRecorder.AttemptDraft draft = draft("logical-stale", "openai", "gpt-test", "USER");
    when(guard.reauthorize(request))
        .thenReturn(
            new LlmExecutionDispatchGuard.ResolvedDispatch(
                "fresh-key", RunKeyContext.Source.USER, mock(LlmExecutionContext.class)));
    when(recorder.draftAttempt(request, "openai", "gpt-test", "USER", 1, null, null))
        .thenReturn(draft);
    when(admission.admit(
            fixture.reference(), fixture.authority(), draft.intent(), 1024, TIMEOUT, SAFETY))
        .thenThrow(
            new LlmExecutionOperationException(
                LlmExecutionOperationException.Reason.LEASE_NOT_OWNED,
                "Execution operation authority is stale"));

    assertThatThrownBy(() -> service.admitChatAttempt(request, 1, null, null, TIMEOUT, SAFETY))
        .isInstanceOf(LlmExecutionOperationException.class)
        .extracting(error -> ((LlmExecutionOperationException) error).reason())
        .isEqualTo(LlmExecutionOperationException.Reason.LEASE_NOT_OWNED);
    verify(guard).reauthorize(request);
    verify(recorder).draftAttempt(request, "openai", "gpt-test", "USER", 1, null, null);
    verify(admission)
        .admit(fixture.reference(), fixture.authority(), draft.intent(), 1024, TIMEOUT, SAFETY);
  }

  @Test
  void capabilityRequiresAnExplicitNonNegativeOutputCapBeforeAnyDependencyCall() {
    Fixture fixture = fixture("ctx-cap");
    var request =
        new PhysicalProviderAttemptAdmissionService.CapabilityAttempt(
            fixture.reference(),
            fixture.authority(),
            "run-1",
            null,
            "EmbeddingService",
            null,
            "openai",
            "text-embedding-3-small",
            1,
            null,
            TIMEOUT,
            SAFETY);

    assertThatThrownBy(() -> service.admitCapabilityAttempt(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit non-negative cap");
    verifyNoInteractions(guard, recorder, admission);
  }

  private static Fixture fixture(String contextId) {
    LlmExecutionReference reference =
        new LlmExecutionReference(contextId, LlmExecutionScope.customer("org-a"));
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-" + contextId, 1, 1, UUID.randomUUID());
    return new Fixture(reference, LlmExecutionOperationAuthority.issue(claim, "owner-a"));
  }

  private static LlmRequest request(Fixture fixture, Integer maxTokens) {
    return LlmRequest.builder()
        .provider("openai")
        .model("gpt-test")
        .messages(List.of(new UserMessage("test")))
        .context(
            new ExecutionContext(
                "run-1", "task-1", Path.of("/tmp"), fixture.reference(), fixture.authority()))
        .maxTokens(maxTokens)
        .agentRole("WorkerAgent")
        .stepId("worker.iteration")
        .build();
  }

  private static RunStepUsageRecorder.AttemptDraft draft(
      String logicalCallId, String provider, String model, String keySource) {
    LlmUsageAttributionResolver.Attribution attribution =
        new LlmUsageAttributionResolver.Attribution(
            "ctx-1",
            "org-a",
            "user-a",
            "project-a",
            null,
            "test",
            "PIPELINE_RUN",
            "run-1",
            "run-1");
    RunStepUsageRecorder.AttemptHandle handle =
        new RunStepUsageRecorder.AttemptHandle(
            logicalCallId + ":" + provider + ":1",
            logicalCallId,
            null,
            "PRIMARY",
            1,
            provider,
            model,
            "WORKHORSE",
            keySource,
            1,
            attribution);
    LlmUsageAttemptIntent intent =
        LlmUsageAttemptIntent.builder()
            .physicalAttemptId(handle.physicalAttemptId())
            .logicalCallId(handle.logicalCallId())
            .attemptKind(handle.attemptKind())
            .attemptOrdinal(handle.attemptOrdinal())
            .build();
    return new RunStepUsageRecorder.AttemptDraft(handle, intent);
  }

  private record Fixture(
      LlmExecutionReference reference, LlmExecutionOperationAuthority authority) {}
}
