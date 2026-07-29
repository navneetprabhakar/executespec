package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
class LlmExecutionOperationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
  private static final LlmExecutionScope SCOPE = LlmExecutionScope.customer("org-a");

  @Mock private LlmExecutionContextRepository contexts;
  @Mock private LlmExecutionOperationRepository operations;

  private LlmExecutionContext context;
  private LlmExecutionReference reference;
  private LlmExecutionOperationService service;

  @BeforeEach
  void setUp() {
    context = context();
    reference = new LlmExecutionReference(context.getId(), SCOPE);
    service = new LlmExecutionOperationService(contexts, operations);
    lenient().when(contexts.databaseNow()).thenReturn(NOW);
    lenient()
        .when(contexts.findForUpdate(context.getId(), SCOPE.isolationKey()))
        .thenReturn(Optional.of(context));
    lenient()
        .when(operations.saveAndFlush(any(LlmExecutionOperation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(operations.save(any(LlmExecutionOperation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createsAndClaimsFirstInvocationWithoutMutatingSharedContextLifecycle() {
    var descriptor = descriptor("worker/task-1", "identity-a");
    when(operations.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), descriptor.operationKey()))
        .thenReturn(Optional.empty());

    LlmExecutionOperationClaim claim =
        service
            .claimOrResume(reference, descriptor, "owner-a", Duration.ofMinutes(2))
            .orElseThrow();

    assertThat(claim.context()).isEqualTo(reference);
    assertThat(claim.invocation()).isEqualTo(1);
    assertThat(claim.attempt()).isEqualTo(1);
    assertThat(claim.leaseToken()).isNotNull();
    assertThat(context.getStatus()).isEqualTo(LlmExecutionContext.Status.QUEUED);
  }

  @Test
  void claimUsesExactlyOneDatabaseClockSampleForAllLifecycleDecisions() {
    var descriptor = descriptor("worker/task-database-clock", "identity-db-clock");
    when(operations.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), descriptor.operationKey()))
        .thenReturn(Optional.empty());
    clearInvocations(contexts);

    service.claimOrResume(reference, descriptor, "owner-a", Duration.ofMinutes(2)).orElseThrow();

    var order = inOrder(contexts);
    order.verify(contexts).findForUpdate(context.getId(), SCOPE.isolationKey());
    order.verify(contexts, times(1)).databaseNow();
  }

  @Test
  void sameOperationKeyWithDifferentImmutableIdentityFailsClosed() {
    var original = descriptor("worker/task-1", "identity-a");
    LlmExecutionOperation existing =
        LlmExecutionOperation.queued("llmop_existing", context.getId(), original, 1, NOW);
    when(operations.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), original.operationKey()))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.claimOrResume(
                    reference,
                    descriptor("worker/task-1", "identity-b"),
                    "owner-a",
                    Duration.ofMinutes(2)))
        .isInstanceOf(LlmExecutionOperationException.class)
        .extracting(error -> ((LlmExecutionOperationException) error).reason())
        .isEqualTo(LlmExecutionOperationException.Reason.IDENTITY_CONFLICT);
  }

  @Test
  void explicitNewInvocationRequiresPreviousInvocationToBeTerminal() {
    var descriptor = descriptor("review/general", "review-a");
    LlmExecutionOperation active =
        LlmExecutionOperation.queued("llmop_active", context.getId(), descriptor, 1, NOW);
    active.claim("owner-a", NOW, Duration.ofMinutes(1));
    when(operations.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), descriptor.operationKey()))
        .thenReturn(Optional.of(active));

    assertThatThrownBy(
            () ->
                service.beginNewInvocation(reference, descriptor, "owner-b", Duration.ofMinutes(2)))
        .isInstanceOf(LlmExecutionOperationException.class)
        .extracting(error -> ((LlmExecutionOperationException) error).reason())
        .isEqualTo(LlmExecutionOperationException.Reason.ACTIVE_INVOCATION_EXISTS);
  }

  @Test
  void exactPriorClaimCanBeResumedButOwnerStringCannotRecoverItsToken() {
    var descriptor = descriptor("worker/task-1", "identity-a");
    LlmExecutionOperation active =
        LlmExecutionOperation.queued("llmop_active", context.getId(), descriptor, 1, NOW);
    active.claim("owner-a", NOW, Duration.ofMinutes(2));
    var exact =
        new LlmExecutionOperationClaim(
            reference, active.getId(), 1, active.getAttempt(), active.getLeaseToken());
    when(operations.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), descriptor.operationKey()))
        .thenReturn(Optional.of(active));
    when(operations.findForUpdate(active.getId(), context.getId())).thenReturn(Optional.of(active));

    assertThat(service.claimOrResume(reference, descriptor, "owner-a", Duration.ofMinutes(2)))
        .isEmpty();
    assertThat(service.resumeOwned(exact, "owner-a", Duration.ofMinutes(2))).contains(exact);
    assertThat(
            service.resumeOwned(
                new LlmExecutionOperationClaim(
                    reference, active.getId(), 1, 1, java.util.UUID.randomUUID()),
                "owner-a",
                Duration.ofMinutes(2)))
        .isEmpty();
  }

  @Test
  void redactionCoversHeadersJsonBareBearerAndCommonServiceTokens() {
    String secrets =
        "Authorization: Bearer eyJheader12345.eyJpayload12345.signature12345 "
            + "{\"api_key\":\"sk-secretvalue123456\"} "
            + "Bearer abcdefghijklmnopqrstuvwxyz "
            + "ghp_abcdefghijklmnopqrstuvwxyz xoxb-123456789012-secret";

    assertThat(LlmExecutionOperationService.redact(secrets))
        .doesNotContain(
            "eyJheader12345",
            "sk-secretvalue123456",
            "abcdefghijklmnopqrstuvwxyz",
            "xoxb-123456789012-secret")
        .contains("[REDACTED]");
  }

  @Test
  void dueCancellationSweepTerminalizesAnOwnerlessExpiredCancellation() {
    var descriptor = descriptor("worker/task-1", "identity-a");
    Instant old = NOW.minus(Duration.ofMinutes(5));
    LlmExecutionOperation operation =
        LlmExecutionOperation.queued("llmop_cancel", context.getId(), descriptor, 1, old);
    operation.claim("dead-owner", old, Duration.ofMinutes(1));
    operation.requestCancellation(old.plusSeconds(1));
    var candidate = mock(LlmExecutionOperationRepository.CancellationCandidate.class);
    when(candidate.getOperationId()).thenReturn(operation.getId());
    when(candidate.getContextId()).thenReturn(context.getId());
    when(operations.findDueCancellationCandidates(
            LlmExecutionOperation.Status.CANCEL_REQUESTED,
            NOW,
            org.springframework.data.domain.PageRequest.of(0, 25)))
        .thenReturn(List.of(candidate));
    when(contexts.findForUpdateById(context.getId())).thenReturn(Optional.of(context));
    when(operations.findForUpdate(operation.getId(), context.getId()))
        .thenReturn(Optional.of(operation));

    assertThat(service.finalizeDueCancellations(25)).isEqualTo(1);
    assertThat(operation.getStatus()).isEqualTo(LlmExecutionOperation.Status.CANCELLED);
  }

  @Test
  void terminalPreviousInvocationAllowsExplicitRetryWithNextInvocationNumber() {
    var firstDescriptor = descriptor("worker/task-1", "initial-route");
    LlmExecutionOperation previous =
        LlmExecutionOperation.queued("llmop_previous", context.getId(), firstDescriptor, 1, NOW);
    previous.claim("owner-a", NOW, Duration.ofMinutes(1));
    previous.complete("owner-a", previous.getLeaseToken(), 1, NOW.plusSeconds(1));
    var retryDescriptor = descriptor("worker/task-1", "approved-new-route");
    when(operations.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), retryDescriptor.operationKey()))
        .thenReturn(Optional.of(previous));

    LlmExecutionOperationClaim retry =
        service.beginNewInvocation(reference, retryDescriptor, "owner-b", Duration.ofMinutes(2));

    assertThat(retry.invocation()).isEqualTo(2);
    assertThat(retry.attempt()).isEqualTo(1);
  }

  @Test
  void retryDelayIsDerivedFromDatabaseTimeAndBounded() {
    var descriptor = descriptor("worker/task-retry", "identity-retry");
    LlmExecutionOperation active =
        LlmExecutionOperation.queued("llmop_retry", context.getId(), descriptor, 1, NOW);
    active.claim("owner-a", NOW, Duration.ofMinutes(2));
    var claim =
        new LlmExecutionOperationClaim(
            reference, active.getId(), 1, active.getAttempt(), active.getLeaseToken());
    when(operations.findForUpdate(active.getId(), context.getId())).thenReturn(Optional.of(active));

    assertThat(
            service.fail(
                claim,
                "owner-a",
                new LlmExecutionOperationService.Failure(
                    "TRANSIENT", "retry later", true, Duration.ofMinutes(7))))
        .isTrue();
    assertThat(active.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(7)));

    assertThatThrownBy(
            () ->
                service.fail(
                    claim,
                    "owner-a",
                    new LlmExecutionOperationService.Failure(
                        "TRANSIENT", "retry later", true, Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retryDelay");
    assertThatThrownBy(
            () ->
                service.fail(
                    claim,
                    "owner-a",
                    new LlmExecutionOperationService.Failure(
                        "TRANSIENT", "retry later", true, Duration.ofHours(25))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retryDelay");
  }

  private static LlmExecutionOperationDescriptor descriptor(String key, String identity) {
    return LlmExecutionOperationDescriptor.of(
        key, key.startsWith("review") ? "REVIEW" : "WORKER", identity, 3);
  }

  private static LlmExecutionContext context() {
    LlmPolicySnapshot policy =
        new LlmPolicySnapshot(
            LlmPolicySnapshot.KeySourceMode.OWN_KEYS,
            "user-a",
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
    return LlmExecutionContext.queued(
        "llmctx_test",
        "a".repeat(64),
        new LlmExecutionContextCreateRequest(
            LlmExecutionContext.ContextType.PIPELINE_RUN,
            "pipeline",
            "PIPELINE_RUN",
            "run-1",
            null,
            "run-1",
            SCOPE,
            "user-a",
            "org-a",
            null,
            null,
            policy,
            LlmExecutionBudget.UNBOUNDED,
            3,
            "route-v1",
            null),
        NOW);
  }
}
