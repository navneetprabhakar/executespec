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
 *   <li><b>Output tokens are reserved at their real per-call ceiling.</b> The call being authorized
 *       is charged its requested {@code max_tokens}, capped by the largest registered output limit
 *       the policy admits. Earlier in-flight calls are conservatively charged that policy-wide
 *       maximum because their individual request caps are not persisted. This prevents a normal
 *       1.2k-token chat call from being rejected against a 40k turn budget merely because an
 *       unrelated allowed model supports 131k output, while still preventing concurrent calls from
 *       collectively crossing {@code maxOutputTokens}. The in-flight reservation is derived from
 *       {@code reserved_provider_calls} minus recorded terminal rows, so it self-heals as calls
 *       terminalize and needs no release path or extra column. It also tightens the {@code
 *       maxTotalTokens} check by its output half.
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
    reserveProviderCall(snapshot, null);
  }

  /**
   * Reserves this dispatch using its real transport output cap when the caller supplied one.
   * Previously the first call reserved the largest output limit of every model allowed by the org
   * (up to 131k) even when a chat turn requested only 1.2k tokens and had a 40k total budget. That
   * made every fresh web-agent turn fail before transport. Existing concurrent calls remain
   * conservatively charged at the policy-wide maximum because their individual caps are not stored.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void reserveProviderCall(LlmExecutionContext snapshot, Integer requestedMaxOutputTokens) {
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

    // Existing in-flight calls have no persisted per-call cap, so retain the policy-wide maximum
    // for them. The call being authorized is known and can use the smaller transport max_tokens.
    long existingInFlight = Math.max(0L, reserved - recordedCalls);
    long worstCaseOutputPerCall = worstCaseOutputTokensPerCall(context);
    long currentCallOutput =
        requestedMaxOutputTokens != null && requestedMaxOutputTokens > 0
            ? Math.min(requestedMaxOutputTokens.longValue(), worstCaseOutputPerCall)
            : worstCaseOutputPerCall;
    long projectedOutput =
        safeAdd(
            safeAdd(output, saturatingMul(existingInFlight, worstCaseOutputPerCall)),
            currentCallOutput);

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
      // A successful call with unprojectable provider cost is not free. Fail closed before
      // authorizing more spend under a dollar ceiling; failed transport attempts stay eligible
      // for the router's bounded retry because they have no provider-reported usage.
      if (value(usageRepository.countUnknownSuccessfulCostAttemptsByContextId(id)) > 0) {
        throw budget();
      }
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
   * allowed models makes this a true upper bound for an earlier in-flight call whose individual
   * request cap was not persisted. The currently authorized call uses its known requested cap.
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
