package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.LlmUsageAttemptIntent;
import com.navneet.executespec.cost.LlmUsageAttemptIntentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LlmExecutionOperationAdmissionServiceTest {

  private static final Instant DB_NOW = Instant.parse("2026-07-28T10:00:00Z");
  private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000321");
  private static final LlmExecutionScope SCOPE = LlmExecutionScope.platformInternal();
  private static final LlmExecutionReference REFERENCE =
      new LlmExecutionReference("llmctx-admit", SCOPE);

  private final LlmExecutionContextRepository contextRepository =
      mock(LlmExecutionContextRepository.class);
  private final LlmExecutionOperationRepository operationRepository =
      mock(LlmExecutionOperationRepository.class);
  private final LlmExecutionBudgetGate budgetGate = mock(LlmExecutionBudgetGate.class);
  private final LlmUsageAttemptIntentRepository intentRepository =
      mock(LlmUsageAttemptIntentRepository.class);
  private final LlmExecutionContext context = mock(LlmExecutionContext.class);
  private final LlmExecutionOperation operation = mock(LlmExecutionOperation.class);
  private LlmExecutionOperationAdmissionService service;
  private LlmExecutionOperationClaim claim;

  @BeforeEach
  void setUp() {
    service =
        new LlmExecutionOperationAdmissionService(
            contextRepository, operationRepository, budgetGate, intentRepository);
    claim = new LlmExecutionOperationClaim(REFERENCE, "llmop-admit", 1, 2, TOKEN);
    when(contextRepository.findForUpdate("llmctx-admit", "platform:internal"))
        .thenReturn(Optional.of(context));
    when(operationRepository.findForUpdate("llmop-admit", "llmctx-admit"))
        .thenReturn(Optional.of(operation));
    when(contextRepository.databaseNow()).thenReturn(DB_NOW);
    when(context.getId()).thenReturn("llmctx-admit");
    when(context.getStatus()).thenReturn(LlmExecutionContext.Status.RUNNING);
    when(context.getExpiresAt()).thenReturn(DB_NOW.plusSeconds(600));
    when(operation.getId()).thenReturn("llmop-admit");
    when(operation.getStatus()).thenReturn(LlmExecutionOperation.Status.RUNNING);
    when(operation.getInvocation()).thenReturn(1);
    when(operation.getAttempt()).thenReturn(2);
    when(operation.getLeaseOwner()).thenReturn("owner-a");
    when(operation.getLeaseToken()).thenReturn(TOKEN);
    when(operation.getLeaseExpiresAt()).thenReturn(DB_NOW.plusSeconds(30));
    when(operation.admitTransport("owner-a", TOKEN, 2, DB_NOW, DB_NOW.plusSeconds(125)))
        .thenReturn(true);
    when(operation.getNotReclaimBefore()).thenReturn(DB_NOW.plusSeconds(125));
    when(intentRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void durationAdmissionLocksInOrderAndDerivesDeadlineFromOneDatabaseTimeSample() {
    LlmUsageAttemptIntent draft = draft("physical-1");
    LlmExecutionOperationAdmissionService.AdmittedAttempt admission =
        service.admit(
            REFERENCE,
            LlmExecutionOperationAuthority.issue(claim, "owner-a"),
            draft,
            1200,
            Duration.ofSeconds(120),
            Duration.ofSeconds(5));
    LlmUsageAttemptIntent admitted = admission.intent();

    InOrder locks = inOrder(contextRepository, operationRepository);
    locks.verify(contextRepository).findForUpdate("llmctx-admit", "platform:internal");
    locks.verify(operationRepository).findForUpdate("llmop-admit", "llmctx-admit");
    verify(contextRepository, times(1)).databaseNow();
    verify(budgetGate).reserveLockedContext(context, 1200);
    verify(operationRepository).saveAndFlush(operation);
    verify(intentRepository).saveAndFlush(draft);
    assertThat(admission.transportDeadline()).isEqualTo(DB_NOW.plusSeconds(120));
    assertThat(admitted.getStartedAt()).isEqualTo(DB_NOW);
    assertThat(admitted.getOperationFenceVersion()).isEqualTo((short) 1);
    assertThat(admitted.getLlmExecutionContextId()).isEqualTo("llmctx-admit");
    assertThat(admitted.getLlmExecutionOperationId()).isEqualTo("llmop-admit");
    assertThat(admitted.getLlmExecutionOperationAttempt()).isEqualTo(2);
    assertThat(admitted.getLlmExecutionLeaseToken()).isEqualTo(TOKEN);
    assertThat(admitted.getOperationNotReclaimBefore()).isEqualTo(DB_NOW.plusSeconds(125));
    assertThat(admitted.getTransportDeadlineAt()).isEqualTo(DB_NOW.plusSeconds(120));
  }

  @Test
  void durationAdmissionAllowsExactlyTwentyFourHoursButRejectsTimeoutPlusSafetyBeyondIt() {
    Instant maximumDeadline = DB_NOW.plus(Duration.ofHours(24));
    when(operation.admitTransport("owner-a", TOKEN, 2, DB_NOW, maximumDeadline)).thenReturn(true);
    when(operation.getNotReclaimBefore()).thenReturn(maximumDeadline);

    assertThat(
            service
                .admit(
                    REFERENCE,
                    LlmExecutionOperationAuthority.issue(claim, "owner-a"),
                    draft("physical-max"),
                    0,
                    Duration.ofHours(24),
                    Duration.ZERO)
                .transportDeadline())
        .isEqualTo(maximumDeadline);

    assertThatThrownBy(
            () ->
                service.admit(
                    REFERENCE,
                    LlmExecutionOperationAuthority.issue(claim, "owner-a"),
                    draft("physical-too-long"),
                    0,
                    Duration.ofHours(24),
                    Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds 24 hours");
  }

  @Test
  void staleOwnerAndCancellationFailBeforeBudgetOrIntent() {
    assertThatThrownBy(
            () ->
                service.admit(
                    REFERENCE,
                    LlmExecutionOperationAuthority.issue(claim, "stale-owner"),
                    draft("physical-stale"),
                    1200,
                    DB_NOW.plusSeconds(120),
                    Duration.ofSeconds(5)))
        .isInstanceOf(LlmExecutionOperationException.class)
        .extracting(error -> ((LlmExecutionOperationException) error).reason())
        .isEqualTo(LlmExecutionOperationException.Reason.LEASE_NOT_OWNED);

    when(operation.getStatus()).thenReturn(LlmExecutionOperation.Status.CANCEL_REQUESTED);
    assertThatThrownBy(
            () ->
                service.admit(
                    REFERENCE,
                    LlmExecutionOperationAuthority.issue(claim, "owner-a"),
                    draft("physical-cancel"),
                    1200,
                    DB_NOW.plusSeconds(120),
                    Duration.ofSeconds(5)))
        .isInstanceOf(LlmExecutionOperationException.class)
        .extracting(error -> ((LlmExecutionOperationException) error).reason())
        .isEqualTo(LlmExecutionOperationException.Reason.LEASE_NOT_OWNED);

    verify(budgetGate, never())
        .reserveLockedContext(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(intentRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void applicationClockCannotOverrideDatabaseClock() {
    when(contextRepository.databaseNow()).thenReturn(DB_NOW.plusSeconds(200));

    assertThatThrownBy(
            () ->
                service.admit(
                    REFERENCE,
                    LlmExecutionOperationAuthority.issue(claim, "owner-a"),
                    draft("physical-clock"),
                    1200,
                    DB_NOW.plusSeconds(120),
                    Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("transportDeadline must be in the future");
    verify(budgetGate, never())
        .reserveLockedContext(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void authorityIsRedactedAndIgnoredWhenNestedInJson() throws Exception {
    LlmExecutionOperationAuthority authority =
        LlmExecutionOperationAuthority.issue(claim, "owner-a");

    assertThat(authority.toString()).isEqualTo("LlmExecutionOperationAuthority[REDACTED]");
    assertThat(new ObjectMapper().writeValueAsString(new AuthorityEnvelope(authority)))
        .isEqualTo("{}");
  }

  private static LlmUsageAttemptIntent draft(String physicalAttemptId) {
    return LlmUsageAttemptIntent.builder()
        .physicalAttemptId(physicalAttemptId)
        .logicalCallId("logical-1")
        .attemptKind("PRIMARY")
        .attemptOrdinal(1)
        .correlationId("correlation")
        .feature("test")
        .sourceType("INTERNAL")
        .sourceId("source")
        .runId("run")
        .agentRole("test")
        .humanRoleSnapshot("SUPER_ADMIN")
        .roleProvenance("CAPTURED_AT_DISPATCH")
        .provider("openai")
        .model("gpt-5.4-mini")
        .modelTier("LIGHTWEIGHT")
        .keySource("PLATFORM")
        .startedAt(DB_NOW.minusSeconds(1000))
        .build();
  }

  private record AuthorityEnvelope(LlmExecutionOperationAuthority authority) {}
}
