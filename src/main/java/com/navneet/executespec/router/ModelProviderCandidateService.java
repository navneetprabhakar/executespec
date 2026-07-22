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
  private volatile BenchmarkQualityPriorProvider benchmarkPriorProvider;

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
    // Benchmark rows are a snapshot for this routing decision. Read them once instead of issuing
    // the same repository query once per model candidate (the matrix commonly contains dozens of
    // models, and candidate evaluation may run in parallel).
    List<ModelProviderCapabilitySignal> externalSignals = externalSignals(req);

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
      return buildSignalsParallel(entries, req, externalSignals);
    }
    return buildSignalsSequential(entries, req, false, externalSignals);
  }

  private boolean shouldBuildSignalsInParallel(
      List<Map.Entry<String, ModelMatrixProperties.ModelConfig>> entries) {
    return parallelProviderEvaluationEnabled && maxParallelTasks() > 1 && entries.size() > 1;
  }

  private List<ModelProviderCapabilitySignal> buildSignalsParallel(
      List<Map.Entry<String, ModelMatrixProperties.ModelConfig>> entries,
      ModelProviderCandidateRequest request,
      List<ModelProviderCapabilitySignal> externalSignals) {
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
                          () ->
                              signalFor(
                                  entry.getKey(),
                                  entry.getValue(),
                                  request,
                                  true,
                                  externalSignals)))
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
      return buildSignalsSequential(entries, request, false, externalSignals);
    } catch (ExecutionException | RuntimeException ex) {
      log.warn(
          "ModelProviderCandidateService parallelProviderEvaluation failed: {}; falling back to"
              + " sequential",
          ex.getMessage());
      return buildSignalsSequential(entries, request, false, externalSignals);
    }
  }

  private List<ModelProviderCapabilitySignal> buildSignalsSequential(
      List<Map.Entry<String, ModelMatrixProperties.ModelConfig>> entries,
      ModelProviderCandidateRequest request,
      boolean parallelEvaluation,
      List<ModelProviderCapabilitySignal> externalSignals) {
    return entries.stream()
        .map(
            entry ->
                signalFor(
                    entry.getKey(), entry.getValue(), request, parallelEvaluation, externalSignals))
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
      boolean parallelEvaluation,
      List<ModelProviderCapabilitySignal> externalSignals) {
    String canonicalModel = modelRegistry.canonicalize(model);
    String provider = providerFor(canonicalModel, config);
    List<ModelProviderCapabilitySignal> external =
        externalSignals.stream()
            .filter(signal -> matches(signal, provider, canonicalModel, request.taskCategory()))
            .toList();

    double[] pricing = modelRegistry.pricingFor(canonicalModel);
    double inputCost = pricing != null ? pricing[0] : configuredInputCost(config);
    double outputCost = pricing != null ? pricing[1] : configuredOutputCost(config);
    double localScore = performanceTracker.getScore(provider, canonicalModel);
    long localLatency = performanceTracker.getAvgLatencyMs(provider, canonicalModel);
    TaskOutcomeMetricService.QualityPrior outcomePrior =
        taskOutcomeMetricService == null ? null : qualityPrior(provider, canonicalModel, request);
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

    long requiredContextTokens = request.requiredContextTokens();
    int contextWindow = modelRegistry.contextLimitFor(canonicalModel);
    boolean contextFits = requiredContextTokens <= 0 || requiredContextTokens <= contextWindow;
    if (!contextFits) {
      reasons.add(
          "required context budget "
              + requiredContextTokens
              + " exceeds model window "
              + contextWindow
              + "; candidate rejected before quality ranking");
    }

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
        .contextFitsRequestedBudget(contextFits)
        .build();
  }

  private List<ModelProviderCapabilitySignal> externalSignals(
      ModelProviderCandidateRequest request) {
    List<ModelProviderCapabilitySignal> signals = new ArrayList<>(request.externalSignals());
    signals.addAll(
        benchmarkPriorProvider()
            .benchmarkSignals(request.taskCategory(), benchmarkMinSampleTasks()));
    return List.copyOf(signals);
  }

  /**
   * Benchmark-evidence machinery was extracted to {@link BenchmarkQualityPriorProvider} (RR-818) so
   * this already-oversized class does not grow. Created lazily — its dependencies are constructor-
   * injected final fields that are not yet assigned during a field initializer.
   */
  private BenchmarkQualityPriorProvider benchmarkPriorProvider() {
    BenchmarkQualityPriorProvider provider = benchmarkPriorProvider;
    if (provider == null) {
      provider = new BenchmarkQualityPriorProvider(modelRegistry, benchmarkRunRepository);
      benchmarkPriorProvider = provider;
    }
    return provider;
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

  private TaskOutcomeMetricService.QualityPrior qualityPrior(
      String provider, String model, ModelProviderCandidateRequest request) {
    if (request.taskComplexity() == null || "UNKNOWN".equalsIgnoreCase(request.taskComplexity())) {
      return taskOutcomeMetricService
          .qualityPrior(provider, model, request.taskCategory(), outcomeMinSampleTasks())
          .orElse(null);
    }
    return taskOutcomeMetricService
        .qualityPrior(
            provider,
            model,
            request.taskCategory(),
            request.taskComplexity(),
            outcomeMinSampleTasks())
        .orElse(null);
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
