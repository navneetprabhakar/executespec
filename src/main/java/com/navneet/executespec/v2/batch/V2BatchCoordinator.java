package com.navneet.executespec.v2.batch;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for V2 batch execution. Called from {@code V2RunOrchestrator} when a plan is approved
 * at {@code executionMode=BATCH} and the {@code harness.v2.batch_enabled} flag is on.
 *
 * <p>Plan 5 P1 scaffolding — actual submission + poll + resume wiring lands in P2 (Anthropic) → P3a
 * (OpenAI) → P3b (Gemini). Today the {@link #submit} method throws so any stray call surfaces
 * loudly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class V2BatchCoordinator {

  public static final String FLAG_KEY = "harness.v2.batch_enabled";

  private final FeatureFlagService featureFlagService;

  @SuppressWarnings("unused") // wired in P2
  private final V2BatchClientRouter router;

  /**
   * @return {@code true} iff the batch path is both flagged on AND has at least one provider client
   *     registered.
   */
  public boolean enabled() {
    return featureFlagService.isEnabled(FLAG_KEY);
  }

  /**
   * Submit a plan for batch execution. Placeholder until P2.
   *
   * @throws UnsupportedOperationException always — batch path not yet wired.
   */
  public V2BatchJob submit(String runId) {
    if (!enabled()) {
      throw new IllegalStateException(
          "V2 batch path is disabled; enable " + FLAG_KEY + " and register a V2BatchClient bean.");
    }
    throw new UnsupportedOperationException(
        "V2 batch submission not yet implemented — see docs/plans/v2-batch-execution-plan.md P2.");
  }
}
