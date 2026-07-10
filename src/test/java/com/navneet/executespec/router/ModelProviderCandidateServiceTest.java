package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.benchmark.BenchmarkRun;
import com.navneet.executespec.benchmark.BenchmarkRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ModelProviderCandidateServiceTest {

  private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
  private final ModelPerformanceTracker performanceTracker = mock(ModelPerformanceTracker.class);
  private final BenchmarkRunRepository benchmarkRunRepository = mock(BenchmarkRunRepository.class);
  private final TaskOutcomeMetricService taskOutcomeMetricService =
      mock(TaskOutcomeMetricService.class);
  private ModelProviderCandidateService service;

  @BeforeEach
  void setUp() {
    service =
        new ModelProviderCandidateService(
            modelRegistry, performanceTracker, benchmarkRunRepository, taskOutcomeMetricService);
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(List.of());
    when(taskOutcomeMetricService.qualityPrior(anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(java.util.Optional.empty());
    Map<String, ModelMatrixProperties.ModelConfig> models = new LinkedHashMap<>();
    models.put("claude-sonnet-4-6", model("anthropic", "HIGH", "CAPABLE", 3.0, 15.0, 200_000));
    models.put("gpt-5.4-mini", model("openai", "STANDARD", "UTILITY", 0.2, 0.6, 256_000));
    models.put("gemini-3-flash-preview", model("google", "BASIC", "UTILITY", 0.1, 0.4, 1_048_576));
    when(modelRegistry.allModels()).thenReturn(models);
    models.forEach(
        (name, config) -> {
          when(modelRegistry.canonicalize(name)).thenReturn(name);
          when(modelRegistry.pricingFor(name))
              .thenReturn(
                  new double[] {
                    config.getPricing().getInputPer1m(), config.getPricing().getOutputPer1m()
                  });
          when(modelRegistry.contextLimitFor(name)).thenReturn(config.getContextLimit());
          when(performanceTracker.getScore(config.getProvider(), name))
              .thenReturn(ModelPerformanceTracker.UNKNOWN_SCORE);
          when(performanceTracker.getAvgLatencyMs(config.getProvider(), name)).thenReturn(-1L);
        });
  }

  @Test
  void buildsCandidatesFromYamlPricingContextAndFallbackQuality() {
    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(70.0)
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isEqualTo(86.0);
    assertThat(sonnet.benchmarkQualityPriorAvailable()).isFalse();
    assertThat(sonnet.costPerMillionTokens()).isEqualTo(18.0);
    assertThat(sonnet.meetsQualityThreshold()).isTrue();
    assertThat(sonnet.reasons())
        .anySatisfy(reason -> assertThat(reason).contains("candidate from model-matrix YAML"));
    assertThat(sonnet.reasons())
        .contains(
            "benchmark quality prior unavailable for anthropic/claude-sonnet-4-6; using"
                + " fallback/local/validation signals only");

    ModelProviderScore mini = find(candidates, "openai", "gpt-5.4-mini");
    assertThat(mini.qualityScore()).isEqualTo(76.0);
    assertThat(mini.meetsQualityThreshold()).isTrue();
  }

  @Test
  void evaluatesIndependentCandidatesInBoundedParallelBatchWithDeterministicOrder() {
    ReflectionTestUtils.setField(service, "parallelProviderEvaluationEnabled", true);
    ReflectionTestUtils.setField(service, "maxParallelTasks", 2);

    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(60.0)
                .build());

    assertThat(candidates)
        .extracting(ModelProviderScore::model)
        .containsExactly("claude-sonnet-4-6", "gemini-3-flash-preview", "gpt-5.4-mini");
    assertThat(candidates)
        .allSatisfy(
            score ->
                assertThat(score.reasons())
                    .contains("provider/model candidate evaluated in bounded parallel batch"));
  }

  @Test
  void usesSequentialEvaluationWhenParallelCandidateEvaluationIsDisabled() {
    ReflectionTestUtils.setField(service, "parallelProviderEvaluationEnabled", false);

    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(60.0)
                .build());

    assertThat(candidates)
        .allSatisfy(
            score ->
                assertThat(score.reasons())
                    .contains("provider/model candidate evaluated sequentially"));
  }

  @Test
  void mergesExternalBenchmarkAndLocalPerformanceSignals() {
    when(performanceTracker.getScore("anthropic", "claude-sonnet-4-6")).thenReturn(0.80);
    when(performanceTracker.getAvgLatencyMs("anthropic", "claude-sonnet-4-6")).thenReturn(1_200L);

    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(75.0)
                .externalSignals(
                    List.of(
                        ModelProviderCapabilitySignal.builder()
                            .provider("anthropic")
                            .model("claude-sonnet-4-6")
                            .taskCategory("BUG_FIX")
                            .benchmarkQualityScore(90.0)
                            .validationPassRateScore(70.0)
                            .toolReliabilityScore(95.0)
                            .latencyP50Ms(900)
                            .latencyP95Ms(1_800)
                            .reasons(List.of("terminal-bench first-20"))
                            .build()))
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isCloseTo(84.1667, within(0.0001));
    assertThat(sonnet.benchmarkQualityPriorAvailable()).isTrue();
    assertThat(sonnet.latencyP50Ms()).isEqualTo(900);
    assertThat(sonnet.latencyP95Ms()).isEqualTo(1_800);
    assertThat(sonnet.reasons())
        .anySatisfy(reason -> assertThat(reason).contains("local 24h success score 80.00%"));
    assertThat(sonnet.reasons()).contains("terminal-bench first-20");
  }

  @Test
  void usesPersistedTaskValidationPriorForMatchingCategory() {
    when(taskOutcomeMetricService.qualityPrior("anthropic", "claude-sonnet-4-6", "BUG_FIX", 5))
        .thenReturn(
            java.util.Optional.of(new TaskOutcomeMetricService.QualityPrior(80.0, 10, "BUG_FIX")));

    ModelProviderScore sonnet =
        find(
            service.buildCandidates(
                ModelProviderCandidateRequest.builder()
                    .taskCategory("BUG_FIX")
                    .requiredQualityThreshold(70.0)
                    .build()),
            "anthropic",
            "claude-sonnet-4-6");

    assertThat(sonnet.qualityScore()).isEqualTo(80.0);
    assertThat(sonnet.benchmarkQualityPriorAvailable()).isFalse();
    assertThat(sonnet.reasons())
        .contains("persisted BUG_FIX validation pass rate 80.00% from 10 tasks");
  }

  @Test
  void mergesCompatiblePersistedBenchmarkRunsAsBenchmarkQualitySignals() {
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(
            List.of(
                BenchmarkRun.builder()
                    .id("bench-1")
                    .suite("SWE_BENCH")
                    .dataset("lite")
                    .provider("anthropic")
                    .model("claude-sonnet-4-6")
                    .totalTasks(20)
                    .resolvedTasks(18)
                    .status("DONE")
                    .build()));

    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(70.0)
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isEqualTo(90.0);
    assertThat(sonnet.benchmarkQualityPriorAvailable()).isTrue();
    assertThat(sonnet.reasons())
        .contains("benchmark SWE_BENCH/lite resolved 18/20 tasks (used as quality prior)");
    assertThat(sonnet.reasons())
        .doesNotContain(
            "benchmark quality prior unavailable for anthropic/claude-sonnet-4-6; using"
                + " fallback/local/validation signals only");
  }

  @Test
  void keepsIncompatiblePersistedBenchmarkRunsVisibleButNotAsQualityPriors() {
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(
            List.of(
                BenchmarkRun.builder()
                    .id("bench-terminal")
                    .suite("TERMINAL_BENCH")
                    .dataset("first-20")
                    .provider("anthropic")
                    .model("claude-sonnet-4-6")
                    .totalTasks(20)
                    .resolvedTasks(18)
                    .status("DONE")
                    .build()));

    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(70.0)
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isEqualTo(86.0);
    assertThat(sonnet.benchmarkQualityPriorAvailable()).isFalse();
    assertThat(sonnet.reasons())
        .contains(
            "benchmark TERMINAL_BENCH/first-20 resolved 18/20 tasks (suite TERMINAL_BENCH is not a"
                + " prior for task category BUG_FIX)",
            "benchmark quality prior unavailable for anthropic/claude-sonnet-4-6; using"
                + " fallback/local/validation signals only");
  }

  @Test
  void allowsTerminalBenchQualityPriorForTerminalTaskCategory() {
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(
            List.of(
                BenchmarkRun.builder()
                    .id("bench-terminal")
                    .suite("TERMINAL_BENCH")
                    .dataset("first-20")
                    .provider("anthropic")
                    .model("claude-sonnet-4-6")
                    .totalTasks(20)
                    .resolvedTasks(18)
                    .status("DONE")
                    .build()));

    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("TERMINAL_TASK")
                .requiredQualityThreshold(70.0)
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isEqualTo(90.0);
    assertThat(sonnet.benchmarkQualityPriorAvailable()).isTrue();
    assertThat(sonnet.reasons())
        .contains("benchmark TERMINAL_BENCH/first-20 resolved 18/20 tasks (used as quality prior)");
  }

  @Test
  void ignoresPersistedBenchmarkRunsBelowSampleThresholdAsQualityPriors() {
    when(benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE"))
        .thenReturn(
            List.of(
                BenchmarkRun.builder()
                    .id("bench-tiny")
                    .suite("TERMINAL_BENCH")
                    .dataset("tiny-5")
                    .provider("anthropic")
                    .model("claude-sonnet-4-6")
                    .totalTasks(5)
                    .resolvedTasks(5)
                    .status("DONE")
                    .build()));

    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(70.0)
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isEqualTo(86.0);
    assertThat(sonnet.benchmarkQualityPriorAvailable()).isFalse();
    assertThat(sonnet.reasons())
        .contains(
            "benchmark TERMINAL_BENCH/tiny-5 resolved 5/5 tasks (below 20-task minimum; not used"
                + " as quality prior)",
            "benchmark quality prior unavailable for anthropic/claude-sonnet-4-6; using"
                + " fallback/local/validation signals only");
  }

  @Test
  void filtersByProviderAndModelAllowLists() {
    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("CHAT")
                .requiredQualityThreshold(60.0)
                .providerAllowList(List.of("openai"))
                .modelAllowList(List.of("gpt-5.4-mini"))
                .build());

    assertThat(candidates)
        .singleElement()
        .satisfies(score -> assertThat(score.provider()).isEqualTo("openai"));
  }

  @Test
  void marksUnavailableProviderBelowThresholdWithReason() {
    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(70.0)
                .providerAvailability(Map.of("anthropic", false))
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isZero();
    assertThat(sonnet.meetsQualityThreshold()).isFalse();
    assertThat(sonnet.reasons())
        .contains("provider unavailable; candidate is forced below threshold");
  }

  @Test
  void providerPreferenceOrderFeedsFallbackTieBreakerPenalty() {
    List<ModelProviderScore> candidates =
        service.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(60.0)
                .providerPreferenceOrder(List.of("openai", "anthropic"))
                .build());

    ModelProviderScore sonnet = find(candidates, "anthropic", "claude-sonnet-4-6");
    assertThat(sonnet.qualityScore()).isEqualTo(85.75);
  }

  private static ModelProviderScore find(
      List<ModelProviderScore> candidates, String provider, String model) {
    return candidates.stream()
        .filter(score -> score.provider().equals(provider) && score.model().equals(model))
        .findFirst()
        .orElseThrow();
  }

  private static ModelMatrixProperties.ModelConfig model(
      String provider,
      String qualityClass,
      String chainTier,
      double input,
      double output,
      int contextLimit) {
    ModelMatrixProperties.Pricing pricing = new ModelMatrixProperties.Pricing();
    pricing.setInputPer1m(input);
    pricing.setOutputPer1m(output);
    ModelMatrixProperties.ModelConfig config = new ModelMatrixProperties.ModelConfig();
    config.setProvider(provider);
    config.setQualityClass(qualityClass);
    config.setChainTier(chainTier);
    config.setPricing(pricing);
    config.setContextLimit(contextLimit);
    return config;
  }
}
