package com.navneet.executespec.executioncontext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.router.ModelRegistry;

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
 * it, and commits its reservation before returning. What is now RESERVED vs still
 * reconciled-after-the-fact:
 *
 * <ul>
 *   <li><b>Provider-call count is a hard ceiling (V316, reserved).</b> Each authorization consumes
 *       exactly one unit of {@code reserved_provider_calls} and the consumption is committed before
 *       transport, so N concurrent callers cannot exceed {@code maxProviderCalls}. Worst-case
 *       equals actual for a call (one authorization is one dispatch), so {@code max(reserved,
 *       recorded)} self-heals with no release path.
 *   <li><b>Output tokens are reserved at their real per-call ceiling.</b> A provider call cannot
 *       emit more than the dispatched model's registered {@code max-output-tokens} — that is the
 *       {@code max_tokens} the transport passes, an API-enforced hard cap, not an estimate. The
 *       gate charges every authorized-but-not-yet-recorded (in-flight) call the worst-case output
 *       of the largest model the context's policy admits, so N concurrent dispatches cannot
 *       collectively cross {@code maxOutputTokens}. This reservation is derived from {@code
 *       reserved_provider_calls} minus recorded terminal rows, so it self-heals as calls
 *       terminalize (recorded output grows, the in-flight count shrinks) and needs no release path
 *       and no extra column. It also tightens the {@code maxTotalTokens} check by its output half.
 *   <li><b>Input tokens and dollar cost stay reconciled-after-the-fact.</b> A call's input-token
 *       count and its dollar cost are only known once the provider responds, and the sole a-priori
 *       input bound — the model's full context window — would deny admission far below the real
 *       ceiling, so it is not a usable reservation. {@code maxInputTokens}, the input half of
 *       {@code maxTotalTokens}, and {@code maxCostUsd} are therefore evaluated against recorded
 *       terminal usage, but now under the lock as one consistent read with in-flight authorized
 *       calls visible: concurrent authorizers can no longer read mutually stale aggregates, and a
 *       context that has already crossed one of those ceilings stops immediately. A worst-case
 *       dollar reservation is deliberately NOT invented, because no honest per-call cost bound
 *       exists before the response.
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
  private final ModelRegistry modelRegistry;

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
    long authorizedAfter = reserved + 1;
    if (context.getMaxProviderCalls() != null && authorizedAfter > context.getMaxProviderCalls()) {
      throw budget();
    }

    long input = value(usageRepository.sumSuccessfulInputTokensByContextId(id));
    long output = value(usageRepository.sumSuccessfulOutputTokensByContextId(id));

    // In-flight = authorized dispatches (including this one) that have not yet written a terminal
    // usage row. As each call terminalizes, recordedCalls grows and this shrinks, so the token
    // reservation self-heals to actual with no release path and no crash leak — the same property
    // the call-count reservation relies on. Never below 1, because this call is itself in flight.
    long inFlight = Math.max(1L, authorizedAfter - recordedCalls);
    long worstCaseOutputPerCall = worstCaseOutputTokensPerCall(context);
    long projectedOutput = safeAdd(output, saturatingMul(inFlight, worstCaseOutputPerCall));

    if (context.getMaxInputTokens() != null && input >= context.getMaxInputTokens()) {
      throw budget();
    }
    if (context.getMaxOutputTokens() != null
        && (output >= context.getMaxOutputTokens()
            || projectedOutput > context.getMaxOutputTokens())) {
      throw budget();
    }
    if (context.getMaxTotalTokens() != null
        && (safeAdd(input, output) >= context.getMaxTotalTokens()
            || safeAdd(input, projectedOutput) > context.getMaxTotalTokens())) {
      throw budget();
    }
    if (context.getMaxCostUsd() != null) {
      double spent = value(usageRepository.sumSuccessfulCostByContextId(id));
      if (BigDecimal.valueOf(spent).compareTo(context.getMaxCostUsd()) >= 0) throw budget();
    }

    context.recordProviderCallReservation(authorizedAfter);
    repository.save(context);
  }

  /**
   * The largest per-call output ceiling the context's policy can dispatch. Every model this policy
   * allows carries a registered {@code max-output-tokens} that the transport passes as {@code
   * max_tokens}; a single call cannot exceed its model's value. Taking the maximum across all
   * allowed models makes the reservation a true upper bound for ANY in-flight call in the context —
   * one run context is shared by planner/worker/reviewer tiers with different output caps, so using
   * only the current call's model would understate a concurrent larger-output call.
   */
  private long worstCaseOutputTokensPerCall(LlmExecutionContext context) {
    Map<String, List<String>> allowed = context.policySnapshot().allowedProviderModels();
    long worst = 0L;
    for (List<String> models : allowed.values()) {
      for (String model : models) {
        worst = Math.max(worst, Math.max(0L, modelRegistry.maxOutputForModel(model)));
      }
    }
    return worst;
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

  private static long saturatingMul(long left, long right) {
    try {
      return Math.multiplyExact(left, right);
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
