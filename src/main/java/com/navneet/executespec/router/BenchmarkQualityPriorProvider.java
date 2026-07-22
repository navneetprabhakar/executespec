package com.navneet.executespec.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.benchmark.BenchmarkRun;
import com.navneet.executespec.benchmark.BenchmarkRunRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds benchmark-derived quality-prior signals for {@link ModelProviderCandidateService}.
 *
 * <p>Extracted from {@code ModelProviderCandidateService} (which is on the oversized-class
 * allowlist) so the benchmark-evidence logic lives in one focused, testable place. It converts DONE
 * benchmark runs into {@link ModelProviderCapabilitySignal}s: a sample-weighted quality prior for
 * eligible (sufficient-sample, category-compatible) routes, and individual explanatory signals for
 * everything else.
 *
 * <p><b>RR-818 fail-closed:</b> a benchmark row is only usable when its resolved/total counts are
 * internally consistent ({@code 0 <= resolvedTasks <= totalTasks}). A corrupt row (negative
 * resolved, or resolved above total from duplicate/unexpected task names or a bad import) is
 * quarantined before it can contribute — otherwise {@code (resolved * 100) / total} could produce a
 * quality prior above 100% (or negative) that overpowers other evidence and auto-selects an invalid
 * route. Clamping later in the scorer would hide, not diagnose, the corrupt source fact.
 *
 * <p><b>Known limitation (RR-815, not addressed here):</b> the newest-50 DONE runs are read
 * globally, so a burst of runs for one route can evict older-but-still-relevant evidence for quiet
 * routes. Closing that needs per-candidate/per-suite bounded queries or a materialized
 * route-quality snapshot; this extraction keeps the current global read but isolates it for that
 * follow-up.
 */
@Slf4j
public class BenchmarkQualityPriorProvider {

  private final ModelRegistry modelRegistry;
  private final BenchmarkRunRepository benchmarkRunRepository;

  public BenchmarkQualityPriorProvider(
      ModelRegistry modelRegistry, BenchmarkRunRepository benchmarkRunRepository) {
    this.modelRegistry = modelRegistry;
    this.benchmarkRunRepository = benchmarkRunRepository;
  }

  public List<ModelProviderCapabilitySignal> benchmarkSignals(
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
      // Do not let a set of differently-sized benchmark runs distort routing by giving every run
      // the same weight. A 5-task smoke and a 200-task evaluation must contribute in proportion
      // to the evidence they contain. Keep below-threshold/incompatible rows as individual
      // explanatory signals, but combine all eligible rows for a provider/model into one
      // sample-weighted prior. This also prevents the candidate scorer from averaging stale run
      // snapshots as if they were independent observations.
      Map<BenchmarkAggregateKey, List<BenchmarkRun>> eligible = new LinkedHashMap<>();
      List<ModelProviderCapabilitySignal> informational = new ArrayList<>();
      for (BenchmarkRun run : runs) {
        if (run == null || run.getTotalTasks() <= 0) continue;
        // A benchmark row without a provider/model cannot influence any candidate. Ignore it
        // rather than allowing one malformed historical row to fail closed for every route.
        if (!hasRouteIdentity(run)) continue;
        // RR-818: quarantine a row whose resolved/total counts are inconsistent. A corrupt row
        // would otherwise produce a >100% (or negative) quality prior that dominates real evidence.
        if (!hasConsistentCounts(run)) {
          log.warn(
              "Quarantining inconsistent benchmark row suite={} model={} resolved={} total={}"
                  + " (RR-818)",
              run.getSuite(),
              run.getModel(),
              run.getResolvedTasks(),
              run.getTotalTasks());
          continue;
        }
        boolean enoughSample = run.getTotalTasks() >= minSampleTasks;
        boolean compatibleTaskCategory =
            benchmarkSuiteSupportsTaskCategory(run.getSuite(), taskCategory);
        if (enoughSample && compatibleTaskCategory) {
          eligible
              .computeIfAbsent(
                  new BenchmarkAggregateKey(
                      run.getProvider(), canonicalBenchmarkModel(run.getModel()), taskCategory),
                  ignored -> new ArrayList<>())
              .add(run);
        } else {
          informational.add(benchmarkSignal(run, taskCategory, minSampleTasks));
        }
      }
      List<ModelProviderCapabilitySignal> out = new ArrayList<>(informational);
      eligible.values().stream()
          .map(group -> benchmarkAggregateSignal(group, taskCategory, minSampleTasks))
          .forEach(out::add);
      return List.copyOf(out);
    } catch (RuntimeException ex) {
      log.warn("BenchmarkQualityPriorProvider benchmark signal read failed: {}", ex.getMessage());
      return List.of();
    }
  }

  private static boolean hasRouteIdentity(BenchmarkRun run) {
    return run.getProvider() != null
        && !run.getProvider().isBlank()
        && run.getModel() != null
        && !run.getModel().isBlank();
  }

  /** RR-818: {@code 0 <= resolvedTasks <= totalTasks}; anything else is a corrupt source fact. */
  private static boolean hasConsistentCounts(BenchmarkRun run) {
    long resolved = run.getResolvedTasks();
    long total = run.getTotalTasks();
    return resolved >= 0 && resolved <= total;
  }

  private static ModelProviderCapabilitySignal benchmarkAggregateSignal(
      List<BenchmarkRun> runs, String taskCategory, int minSampleTasks) {
    BenchmarkRun first = runs.getFirst();
    String canonicalModel = first.getModel();
    // RR-219 evidence integrity: repeated runs of the SAME corpus are not independent observations.
    // Two evaluations of one (suite, dataset) re-measure the same tasks, so summing their
    // totalTasks/resolvedTasks inflates the sample weight with experiment frequency rather than
    // adding evidence. Collapse each (suite, dataset) to its most recent run before
    // sample-weighting
    // so a favorable (or unfavorable) benchmark that is re-run repeatedly cannot dominate the
    // prior.
    // Runs arrive newest-first (createdAt desc), so the first occurrence per corpus is the newest.
    List<BenchmarkRun> distinctObservations = dedupeRepeatedTrials(runs);
    int repeatedTrialsCollapsed = runs.size() - distinctObservations.size();
    long totalTasks = distinctObservations.stream().mapToLong(BenchmarkRun::getTotalTasks).sum();
    long resolvedTasks =
        distinctObservations.stream().mapToLong(BenchmarkRun::getResolvedTasks).sum();
    double resolvedRate = totalTasks <= 0 ? 0.0 : (resolvedTasks * 100.0) / totalTasks;
    String reason;
    if (distinctObservations.size() == 1) {
      BenchmarkRun only = distinctObservations.getFirst();
      String collapsedNote =
          repeatedTrialsCollapsed > 0
              ? String.format(
                  Locale.ROOT,
                  " (used as quality prior; %d repeated trial(s) of the same corpus collapsed to"
                      + " newest)",
                  repeatedTrialsCollapsed)
              : " (used as quality prior)";
      reason =
          String.format(
              Locale.ROOT,
              "benchmark %s/%s resolved %d/%d tasks%s",
              only.getSuite(),
              only.getDataset(),
              only.getResolvedTasks(),
              only.getTotalTasks(),
              collapsedNote);
    } else {
      String collapsedNote =
          repeatedTrialsCollapsed > 0
              ? String.format(
                  Locale.ROOT,
                  "; %d repeated trial(s) collapsed to newest",
                  repeatedTrialsCollapsed)
              : "";
      reason =
          String.format(
              Locale.ROOT,
              "benchmark aggregate %s/%s resolved %d/%d tasks across %d distinct observation(s)"
                  + " (sample-weighted; minimum %d-task prior%s)",
              first.getProvider(),
              first.getModel(),
              resolvedTasks,
              totalTasks,
              distinctObservations.size(),
              minSampleTasks,
              collapsedNote);
    }
    return ModelProviderCapabilitySignal.builder()
        .provider(first.getProvider())
        .model(canonicalModel)
        .taskCategory(taskCategory)
        .benchmarkQualityScore(resolvedRate)
        .reasons(List.of(reason))
        .build();
  }

  /**
   * Collapse repeated trials of the same benchmark observation to the newest run. The observation
   * key is (suite, dataset): two runs sharing it are re-evaluations of the same task corpus, not
   * independent evidence (RR-219). Distinct datasets under the same suite (e.g. {@code lite-a} and
   * {@code lite-b}) remain separate observations and are legitimately summed. Input is assumed
   * newest-first, so the first occurrence of each key is retained.
   */
  private static List<BenchmarkRun> dedupeRepeatedTrials(List<BenchmarkRun> runs) {
    Map<String, BenchmarkRun> newestPerObservation = new LinkedHashMap<>();
    for (BenchmarkRun run : runs) {
      String key =
          normalizeBenchmarkLabel(run.getSuite()) + "|" + normalizeBenchmarkLabel(run.getDataset());
      newestPerObservation.putIfAbsent(key, run);
    }
    return List.copyOf(newestPerObservation.values());
  }

  private record BenchmarkAggregateKey(String provider, String model, String taskCategory) {}

  /**
   * Canonicalise benchmark route identities before aggregation. Benchmark producers are allowed to
   * emit legacy aliases (for example a tier label rather than the registered model id). If aliases
   * are grouped separately, the later candidate average gives a tiny run the same weight as a large
   * run and can materially distort the quality prior. Normalize once at ingestion while preserving
   * the original run text in reasons.
   */
  private String canonicalBenchmarkModel(String model) {
    return modelRegistry.canonicalize(model);
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

  /**
   * Closed, versioned suite→task-category taxonomy. Only the suites ExecuteSpec actually knows
   * (mirroring {@link com.navneet.executespec.benchmark.BenchmarkSuite}, plus their legacy
   * lowercase/variant aliases) can back a routing quality prior.
   *
   * <p>RR-218 fail-closed: the {@code default} branch previously declared every UNKNOWN suite
   * compatible with the common coding categories, so a newly introduced, misspelled or unrelated
   * benchmark could silently become a production quality prior. An unrecognised suite is now
   * informational only — it stays visible as an explanatory signal but never drives model/provider
   * selection until it is explicitly mapped here.
   */
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
        // RR-218: unknown/unmapped suite -> not a quality prior for any task category.
      default -> false;
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
}
