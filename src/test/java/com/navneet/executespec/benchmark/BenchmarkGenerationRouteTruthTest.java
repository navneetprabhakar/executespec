package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsage;
import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.engine.CodeGenEngine;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.swebench.SwebenchContainerStore;
import com.navneet.executespec.swebench.SwebenchInstanceLoader;
import com.navneet.executespec.swebench.SwebenchProperties;

import org.junit.jupiter.api.Test;

/**
 * C1 Step 3 — benchmark generation must report the route that physically served the run, not the
 * route it asked for. {@code preds.json} and the campaign manifest are built from this result, so a
 * requested-only route here is how a DeepSeek→GLM comparison silently becomes untrue.
 */
class BenchmarkGenerationRouteTruthTest {

  private final RunStepUsageRepository stepUsage = mock(RunStepUsageRepository.class);

  private BenchmarkGenerationService service() {
    return new BenchmarkGenerationService(
        mock(SwebenchProperties.class),
        mock(SwebenchInstanceLoader.class),
        mock(SwebenchContainerStore.class),
        mock(CodeGenEngine.class),
        mock(PipelineRunRepository.class),
        mock(RunCostTracker.class),
        stepUsage,
        mock(SseBroadcaster.class));
  }

  private static RunStepUsage row(String provider, String model) {
    return RunStepUsage.builder().provider(provider).model(model).attemptStatus("SUCCESS").build();
  }

  @Test
  void effectiveRouteReportsTheLedgerRouteNotTheRequestedOne() {
    when(stepUsage.findByRunIdOrderByCreatedAtAsc("run-1"))
        .thenReturn(List.of(row("openrouter", "glm-5.2")));

    EffectiveRouteProjection route = service().effectiveRoute("run-1");

    assertThat(route.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(route.provider()).isEqualTo("openrouter");
    assertThat(route.model()).isEqualTo("glm-5.2");
    // The requested route was DeepSeek; divergence must be detectable by the caller.
    assertThat(route.divergesFrom("deepseek", "deepseek-v4-pro-native")).isTrue();
  }

  @Test
  void effectiveRouteNeverBorrowsTheRequestedRouteWhenTheLedgerIsEmpty() {
    when(stepUsage.findByRunIdOrderByCreatedAtAsc("run-1")).thenReturn(List.of());

    assertThat(service().effectiveRoute("run-1")).isEqualTo(EffectiveRouteProjection.UNATTRIBUTED);
  }

  @Test
  void generateResultCarriesBothTheRequestedAndTheServedRoute() {
    var result =
        new BenchmarkGenerationService.GenerateResult(
            "run-1",
            "django__django-1",
            "deepseek",
            "deepseek-v4-pro-native",
            "openrouter",
            "glm-5.2",
            "LEDGER",
            true,
            "COMPLETED",
            3,
            5,
            1200,
            0.12,
            4567,
            321,
            "/tmp/submission.patch",
            "/tmp/preds.json",
            null);

    // Requested identity is preserved, not overwritten.
    assertThat(result.provider()).isEqualTo("deepseek");
    assertThat(result.model()).isEqualTo("deepseek-v4-pro-native");
    // Served identity is reported alongside it.
    assertThat(result.effectiveProvider()).isEqualTo("openrouter");
    assertThat(result.effectiveModel()).isEqualTo("glm-5.2");
    assertThat(result.routeAttribution()).isEqualTo("LEDGER");
    assertThat(result.routeDiverged()).isTrue();
  }
}
