package com.navneet.executespec.agent;

/**
 * Output-token ceilings for planner calls that must RE-EMIT plan content (coherence patching, task
 * expansion) rather than answer with a short verdict.
 *
 * <h2>Why this exists</h2>
 *
 * These ceilings used to be the constant {@code 4_096}, chosen independently of the model actually
 * serving the call. That is a truncation bug for every verbose model, not one model's problem: dev
 * run f86d1ad1 planned successfully on {@code glm-5.2-fw} (outline emitted 16,589 output tokens),
 * then the coherence pass — which has to restate the patched plan — returned EXACTLY 4,096 tokens,
 * truncating its tool call mid-emission. The protocol repair then ran at the SAME 4,096 ceiling and
 * truncated in exactly the same place, so the retry could only fail identically. The run ended
 * {@code FAILED / PLANNER_UNAVAILABLE} with no plan, while that model's own configured ceiling was
 * 25,344 — we were capping it at 16% of its capability and calling the result unplannable.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>The ceiling is derived from the resolved model's configured {@code max-output-tokens} and
 *       is NEVER allowed to exceed it — a request above a model's real ceiling is rejected by some
 *       transports and silently clamped by others.
 *   <li>{@link #repairAttempt(int)} is always {@code >=} {@link #firstAttempt(int)}. A repair that
 *       retries at the first attempt's ceiling is not a retry: if truncation caused the failure,
 *       the identical ceiling reproduces it.
 *   <li>A ceiling is a bound, not a charge. Raising it costs nothing unless the model genuinely
 *       needs the headroom; it only stops us truncating work we already paid to generate.
 * </ul>
 */
public final class PlanEmissionBudget {

  private PlanEmissionBudget() {}

  /**
   * Headroom for a first plan-emitting attempt when the model allows it. Deliberately below a large
   * model's full ceiling: most plans fit well inside this, and the remaining headroom is held back
   * for {@link #repairAttempt(int)} so a repair is strictly roomier than what already failed.
   */
  static final int PREFERRED_FIRST_ATTEMPT = 16_384;

  /** Used when a model has no configured ceiling, matching {@code ModelRegistry}'s own default. */
  static final int FALLBACK_MODEL_CEILING = 16_000;

  /**
   * Ceiling for the first plan-emitting call on a model whose configured max is {@code modelMax}.
   */
  public static int firstAttempt(int modelMax) {
    return Math.min(effectiveModelCeiling(modelMax), PREFERRED_FIRST_ATTEMPT);
  }

  /**
   * Ceiling for a protocol-repair retry: the model's full headroom, and never less than the first
   * attempt's ceiling.
   */
  public static int repairAttempt(int modelMax) {
    int ceiling = effectiveModelCeiling(modelMax);
    return Math.max(ceiling, firstAttempt(modelMax));
  }

  private static int effectiveModelCeiling(int modelMax) {
    return modelMax > 0 ? modelMax : FALLBACK_MODEL_CEILING;
  }
}
