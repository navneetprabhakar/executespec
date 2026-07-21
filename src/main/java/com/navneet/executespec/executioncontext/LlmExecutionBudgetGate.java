package com.navneet.executespec.executioncontext;

import java.math.BigDecimal;

import com.navneet.executespec.cost.RunStepUsageRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * RR-321 (2026-07-20) — the atomic budget seam for durable LLM execution contexts.
 *
 * <p>{@code LlmExecutionDispatchGuard} previously authorized a provider call by running three
 * independent read-only aggregates and then returning permission; the corresponding usage rows are
 * written only after the provider responds. Two dispatches racing at the last unit of any budget
 * dimension therefore both passed, so {@code maxProviderCalls}, the token ceilings and {@code
 * maxCostUsd} were advisory rather than hard under concurrency — and the router itself creates
 * concurrency through parallel read batches, retries and failover.
 *
 * <p>This gate takes a pessimistic row lock on the context, evaluates every dimension while holding
 * it, and increments the V316 reservation counter before returning. Consequences:
 *
 * <ul>
 *   <li><b>Call count is now a hard ceiling.</b> Each authorization consumes exactly one unit and
 *       the consumption is committed before transport, so N concurrent callers cannot exceed {@code
 *       maxProviderCalls}.
 *   <li><b>Token and cost ceilings are serialized, not reserved.</b> A call's token and dollar
 *       consumption is not knowable before dispatch, so those dimensions are still evaluated
 *       against recorded terminal usage — but now under the lock, as one consistent read, and with
 *       in-flight authorized calls visible. Concurrent authorizers can therefore no longer read
 *       mutually stale aggregates, and a context with in-flight calls that has already crossed a
 *       ceiling stops immediately. A true token/cost reservation needs a conservative per-call
 *       worst-case estimate reconciled after the fact; that is deliberately NOT invented here.
 * </ul>
 *
 * <p>Runs in its own transaction so the reservation commits even if the caller's surrounding
 * transaction later rolls back — an authorization that reached transport must not be given back.
 */
@Service
@RequiredArgsConstructor
class LlmExecutionBudgetGate {

  private final LlmExecutionContextRepository repository;
  private final RunStepUsageRepository usageRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void reserveProviderCall(LlmExecutionContext snapshot) {
    LlmExecutionContext context =
        repository
            .findForUpdate(snapshot.getId(), snapshot.getIsolationKey())
            .orElseThrow(
                () ->
                    new LlmDispatchPolicyException(
                        LlmDispatchPolicyException.Reason.CONTEXT_NOT_FOUND,
                        "LLM execution context disappeared before budget reservation"));

    String id = context.getId();
    long recordedCalls = usageRepository.countByLlmExecutionContextId(id);
    long reserved = Math.max(context.getReservedProviderCalls(), recordedCalls);
    if (context.getMaxProviderCalls() != null && reserved + 1 > context.getMaxProviderCalls()) {
      throw budget();
    }

    long input = value(usageRepository.sumSuccessfulInputTokensByContextId(id));
    long output = value(usageRepository.sumSuccessfulOutputTokensByContextId(id));
    if ((context.getMaxInputTokens() != null && input >= context.getMaxInputTokens())
        || (context.getMaxOutputTokens() != null && output >= context.getMaxOutputTokens())
        || (context.getMaxTotalTokens() != null
            && safeAdd(input, output) >= context.getMaxTotalTokens())) {
      throw budget();
    }
    if (context.getMaxCostUsd() != null) {
      double spent = value(usageRepository.sumSuccessfulCostByContextId(id));
      if (BigDecimal.valueOf(spent).compareTo(context.getMaxCostUsd()) >= 0) throw budget();
    }

    context.recordProviderCallReservation(reserved + 1);
    repository.save(context);
  }

  private static long value(Long value) {
    return value == null ? 0L : value;
  }

  private static double value(Double value) {
    return value == null ? 0D : value;
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static LlmDispatchPolicyException budget() {
    return new LlmDispatchPolicyException(
        LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED,
        "Durable LLM execution-context budget is exhausted");
  }
}
