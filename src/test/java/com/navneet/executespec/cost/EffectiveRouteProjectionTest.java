package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the server-side effective-route projection. A multi-route run must stay visibly MIXED: the
 * whole point of C1 is that a campaign cannot silently attribute work to a provider that did not do
 * it.
 */
class EffectiveRouteProjectionTest {

  private static RunStepUsage row(String provider, String model, String status) {
    return RunStepUsage.builder().provider(provider).model(model).attemptStatus(status).build();
  }

  @Test
  void singleSuccessfulRouteIsLedgerAttributed() {
    var projection =
        EffectiveRouteProjection.from(
            List.of(
                row("deepseek", "deepseek-v4-pro-native", "SUCCESS"),
                row("deepseek", "deepseek-v4-pro-native", "SUCCESS")));

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("deepseek");
    assertThat(projection.model()).isEqualTo("deepseek-v4-pro-native");
  }

  @Test
  void multipleSuccessfulRoutesAreMixedAndNeverCollapsedToOneProvider() {
    var projection =
        EffectiveRouteProjection.from(
            List.of(
                row("deepseek", "deepseek-v4-pro-native", "SUCCESS"),
                row("openrouter", "glm-5.2", "SUCCESS")));

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.MIXED);
    assertThat(projection.provider()).isNull();
    assertThat(projection.model()).isNull();
  }

  @Test
  void sameProviderWithDifferentModelsIsAlsoMixed() {
    var projection =
        EffectiveRouteProjection.from(
            List.of(
                row("deepseek", "deepseek-v4-pro-native", "SUCCESS"),
                row("deepseek", "deepseek-v4-flash-native", "SUCCESS")));

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.MIXED);
  }

  @Test
  void failedAttemptsDoNotEstablishARoute() {
    var projection =
        EffectiveRouteProjection.from(
            List.of(
                row("openai", "gpt-5.4", "FAILED"),
                row("deepseek", "deepseek-v4-pro-native", "SUCCESS")));

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("deepseek");
  }

  @Test
  void noSuccessfulAttemptIsUnattributedRatherThanAssumed() {
    assertThat(EffectiveRouteProjection.from(List.of(row("openai", "gpt-5.4", "FAILED"))))
        .isEqualTo(EffectiveRouteProjection.UNATTRIBUTED);
    assertThat(EffectiveRouteProjection.from(List.of()))
        .isEqualTo(EffectiveRouteProjection.UNATTRIBUTED);
    assertThat(EffectiveRouteProjection.from(null))
        .isEqualTo(EffectiveRouteProjection.UNATTRIBUTED);
  }

  @Test
  void googleAliasNormalizesToGeminiSoAnAliasIsNotReportedAsTwoRoutes() {
    var projection =
        EffectiveRouteProjection.from(
            List.of(
                row("google", "gemini-3-flash", "SUCCESS"),
                row("gemini", "gemini-3-flash", "SUCCESS")));

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("gemini");
  }

  @Test
  void divergenceIsReportedAgainstTheRequestedRoute() {
    var served = EffectiveRouteProjection.from(List.of(row("openrouter", "glm-5.2", "SUCCESS")));

    assertThat(served.divergesFrom("deepseek", "deepseek-v4-pro-native")).isTrue();
    assertThat(served.divergesFrom("openrouter", "glm-5.2")).isFalse();
  }

  @Test
  void mixedAlwaysDivergesAndUnattributedNeverClaimsDivergence() {
    var mixed =
        EffectiveRouteProjection.from(
            List.of(row("deepseek", "d1", "SUCCESS"), row("openrouter", "g1", "SUCCESS")));
    assertThat(mixed.divergesFrom("deepseek", "d1")).isTrue();

    assertThat(EffectiveRouteProjection.UNATTRIBUTED.divergesFrom("deepseek", "d1")).isFalse();
  }

  /**
   * RR-1560 — {@code RunStepUsageRecorder} writes the literal {@code "unknown"} when neither the
   * caller nor the provider response named a provider/model. That is an ABSENCE marker. Admitting
   * it produced Attribution.LEDGER for a provider called {@code unknown}, i.e. a sentinel laundered
   * into ledger-grade proof, and made divergence fire against every real requested provider.
   */
  @Test
  void theRecorderUnknownSentinelIsTreatedAsAbsenceNotAsAProvenRoute() {
    var projection = EffectiveRouteProjection.from(List.of(row("unknown", "unknown", "SUCCESS")));

    assertThat(projection.attribution())
        .isEqualTo(EffectiveRouteProjection.Attribution.UNATTRIBUTED);
    assertThat(projection.provider()).isNull();
    assertThat(projection.model()).isNull();
    assertThat(projection.divergesFrom("deepseek", "deepseek-v4-pro-native")).isFalse();
    assertThat(projection.divergesFromRequestedRoute("deepseek", "deepseek-v4-pro-native"))
        .isFalse();
  }

  @Test
  void aSentinelRowDoesNotMakeAnOtherwiseSingleRouteRunLookMixed() {
    var projection =
        EffectiveRouteProjection.from(
            List.of(
                row("anthropic", "claude-sonnet-5", "SUCCESS"),
                row("unknown", "unknown", "SUCCESS")));

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("anthropic");
  }

  @Test
  void aHalfSentinelRowIsAlsoNotAdmitted() {
    // A named provider with an unknown model cannot prove a route either.
    assertThat(
            EffectiveRouteProjection.from(List.of(row("anthropic", "unknown", "SUCCESS")))
                .attribution())
        .isEqualTo(EffectiveRouteProjection.Attribution.UNATTRIBUTED);
    assertThat(
            EffectiveRouteProjection.from(List.of(row("UNKNOWN", "claude-sonnet-5", "SUCCESS")))
                .attribution())
        .isEqualTo(EffectiveRouteProjection.Attribution.UNATTRIBUTED);
  }
}
