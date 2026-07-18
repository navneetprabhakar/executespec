package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmExecutionContextServiceTest {

  private static final LlmExecutionScope ORG_A = LlmExecutionScope.customer("org-a");
  private static final LlmExecutionScope ORG_B = LlmExecutionScope.customer("org-b");

  @Mock private LlmExecutionContextRepository repository;

  private MutableClock clock;
  private LlmExecutionContextService service;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-07-17T00:00:00Z"));
    service = new LlmExecutionContextService(repository, clock);
  }

  @Test
  void createIsIdempotentButRejectsPayloadDriftForTheSameKey() {
    LlmExecutionContextCreateRequest original = request(2, LlmExecutionBudget.UNBOUNDED);
    LlmExecutionContext created = create(original);

    whenIdempotencyLookup(original).thenReturn(Optional.of(created));
    assertThat(service.create(original)).isSameAs(created);

    LlmExecutionContextCreateRequest changed =
        request(2, new LlmExecutionBudget(10_000L, 2_000L, 12_000L, new BigDecimal("1.25"), 12));
    whenIdempotencyLookup(changed).thenReturn(Optional.of(created));
    assertThatThrownBy(() -> service.create(changed))
        .isInstanceOf(LlmExecutionContextException.class)
        .extracting(e -> ((LlmExecutionContextException) e).reason())
        .isEqualTo(LlmExecutionContextException.Reason.IDEMPOTENCY_CONFLICT);
  }

  @Test
  void tenantScopedLoadNeverFallsBackToAnUnscopedLookup() {
    LlmExecutionContext context = create(request(2, LlmExecutionBudget.UNBOUNDED));
    when(repository.findByIdAndIsolationKey(context.getId(), ORG_B.isolationKey()))
        .thenReturn(Optional.empty());

    assertThat(service.load(ORG_B, context.getId())).isEmpty();
    verify(repository, never()).findById(context.getId());
  }

  @Test
  void immutableReferenceRecoveryUsesTheOriginalTenantScopedTuple() {
    LlmExecutionContextCreateRequest request = request(2, LlmExecutionBudget.UNBOUNDED);
    LlmExecutionContext context = create(request);
    whenIdempotencyLookup(request).thenReturn(Optional.of(context));

    assertThat(
            service.findReference(
                ORG_A,
                request.feature(),
                request.sourceType(),
                request.sourceId(),
                request.idempotencyKey()))
        .contains(new LlmExecutionReference(context.getId(), ORG_A));
  }

  @Test
  void activeLeaseCannotBeStolenButExpiredLeaseCanBeClaimedByANewOwner() {
    LlmExecutionContext context = create(request(3, LlmExecutionBudget.UNBOUNDED));
    lock(context, ORG_A);

    assertThat(service.claim(ORG_A, context.getId(), "worker-a", Duration.ofSeconds(30)))
        .contains(context);
    assertThat(context.getAttempt()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(10));
    assertThat(service.claim(ORG_A, context.getId(), "worker-b", Duration.ofSeconds(30))).isEmpty();
    assertThat(context.getLeaseOwner()).isEqualTo("worker-a");

    clock.advance(Duration.ofSeconds(21));
    assertThat(service.claim(ORG_A, context.getId(), "worker-b", Duration.ofSeconds(30)))
        .contains(context);
    assertThat(context.getAttempt()).isEqualTo(2);
    assertThat(context.getLeaseOwner()).isEqualTo("worker-b");
    assertThat(service.heartbeat(ORG_A, context.getId(), "worker-a", Duration.ofSeconds(30)))
        .isFalse();
    assertThat(service.complete(ORG_A, context.getId(), "worker-a")).isFalse();
    assertThat(service.complete(ORG_A, context.getId(), "worker-b")).isTrue();
    assertThat(context.getStatus()).isEqualTo(LlmExecutionContext.Status.SUCCEEDED);
  }

  @Test
  void cancellationFencesHeartbeatsAndRequiresTheActiveOwnerToAcknowledge() {
    LlmExecutionContext context = create(request(2, LlmExecutionBudget.UNBOUNDED));
    lock(context, ORG_A);
    service.claim(ORG_A, context.getId(), "worker-a", Duration.ofMinutes(1));

    assertThat(service.requestCancellation(ORG_A, context.getId()))
        .contains(LlmExecutionContext.Status.CANCEL_REQUESTED);
    assertThat(service.heartbeat(ORG_A, context.getId(), "worker-a", Duration.ofMinutes(1)))
        .isFalse();
    assertThat(service.acknowledgeCancellation(ORG_A, context.getId(), "worker-b")).isFalse();
    assertThat(service.acknowledgeCancellation(ORG_A, context.getId(), "worker-a")).isTrue();
    assertThat(context.getStatus()).isEqualTo(LlmExecutionContext.Status.CANCELLED);
  }

  @Test
  void queuedCancellationIsImmediateAndCannotBeClaimed() {
    LlmExecutionContext context = create(request(2, LlmExecutionBudget.UNBOUNDED));
    lock(context, ORG_A);

    assertThat(service.requestCancellation(ORG_A, context.getId()))
        .contains(LlmExecutionContext.Status.CANCELLED);
    assertThat(service.claim(ORG_A, context.getId(), "worker-a", Duration.ofMinutes(1))).isEmpty();
  }

  @Test
  void retryableFailureRequeuesUntilAttemptBudgetIsExhaustedAndRedactsSecrets() {
    LlmExecutionContext context = create(request(2, LlmExecutionBudget.UNBOUNDED));
    lock(context, ORG_A);
    service.claim(ORG_A, context.getId(), "worker-a", Duration.ofMinutes(1));

    assertThat(
            service.fail(
                ORG_A,
                context.getId(),
                "worker-a",
                new LlmExecutionContextService.Failure(
                    "provider timeout",
                    "api_key=sk-this-must-not-survive",
                    true,
                    clock.instant().plusSeconds(5))))
        .isTrue();
    assertThat(context.getStatus()).isEqualTo(LlmExecutionContext.Status.QUEUED);
    assertThat(context.getLastErrorRedacted()).doesNotContain("sk-this-must-not-survive");
    assertThat(service.claim(ORG_A, context.getId(), "worker-b", Duration.ofMinutes(1))).isEmpty();

    clock.advance(Duration.ofSeconds(6));
    service.claim(ORG_A, context.getId(), "worker-b", Duration.ofMinutes(1));
    assertThat(
            service.fail(
                ORG_A,
                context.getId(),
                "worker-b",
                new LlmExecutionContextService.Failure(
                    "provider timeout", "still unavailable", true, null)))
        .isTrue();
    assertThat(context.getStatus()).isEqualTo(LlmExecutionContext.Status.FAILED);
    assertThat(context.getAttempt()).isEqualTo(2);
  }

  @Test
  void customerContextSeparatesInitiatingActorFromCredentialSubject() {
    LlmPolicySnapshot hostCredentialPolicy = policy("host-org-key-user");
    LlmExecutionContext context =
        create(
            new LlmExecutionContextCreateRequest(
                LlmExecutionContext.ContextType.PROJECT_JOB,
                "architecture",
                "PROJECT",
                "project-1",
                null,
                "corr-1",
                ORG_A,
                "initiating-project-member",
                "project-1",
                null,
                hostCredentialPolicy,
                LlmExecutionBudget.UNBOUNDED,
                1,
                "idem-actor-key-subject",
                null));

    assertThat(context.getUserId()).isEqualTo("initiating-project-member");
    assertThat(context.getKeySubjectUserId()).isEqualTo("host-org-key-user");
  }

  private LlmExecutionContext create(LlmExecutionContextCreateRequest request) {
    when(repository.saveAndFlush(any(LlmExecutionContext.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    whenIdempotencyLookup(request).thenReturn(Optional.empty());
    return service.create(request);
  }

  private void lock(LlmExecutionContext context, LlmExecutionScope scope) {
    when(repository.save(any(LlmExecutionContext.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.findForUpdate(context.getId(), scope.isolationKey()))
        .thenReturn(Optional.of(context));
  }

  private org.mockito.stubbing.OngoingStubbing<Optional<LlmExecutionContext>> whenIdempotencyLookup(
      LlmExecutionContextCreateRequest request) {
    return when(
        repository.findByIsolationKeyAndFeatureAndSourceTypeAndSourceIdAndIdempotencyKey(
            request.scope().isolationKey(),
            request.feature(),
            request.sourceType(),
            request.sourceId(),
            request.idempotencyKey()));
  }

  private static LlmExecutionContextCreateRequest request(
      int maxAttempts, LlmExecutionBudget budget) {
    return new LlmExecutionContextCreateRequest(
        LlmExecutionContext.ContextType.PROJECT_JOB,
        "architecture",
        "PROJECT",
        "project-1",
        null,
        "corr-1",
        ORG_A,
        "user-1",
        "project-1",
        null,
        policy("user-1"),
        budget,
        maxAttempts,
        "idem-1",
        null);
  }

  private static LlmPolicySnapshot policy(String userId) {
    return new LlmPolicySnapshot(
        LlmPolicySnapshot.KeySourceMode.OWN_KEYS,
        userId,
        Set.of("deepseek"),
        Map.of("deepseek", List.of("deepseek-v4-pro-native")),
        Map.of("deepseek", LlmPolicySnapshot.CredentialSource.ORG),
        Map.of(),
        "deepseek",
        "deepseek-v4-pro-native",
        LlmPolicySnapshot.RoutingMode.EXACT,
        true,
        false,
        1);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
