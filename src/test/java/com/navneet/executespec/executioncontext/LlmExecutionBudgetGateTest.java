package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RR-321 — pins the reservation arithmetic the {@link LlmExecutionBudgetGate} evaluates under its
 * pessimistic lock. The lock/serialization property itself is structural ({@code REQUIRES_NEW} +
 * {@code findForUpdate}); these tests pin what the gate DECIDES so a second authorizer racing at
 * the last unit of a dimension is refused, and so the honest residual (input/cost) is not
 * over-reserved.
 */
class LlmExecutionBudgetGateTest {

  private final LlmExecutionContextRepository repository =
      mock(LlmExecutionContextRepository.class);
  private final RunStepUsageRepository usage = mock(RunStepUsageRepository.class);
  private final ModelRegistry models = mock(ModelRegistry.class);
  private LlmExecutionBudgetGate gate;
  private LlmExecutionContext context;

  @BeforeEach
  void setUp() {
    gate = new LlmExecutionBudgetGate(repository, usage, models);
    context = mock(LlmExecutionContext.class);
    when(context.getId()).thenReturn("ctx-1");
    when(context.getIsolationKey()).thenReturn("org:org-a");
    when(repository.findForUpdate("ctx-1", "org:org-a")).thenReturn(Optional.of(context));
    when(context.policySnapshot()).thenReturn(policy());
    when(models.maxOutputForModel("m")).thenReturn(600);
    // Default: no recorded usage, no prior reservation, no ceilings.
    when(context.getReservedProviderCalls()).thenReturn(0L);
    when(usage.countByLlmExecutionContextId("ctx-1")).thenReturn(0L);
    when(usage.sumSuccessfulInputTokensByContextId("ctx-1")).thenReturn(0L);
    when(usage.sumSuccessfulOutputTokensByContextId("ctx-1")).thenReturn(0L);
    when(usage.sumSuccessfulCostByContextId("ctx-1")).thenReturn(0.0);
    when(context.getMaxProviderCalls()).thenReturn(null);
    when(context.getMaxInputTokens()).thenReturn(null);
    when(context.getMaxOutputTokens()).thenReturn(null);
    when(context.getMaxTotalTokens()).thenReturn(null);
    when(context.getMaxCostUsd()).thenReturn(null);
  }

  @Test
  void firstOutputCallWithinCeilingIsAdmittedAndReserved() {
    when(context.getMaxOutputTokens()).thenReturn(1000L);

    gate.reserveProviderCall(context);

    // One in-flight call (this one) reserves 600 <= 1000.
    verify(context).recordProviderCallReservation(1L);
    verify(repository).save(context);
  }

  @Test
  void firstBoundedChatCallUsesItsTransportCapNotLargestAllowedModel() {
    when(context.getMaxTotalTokens()).thenReturn(40_000L);
    when(models.maxOutputForModel("m")).thenReturn(131_072);

    gate.reserveProviderCall(context, 1_200);

    verify(context).recordProviderCallReservation(1L);
    verify(repository).save(context);
  }

  @Test
  void secondConcurrentCallThatWouldCollectivelyExceedOutputCeilingIsRefused() {
    when(context.getMaxOutputTokens()).thenReturn(1000L);
    // One call already authorized and in flight (no terminal row yet).
    when(context.getReservedProviderCalls()).thenReturn(1L);
    when(usage.countByLlmExecutionContextId("ctx-1")).thenReturn(0L);

    // authorizedAfter=2, inFlight=2, projectedOutput=2*600=1200 > 1000 -> refuse.
    assertThatThrownBy(() -> gate.reserveProviderCall(context))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(e -> ((LlmDispatchPolicyException) e).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED);
    verify(context, never()).recordProviderCallReservation(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void completedCallsReconcileTheOutputReservationDownToActual() {
    when(context.getMaxOutputTokens()).thenReturn(1000L);
    // Two calls authorized, both already recorded terminal with a SMALL actual output.
    when(context.getReservedProviderCalls()).thenReturn(2L);
    when(usage.countByLlmExecutionContextId("ctx-1")).thenReturn(2L);
    when(usage.sumSuccessfulOutputTokensByContextId("ctx-1")).thenReturn(100L);

    // A third call: inFlight=1, projected=100 + 600 = 700 <= 1000. Worst-case did NOT accumulate;
    // recorded output subsumed the finished reservations. Admitted.
    gate.reserveProviderCall(context);

    verify(context).recordProviderCallReservation(3L);
    verify(repository).save(context);
  }

  @Test
  void providerCallCountRemainsAHardCeiling() {
    when(context.getMaxProviderCalls()).thenReturn(3);
    when(context.getReservedProviderCalls()).thenReturn(3L);

    assertThatThrownBy(() -> gate.reserveProviderCall(context))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(e -> ((LlmDispatchPolicyException) e).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED);
  }

  @Test
  void costCeilingIsReconciledAfterTheFactAndDoesNotOverReserve() {
    when(context.getMaxCostUsd()).thenReturn(new BigDecimal("10.00"));
    // Many prior calls, dollars still below the ceiling -> admit (no worst-case dollar
    // reservation).
    when(context.getReservedProviderCalls()).thenReturn(20L);
    when(usage.countByLlmExecutionContextId("ctx-1")).thenReturn(20L);
    when(usage.sumSuccessfulCostByContextId("ctx-1")).thenReturn(2.0);

    gate.reserveProviderCall(context);

    verify(context).recordProviderCallReservation(21L);
    // A recorded cost at/over the ceiling stops immediately.
    when(usage.sumSuccessfulCostByContextId("ctx-1")).thenReturn(10.0);
    assertThatThrownBy(() -> gate.reserveProviderCall(context))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(e -> ((LlmDispatchPolicyException) e).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED);
  }

  @Test
  void missingContextUnderTheLockIsRefused() {
    when(repository.findForUpdate(anyString(), anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> gate.reserveProviderCall(context))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(e -> ((LlmDispatchPolicyException) e).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.CONTEXT_NOT_FOUND);
  }

  private static LlmPolicySnapshot policy() {
    return new LlmPolicySnapshot(
        LlmPolicySnapshot.KeySourceMode.SYSTEM,
        "user-a",
        Set.of("openai"),
        Map.of("openai", List.of("m")),
        Map.of("openai", LlmPolicySnapshot.CredentialSource.PLATFORM),
        Map.of(),
        null,
        null,
        LlmPolicySnapshot.RoutingMode.PROVIDER_PREFERRED,
        false,
        true,
        1);
  }
}
