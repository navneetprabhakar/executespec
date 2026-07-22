package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.benchmark.BenchmarkRun;
import com.navneet.executespec.benchmark.BenchmarkRunRepository;

import org.junit.jupiter.api.Test;

/** RR-818 (2026-07-22) — corrupt benchmark totals must never produce a quality prior above 100%. */
class BenchmarkQualityPriorProviderTest {

  private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
  private final BenchmarkRunRepository benchmarkRunRepository = mock(BenchmarkRunRepository.class);
  private final BenchmarkQualityPriorProvider provider =
      new BenchmarkQualityPriorProvider(modelRegistry, benchmarkRunRepository);

  private static BenchmarkRun run(
      String suite, String provider, String model, int resolved, int total) {
    BenchmarkRun r = new BenchmarkRun();
    r.setSuite(suite);
    r.setDataset("lite");
    r.setProvider(provider);
    r.setModel(model);
    r.setResolvedTasks(resolved);
    r.setTotalTasks(total);
    r.setStatus("DONE");
    return r;
  }

  @Test
  void quarantinesRowWhereResolvedExceedsTotal_soPriorNeverExceeds100() {
    when(modelRegistry.canonicalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
    // 30 resolved out of 20 total (duplicate/unexpected task names) => would be 150% if used.
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(List.of(run("swe_bench", "openai", "gpt-5.4", 30, 20)));

    List<ModelProviderCapabilitySignal> signals = provider.benchmarkSignals("BUG_FIX", 5);

    // The corrupt row contributes no benchmark quality prior at all.
    assertThat(signals).noneMatch(s -> s.benchmarkQualityScore() != null);
  }

  @Test
  void quarantinesNegativeResolved() {
    when(modelRegistry.canonicalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(List.of(run("swe_bench", "openai", "gpt-5.4", -3, 20)));

    List<ModelProviderCapabilitySignal> signals = provider.benchmarkSignals("BUG_FIX", 5);

    assertThat(signals).noneMatch(s -> s.benchmarkQualityScore() != null);
  }

  @Test
  void consistentEligibleRowProducesBoundedPrior() {
    when(modelRegistry.canonicalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(List.of(run("swe_bench", "openai", "gpt-5.4", 15, 20)));

    List<ModelProviderCapabilitySignal> signals = provider.benchmarkSignals("BUG_FIX", 5);

    ModelProviderCapabilitySignal prior =
        signals.stream().filter(s -> s.benchmarkQualityScore() != null).findFirst().orElseThrow();
    assertThat(prior.benchmarkQualityScore()).isEqualTo(75.0);
    assertThat(prior.benchmarkQualityScore()).isLessThanOrEqualTo(100.0);
  }
}
