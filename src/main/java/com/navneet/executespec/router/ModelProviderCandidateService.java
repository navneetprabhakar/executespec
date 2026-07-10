package com.navneet.executespec.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.navneet.executespec.benchmark.BenchmarkRun;
import com.navneet.executespec.benchmark.BenchmarkRunRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds explainable provider/model candidates from current runtime sources.
 *
 * <p>The service bridges the config and telemetry world into the pure scoring/ranking seams. It
 * does not switch providers, mutate a run, or decide a winner.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ModelProviderCandidateService {

  private static final long UNKNOWN_LATENCY_MS = 0L;

  private final ModelRegistry modelRegistry;
  private final ModelPerformanceTracker performanceTracker;
  private final BenchmarkRunRepository benchmarkRunRepository;
  private final TaskOutcomeMetricService taskOutcomeMetricService;
  private final ModelProviderCapabilityRegistry capabilityRegistry =
      new ModelProviderCapabilityRegistry();

  @Value("${execution.continuation.parallel-provider-evaluation-enabled:true}")
  private boolean parallelProviderEvaluationEnabled = true;

  @Value("${execution.continuation.max-parallel-tasks:3}")
  private int maxParallelTasks = 3;

  @Value("${execution.continuation.benchmark-min-sample-tasks:20}")
  private int benchmarkMinSampleTasks = 20;

  @Value("${execution.continuation.outcome-min-sample-tasks:5}")
  private int outcomeMinSampleTasks = 5;

  public List<ModelProviderScore> buildCandidates(ModelProviderCandidateRequest request) {
    return capabilityRegistry.scoreAll(buildSignals(request));
  }

  public List<ModelProviderCapabilitySignal> buildSignals(ModelProviderCandidateRequest request) {
    ModelProviderCandidateRequest req =
        request == null ? ModelProviderCandidateRequest.builder().build() : request;
    Set<String> allowedProviders = normalizeSet(req.providerAllowList());
    Set<String> allowedModels = normalizeSet(req.modelAllowList());

    List<Map.Entry<String, ModelMatrixProperties.ModelConfig>> entries =
        modelRegistry.allModels().entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .sorted(Map.Entry.comparingByKey())
            .filter(
                entry -> {
                  String provider = providerFor(entry.getKey(), entry.getValue());
                  return provider != null
                      && (allowedProviders.isEmpty()
                          || allowedProviders.contains(normalize(provider)))
                      && (allowedModels.isEmpty()
                          || allowedModels.contains(normalize(entry.getKey()))
                          || allowedModels.contains(
                              normalize(modelRegistry.canonicalize(entry.getKey()))));
                })
            .toList();

    if (shouldBuildSignalsInParallel(entries)) {
      return buildSignalsParallel(entries, req);
    }
    return buildSignalsSequential(entries, req, false);
  }

  private boolean shouldBuildSignalsInParallel(
      List<Map.Entry<String, ModelMatrixProperties.ModelConfig>> entries) {
    return parallelProviderEvaluationEnabled && maxParallelTasks() > 1 && entries.size() > 1;
  }

  private List<ModelProviderCapabilitySignal> buildSignalsParallel(
      List<Map.Entry<String, ModelMatrixProperties.ModelConfig>> entries,
      ModelProviderCandidateRequest request) {
    int parallelism = Math.min(entries.size(), maxParallelTasks());
    List<String> labels = entries.stream().map(this::candidateLabel).toList();
    log.info(
        "ModelProviderCandidateService parallelProviderEvaluation START count={} parallelism={}"
            + " labels={}",
        entries.size(),
        parallelism,
        labels);

    try (ExecutorService executor = Executors.newFixedThreadPool(parallelism)) {
      List<Future<ModelProviderCapabilitySignal>> futures =
          entries.stream()
              .map(
                  entry ->
                      executor.submit(
                          () -> signalFor(entry.getKey(), entry.getValue(), request, true)))
              .toList();
      List<ModelProviderCapabilitySignal> out = new ArrayList<>(futures.size());
      for (Future<ModelProviderCapabilitySignal> future : futures) {
        out.add(future.get());
      }
      List<String> results = labels.stream().map(label -> label + "=ok").toList();
      log.info(
          "ModelProviderCandidateService parallelProviderEvaluation DONE count={} results={}",
          out.size(),
          results);
      return List.copyOf(out);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn(
          "ModelProviderCandidateService parallelProviderEvaluation interrupted; falling back to"
              + " sequential");
      return buildSignalsSequential(entries, request, false);
    } catch (ExecutionException | RuntimeException ex) {
      log.warn(
          "ModelProviderCandidateService parallelProviderEvaluation failed: {}; falling back to"
              + " sequential",
          ex.getMessage());
      return buildSignalsSequential(entries, request, false);
    }
  }

  private List<ModelProviderCapabilitySignal> buildSignalsSequential(
      List<Map.Entry<String, ModelMatrixProperties.ModelConfig>> entries,
      ModelProviderCandidateRequest request,
      boolean parallelEvaluation) {
    return entries.stream()
        .map(entry -> signalFor(entry.getKey(), entry.getValue(), request, parallelEvaluation))
        .toList();
  }

  private String candidateLabel(Map.Entry<String, ModelMatrixProperties.ModelConfig> entry) {
    return providerFor(entry.getKey(), entry.getValue())
        + "/"
        + modelRegistry.canonicalize(entry.getKey());
  }

  private int maxParallelTasks() {
    return Math.max(1, maxParallelTasks);
  }

  private ModelProviderCapabilitySignal signalFor(
      String model,
      ModelMatrixProperties.ModelConfig config,
      ModelProviderCandidateRequest request,
      boolean parallelEvaluation) {
    String canonicalModel = modelRegistry.canonicalize(model);
    String provider = providerFor(canonicalModel, config);
    List<ModelProviderCapabilitySignal> external =
        externalSignals(request).stream()
            .filter(signal -> matches(signal, provider, canonicalModel, request.taskCategory()))
            .toList();

    double[] pricing = modelRegistry.pricingFor(canonicalModel);
    double inputCost = pricing != null ? pricing[0] : configuredInputCost(config);
    double outputCost = pricing != null ? pricing[1] : configuredOutputCost(config);
    double localScore = performanceTracker.getScore(provider, canonicalModel);
    long localLatency = performanceTracker.getAvgLatencyMs(provider, canonicalModel);
    TaskOutcomeMetricService.QualityPrior outcomePrior =
        taskOutcomeMetricService == null
            ? null
            : taskOutcomeMetricService
                .qualityPrior(
                    provider, canonicalModel, request.taskCategory(), outcomeMinSampleTasks())
                .orElse(null);
    Boolean providerAvailable = providerAvailable(request, provider, canonicalModel, external);
    double recentFailurePenalty =
        external.stream().mapToDouble(ModelProviderCapabilitySignal::recentFailurePenalty).sum();

    List<String> reasons = new ArrayList<>();
    reasons.add("candidate from model-matrix YAML: " + provider + "/" + canonicalModel);
    reasons.add(
        parallelEvaluation
            ? "provider/model candidate evaluated in bounded parallel batch"
            : "provider/model candidate evaluated sequentially");
    if (localScore == ModelPerformanceTracker.UNKNOWN_SCORE) {
      reasons.add("local 24h performance score unavailable or below sample threshold");
    } else {
      reasons.add(String.format(Locale.ROOT, "local 24h success score %.2f%%", localScore * 100.0));
    }
    if (outcomePrior == null) {
      reasons.add("persisted task outcome prior unavailable or below sample threshold");
    } else {
      reasons.add(
          String.format(
              Locale.ROOT,
              "persisted %s validation pass rate %.2f%% from %d tasks",
              outcomePrior.taskCategory(),
              outcomePrior.validationPassRate(),
              outcomePrior.sampleSize()));
    }
    if (!external.isEmpty()) {
      reasons.add("merged " + external.size() + " external benchmark/validation signal(s)");
      external.stream().flatMap(signal -> signal.reasons().stream()).forEach(reasons::add);
    }
    if (external.stream().noneMatch(signal -> signal.benchmarkQualityScore() != null)) {
      reasons.add(
          "benchmark quality prior unavailable for "
              + provider
              + "/"
              + canonicalModel
              + "; using fallback/local/validation signals only");
    }

    long latencyP50 =
        externalLong(external, ModelProviderCapabilitySignal::latencyP50Ms).stream()
            .mapToLong(Math::round)
            .findFirst()
            .orElse(localLatency > 0 ? localLatency : UNKNOWN_LATENCY_MS);
    long latencyP95 =
        externalLong(external, ModelProviderCapabilitySignal::latencyP95Ms).stream()
            .mapToLong(Math::round)
            .findFirst()
            .orElse(localLatency > 0 ? localLatency * 2 : UNKNOWN_LATENCY_MS);

    return ModelProviderCapabilitySignal.builder()
        .provider(provider)
        .model(canonicalModel)
        .taskCategory(request.taskCategory())
        .benchmarkQualityScore(avg(external, ModelProviderCapabilitySignal::benchmarkQualityScore))
        .localSuccessRateScore(
            localScore == ModelPerformanceTracker.UNKNOWN_SCORE ? null : localScore * 100.0)
        .validationPassRateScore(
            averageWithPersistedOutcome(
                avg(external, ModelProviderCapabilitySignal::validationPassRateScore),
                outcomePrior))
        .codeReviewScore(avg(external, ModelProviderCapabilitySignal::codeReviewScore))
        .toolReliabilityScore(avg(external, ModelProviderCapabilitySignal::toolReliabilityScore))
        .recentFailurePenalty(recentFailurePenalty)
        .inputCostPerMillionTokens(inputCost)
        .outputCostPerMillionTokens(outputCost)
        .latencyP50Ms(latencyP50)
        .latencyP95Ms(Math.max(latencyP50, latencyP95))
        .contextWindowTokens(modelRegistry.contextLimitFor(canonicalModel))
        .providerAvailable(providerAvailable)
        .fallbackOrder(providerPreferenceOrder(request.providerPreferenceOrder(), provider))
        .requiredQualityThreshold(request.requiredQualityThreshold())
        .fallbackQualityScore(fallbackQualityScore(config))
        .reasons(reasons)
        .build();
  }

  private List<ModelProviderCapabilitySignal> externalSignals(
      ModelProviderCandidateRequest request) {
    List<ModelProviderCapabilitySignal> signals = new ArrayList<>(request.externalSignals());
    signals.addAll(benchmarkSignals(request.taskCategory(), benchmarkMinSampleTasks()));
    return List.copyOf(signals);
  }

  private int benchmarkMinSampleTasks() {
    return Math.max(1, benchmarkMinSampleTasks);
  }

  private int outcomeMinSampleTasks() {
    return Math.max(1, outcomeMinSampleTasks);
  }

  private static Double averageWithPersistedOutcome(
      Double externalValidation, TaskOutcomeMetricService.QualityPrior outcomePrior) {
    if (outcomePrior == null) return externalValidation;
    if (externalValidation == null) return outcomePrior.validationPassRate();
    return (externalValidation + outcomePrior.validationPassRate()) / 2.0;
  }

  private List<ModelProviderCapabilitySignal> benchmarkSignals(
      String taskCategory, int minSampleTasks) {
    if (benchmarkRunRepository == null) {
      return List.of();
    }
    try {
      List<BenchmarkRun> runs =
          benchmarkRunRepository.findTop50ByStatusOrderByCreatedAtDesc("DONE");
      if (runs == null || runs.isEmpty()) {
        return List.of();
      }
      return runs.stream()
          .filter(run -> run != null && run.getTotalTasks() > 0)
          .map(run -> benchmarkSignal(run, taskCategory, minSampleTasks))
          .toList();
    } catch (RuntimeException ex) {
      log.warn("ModelProviderCandidateService benchmark signal read failed: {}", ex.getMessage());
      return List.of();
    }
  }

  private static ModelProviderCapabilitySignal benchmarkSignal(
      BenchmarkRun run, String taskCategory, int minSampleTasks) {
    double resolvedRate = (run.getResolvedTasks() * 100.0) / run.getTotalTasks();
    boolean enoughSample = run.getTotalTasks() >= minSampleTasks;
    boolean compatibleTaskCategory =
        benchmarkSuiteSupportsTaskCategory(run.getSuite(), taskCategory);
    return ModelProviderCapabilitySignal.builder()
        .provider(run.getProvider())
        .model(run.getModel())
        .taskCategory(taskCategory)
        .benchmarkQualityScore(enoughSample && compatibleTaskCategory ? resolvedRate : null)
        .reasons(
            List.of(
                String.format(
                    Locale.ROOT,
                    "benchmark %s/%s resolved %d/%d tasks%s",
                    run.getSuite(),
                    run.getDataset(),
                    run.getResolvedTasks(),
                    run.getTotalTasks(),
                    benchmarkPriorSuffix(
                        enoughSample,
                        compatibleTaskCategory,
                        minSampleTasks,
                        run.getSuite(),
                        taskCategory))))
        .build();
  }

  private static String benchmarkPriorSuffix(
      boolean enoughSample,
      boolean compatibleTaskCategory,
      int minSampleTasks,
      String suite,
      String taskCategory) {
    if (!enoughSample) {
      return " (below " + minSampleTasks + "-task minimum; not used as quality prior)";
    }
    if (!compatibleTaskCategory) {
      return " (suite "
          + safeLabel(suite)
          + " is not a prior for task category "
          + safeLabel(taskCategory)
          + ")";
    }
    return " (used as quality prior)";
  }

  private static boolean benchmarkSuiteSupportsTaskCategory(String suite, String taskCategory) {
    String normalizedSuite = normalizeBenchmarkLabel(suite);
    String normalizedCategory = normalizeBenchmarkLabel(taskCategory);
    if (normalizedSuite.isEmpty() || normalizedCategory.isEmpty()) {
      return false;
    }
    return switch (normalizedSuite) {
      case "terminal_bench", "terminalbench" ->
          normalizedCategory.equals("terminal_task")
              || normalizedCategory.equals("benchmark_patch");
      case "swe_bench", "swebench", "swe_bench_lite", "swe_bench_verified" ->
          normalizedCategory.equals("bug_fix")
              || normalizedCategory.equals("test_fix")
              || normalizedCategory.equals("project_patch")
              || normalizedCategory.equals("benchmark_patch");
      case "program_bench", "programbench" ->
          normalizedCategory.equals("bug_fix")
              || normalizedCategory.equals("test_fix")
              || normalizedCategory.equals("benchmark_patch");
      default ->
          normalizedCategory.equals("benchmark_patch")
              || normalizedCategory.equals("terminal_task")
              || normalizedCategory.equals("bug_fix")
              || normalizedCategory.equals("test_fix")
              || normalizedCategory.equals("project_patch");
    };
  }

  private static String normalizeBenchmarkLabel(String value) {
    return value == null
        ? ""
        : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
  }

  private static String safeLabel(String value) {
    return value == null || value.isBlank() ? "UNKNOWN" : value;
  }

  private static String providerFor(String model, ModelMatrixProperties.ModelConfig config) {
    String provider = config.getProvider();
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider missing for model " + model);
    }
    return provider;
  }

  private boolean matches(
      ModelProviderCapabilitySignal signal,
      String provider,
      String canonicalModel,
      String taskCategory) {
    if (signal == null) return false;
    boolean providerMatches = normalize(provider).equals(normalize(signal.provider()));
    boolean modelMatches =
        normalize(canonicalModel).equals(normalize(modelRegistry.canonicalize(signal.model())));
    boolean taskMatches =
        normalize(taskCategory).equals("unknown")
            || normalize(signal.taskCategory()).equals("unknown")
            || normalize(taskCategory).equals(normalize(signal.taskCategory()));
    return providerMatches && modelMatches && taskMatches;
  }

  private static Boolean providerAvailable(
      ModelProviderCandidateRequest request,
      String provider,
      String model,
      List<ModelProviderCapabilitySignal> external) {
    Boolean explicitModel =
        lookupAvailability(request.providerAvailability(), provider + "/" + model);
    if (explicitModel != null) return explicitModel;
    Boolean explicitProvider = lookupAvailability(request.providerAvailability(), provider);
    if (explicitProvider != null) return explicitProvider;
    return external.stream()
        .filter(signal -> !signal.providerAvailable())
        .findFirst()
        .map(ModelProviderCapabilitySignal::providerAvailable)
        .orElse(Boolean.TRUE);
  }

  private static Boolean lookupAvailability(Map<String, Boolean> availability, String key) {
    if (availability == null || availability.isEmpty()) return null;
    Boolean exact = availability.get(key);
    if (exact != null) return exact;
    return availability.entrySet().stream()
        .filter(entry -> normalize(entry.getKey()).equals(normalize(key)))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static int providerPreferenceOrder(
      List<String> providerPreferenceOrder, String provider) {
    if (providerPreferenceOrder == null || providerPreferenceOrder.isEmpty()) return 0;
    for (int i = 0; i < providerPreferenceOrder.size(); i++) {
      if (normalize(providerPreferenceOrder.get(i)).equals(normalize(provider))) return i;
    }
    return providerPreferenceOrder.size();
  }

  private static double configuredInputCost(ModelMatrixProperties.ModelConfig config) {
    return config.getPricing() == null ? 0.0 : config.getPricing().getInputPer1m();
  }

  private static double configuredOutputCost(ModelMatrixProperties.ModelConfig config) {
    return config.getPricing() == null ? 0.0 : config.getPricing().getOutputPer1m();
  }

  private static Double fallbackQualityScore(ModelMatrixProperties.ModelConfig config) {
    String quality =
        firstNonBlank(config.getQualityClass(), config.getChainTier(), config.getCostClass());
    return switch (quality.toUpperCase(Locale.ROOT)) {
      case "PREMIUM", "ARCHITECT", "FRONTIER" -> 92.0;
      case "HIGH" -> 86.0;
      case "CAPABLE" -> 82.0;
      case "STANDARD", "BALANCED" -> 76.0;
      case "BASIC", "UTILITY" -> 68.0;
      case "CHEAP" -> 64.0;
      default -> 70.0;
    };
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return "UNKNOWN";
  }

  private static Set<String> normalizeSet(List<String> values) {
    return values == null
        ? Set.of()
        : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(ModelProviderCandidateService::normalize)
            .collect(Collectors.toUnmodifiableSet());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static Double avg(
      List<ModelProviderCapabilitySignal> signals,
      java.util.function.Function<ModelProviderCapabilitySignal, Double> mapper) {
    OptionalDouble average =
        signals.stream()
            .map(mapper)
            .filter(value -> value != null)
            .mapToDouble(Double::doubleValue)
            .average();
    return average.isPresent() ? average.getAsDouble() : null;
  }

  private static OptionalDouble externalLong(
      List<ModelProviderCapabilitySignal> signals,
      java.util.function.ToLongFunction<ModelProviderCapabilitySignal> mapper) {
    return signals.stream().mapToLong(mapper).filter(value -> value > 0).average();
  }
}
