package com.navneet.executespec.tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Offline, adapter-only experiment contract for comparing an optional repository explorer with the
 * built-in deterministic search. This class deliberately has no FastContext dependency and is not a
 * Spring bean: a licensed/external explorer can be supplied by an operator-owned harness, never by
 * the production worker classpath.
 *
 * <p>The report carries repository/inventory/query fingerprints from each search result so a paired
 * result is auditable against an exact fixture. The invocation policy is conservative: an optional
 * explorer is only called when explicitly enabled and the baseline is empty, or when a case is
 * marked as an exploratory comparison. Production callers should continue using {@link
 * RepositorySearchService} directly until a measured report justifies graduation.
 */
public final class FastContextExperiment {

  private final RepositorySearchService baseline;

  public FastContextExperiment() {
    this(new RepositorySearchService());
  }

  FastContextExperiment(RepositorySearchService baseline) {
    this.baseline = Objects.requireNonNull(baseline, "baseline");
  }

  /** Runs a paired offline fixture. The explorer is optional and never required for production. */
  public ExperimentReport run(
      Path workspace, List<FixtureCase> cases, Explorer explorer, InvocationPolicy policy) {
    Objects.requireNonNull(workspace, "workspace");
    Objects.requireNonNull(cases, "cases");
    Objects.requireNonNull(policy, "policy");
    List<CaseReport> reports = new ArrayList<>();
    int explorerInvocations = 0;
    for (FixtureCase fixture : cases) {
      RepositorySearchService.SearchResult base = baseline.search(workspace, fixture.request());
      boolean invoke = policy.shouldInvoke(base, fixture);
      RepositorySearchService.SearchResult candidate = null;
      if (invoke && explorer != null) {
        candidate = explorer.search(workspace, fixture.request());
        explorerInvocations++;
      }
      reports.add(
          new CaseReport(
              fixture,
              base,
              candidate,
              InvocationMetrics.estimated(base),
              candidate == null
                  ? null
                  : explorer.metrics(workspace, fixture.request(), candidate)));
    }
    return new ExperimentReport(
        reports,
        explorer == null ? "UNAVAILABLE" : explorer.engine(),
        explorerInvocations,
        Decision.from(reports, explorer != null && explorerInvocations > 0));
  }

  /** A deliberately narrow explorer seam; implementations belong in an external harness. */
  @FunctionalInterface
  public interface Explorer {
    RepositorySearchService.SearchResult search(
        Path workspace, RepositorySearchService.SearchRequest request);

    default String engine() {
      return "optional-explorer";
    }

    /**
     * Supplies deterministic, harness-owned performance estimates for a result. Production callers
     * must not interpret these as provider billing or wall-clock telemetry; an external fixture may
     * override this method to attach measured/synthetic trace values.
     */
    default InvocationMetrics metrics(
        Path workspace,
        RepositorySearchService.SearchRequest request,
        RepositorySearchService.SearchResult result) {
      return InvocationMetrics.estimated(result);
    }
  }

  /** Controls when an optional explorer may be called during a paired run. */
  public enum InvocationPolicy {
    /** Never call the optional explorer; useful for the baseline fixture and control arm. */
    BASELINE_ONLY {
      @Override
      boolean shouldInvoke(RepositorySearchService.SearchResult baseline, FixtureCase fixture) {
        return false;
      }
    },
    /** Call only for explicitly exploratory cases or when deterministic search found nothing. */
    SELECTIVE {
      @Override
      boolean shouldInvoke(RepositorySearchService.SearchResult baseline, FixtureCase fixture) {
        return fixture.exploratory() || baseline.totalMatches() == 0;
      }
    },
    /** Call for every fixture; only permitted by the offline comparison harness. */
    PAIRED {
      @Override
      boolean shouldInvoke(RepositorySearchService.SearchResult baseline, FixtureCase fixture) {
        return true;
      }
    };

    abstract boolean shouldInvoke(
        RepositorySearchService.SearchResult baseline, FixtureCase fixture);
  }

  public record FixtureCase(
      String id,
      RepositorySearchService.SearchRequest request,
      Set<String> expectedPaths,
      boolean exploratory) {
    public FixtureCase {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("fixture id required");
      Objects.requireNonNull(request, "request");
      expectedPaths = Set.copyOf(expectedPaths == null ? Set.of() : expectedPaths);
    }
  }

  public record CaseReport(
      FixtureCase fixture,
      RepositorySearchService.SearchResult baseline,
      RepositorySearchService.SearchResult explorer,
      InvocationMetrics baselineMetrics,
      InvocationMetrics explorerMetrics) {
    /** Backward-compatible constructor for callers that only provide search results. */
    public CaseReport(
        FixtureCase fixture,
        RepositorySearchService.SearchResult baseline,
        RepositorySearchService.SearchResult explorer) {
      this(
          fixture,
          baseline,
          explorer,
          InvocationMetrics.estimated(baseline),
          explorer == null ? null : InvocationMetrics.estimated(explorer));
    }

    public double baselineRecall() {
      return recall(baseline, fixture.expectedPaths());
    }

    public double explorerRecall() {
      return explorer == null ? Double.NaN : recall(explorer, fixture.expectedPaths());
    }

    public double baselinePrecision() {
      return precision(baseline, fixture.expectedPaths());
    }

    public double explorerPrecision() {
      return explorer == null ? Double.NaN : precision(explorer, fixture.expectedPaths());
    }

    public EvidenceCitation baselineEvidence() {
      return EvidenceCitation.from(baseline);
    }

    public EvidenceCitation explorerEvidence() {
      return explorer == null ? null : EvidenceCitation.from(explorer);
    }

    private static double recall(
        RepositorySearchService.SearchResult result, Set<String> expected) {
      if (expected.isEmpty()) return result.hits().isEmpty() ? 1.0 : 0.0;
      long matched =
          result.hits().stream()
              .map(RepositorySearchService.SearchHit::path)
              .distinct()
              .filter(expected::contains)
              .count();
      return (double) matched / expected.size();
    }

    private static double precision(
        RepositorySearchService.SearchResult result, Set<String> expected) {
      if (result.hits().isEmpty()) return expected.isEmpty() ? 1.0 : 0.0;
      long relevant =
          result.hits().stream()
              .map(RepositorySearchService.SearchHit::path)
              .distinct()
              .filter(expected::contains)
              .count();
      long distinct =
          result.hits().stream().map(RepositorySearchService.SearchHit::path).distinct().count();
      return distinct == 0 ? 0.0 : (double) relevant / distinct;
    }
  }

  public record EvidenceCitation(
      String repositorySha, String inventoryFingerprint, String queryFingerprint, String engine) {
    static EvidenceCitation from(RepositorySearchService.SearchResult result) {
      return new EvidenceCitation(
          result.repositorySha(),
          result.inventoryFingerprint(),
          result.queryFingerprint(),
          result.engine());
    }
  }

  public record ExperimentReport(
      List<CaseReport> cases, String explorerEngine, int explorerInvocations, Decision decision) {
    public ExperimentReport {
      cases = List.copyOf(cases);
    }

    public int baselineMatches() {
      return cases.stream().mapToInt(c -> c.baseline().totalMatches()).sum();
    }

    public int explorerMatches() {
      return cases.stream()
          .map(CaseReport::explorer)
          .filter(Objects::nonNull)
          .mapToInt(RepositorySearchService.SearchResult::totalMatches)
          .sum();
    }

    public double baselineMeanRecall() {
      return mean(c -> c.baselineRecall());
    }

    public double explorerMeanRecall() {
      List<CaseReport> compared = cases.stream().filter(c -> c.explorer() != null).toList();
      return compared.isEmpty()
          ? Double.NaN
          : compared.stream().mapToDouble(CaseReport::explorerRecall).average().orElse(0);
    }

    /** Approximate context cost from returned hit token estimates; this is not provider billing. */
    public int baselineTokenEstimate() {
      return cases.stream().mapToInt(c -> c.baselineMetrics().contextTokens()).sum();
    }

    public int explorerTokenEstimate() {
      return cases.stream()
          .map(CaseReport::explorerMetrics)
          .filter(Objects::nonNull)
          .mapToInt(InvocationMetrics::contextTokens)
          .sum();
    }

    public double baselineMeanPrecision() {
      return mean(c -> c.baselinePrecision());
    }

    public double explorerMeanPrecision() {
      List<CaseReport> compared = cases.stream().filter(c -> c.explorer() != null).toList();
      return compared.isEmpty()
          ? Double.NaN
          : compared.stream().mapToDouble(CaseReport::explorerPrecision).average().orElse(0);
    }

    public long baselineLatencyMicros() {
      return cases.stream().mapToLong(c -> c.baselineMetrics().latencyMicros()).sum();
    }

    public long explorerLatencyMicros() {
      return cases.stream()
          .map(CaseReport::explorerMetrics)
          .filter(Objects::nonNull)
          .mapToLong(InvocationMetrics::latencyMicros)
          .sum();
    }

    public double baselineCostUsd() {
      return cases.stream().mapToDouble(c -> c.baselineMetrics().costUsd()).sum();
    }

    public double explorerCostUsd() {
      return cases.stream()
          .map(CaseReport::explorerMetrics)
          .filter(Objects::nonNull)
          .mapToDouble(InvocationMetrics::costUsd)
          .sum();
    }

    private double mean(Function<CaseReport, Double> metric) {
      return cases.stream().mapToDouble(c -> metric.apply(c)).average().orElse(0);
    }
  }

  public enum Decision {
    BASELINE_ONLY,
    INSUFFICIENT_EVIDENCE,
    KEEP_BASELINE,
    ADOPT_EXPLORER;

    static Decision from(List<CaseReport> cases, boolean explorerRan) {
      if (!explorerRan) return BASELINE_ONLY;
      List<CaseReport> compared = cases.stream().filter(c -> c.explorer() != null).toList();
      if (compared.isEmpty()) return INSUFFICIENT_EVIDENCE;
      double base = compared.stream().mapToDouble(CaseReport::baselineRecall).average().orElse(0);
      double candidate =
          compared.stream().mapToDouble(CaseReport::explorerRecall).average().orElse(0);
      double basePrecision =
          compared.stream().mapToDouble(CaseReport::baselinePrecision).average().orElse(0);
      double candidatePrecision =
          compared.stream().mapToDouble(CaseReport::explorerPrecision).average().orElse(0);
      long baseLatency =
          compared.stream().mapToLong(c -> c.baselineMetrics().latencyMicros()).sum();
      long candidateLatency =
          compared.stream().mapToLong(c -> c.explorerMetrics().latencyMicros()).sum();
      double baseCost = compared.stream().mapToDouble(c -> c.baselineMetrics().costUsd()).sum();
      double candidateCost =
          compared.stream().mapToDouble(c -> c.explorerMetrics().costUsd()).sum();
      // Adoption is intentionally conservative: quality must improve without a precision,
      // latency, or cost regression on the same paired cases.
      return candidate > base
              && candidatePrecision >= basePrecision
              && candidateLatency <= baseLatency
              && candidateCost <= baseCost
          ? ADOPT_EXPLORER
          : KEEP_BASELINE;
    }
  }

  /** Synthetic/measured trace values owned by the evaluation harness, never provider billing. */
  public record InvocationMetrics(long latencyMicros, int contextTokens, double costUsd) {
    public InvocationMetrics {
      if (latencyMicros < 0) throw new IllegalArgumentException("latencyMicros must be >= 0");
      if (contextTokens < 0) throw new IllegalArgumentException("contextTokens must be >= 0");
      if (!Double.isFinite(costUsd) || costUsd < 0)
        throw new IllegalArgumentException("costUsd must be finite and >= 0");
    }

    static InvocationMetrics estimated(RepositorySearchService.SearchResult result) {
      int tokens =
          result.hits().stream().mapToInt(RepositorySearchService.SearchHit::tokenEstimate).sum();
      // Stable fixture estimate: deliberately not wall-clock or provider pricing telemetry.
      return new InvocationMetrics(
          100L + result.totalMatches() * 10L + tokens * 2L, tokens, tokens * 0.00000015d);
    }
  }
}
