package com.navneet.executespec.tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Offline, adapter-only experiment contract for comparing an optional repository explorer with
 * the built-in deterministic search. This class deliberately has no FastContext dependency and is
 * not a Spring bean: a licensed/external explorer can be supplied by an operator-owned harness,
 * never by the production worker classpath.
 *
 * <p>The report carries repository/inventory/query fingerprints from each search result so a
 * paired result is auditable against an exact fixture. The invocation policy is conservative: an
 * optional explorer is only called when explicitly enabled and the baseline is empty, or when a
 * case is marked as an exploratory comparison. Production callers should continue using
 * {@link RepositorySearchService} directly until a measured report justifies graduation.
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
      reports.add(new CaseReport(fixture, base, candidate));
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
      String id, RepositorySearchService.SearchRequest request, Set<String> expectedPaths,
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
      RepositorySearchService.SearchResult explorer) {
    public double baselineRecall() {
      return recall(baseline, fixture.expectedPaths());
    }

    public double explorerRecall() {
      return explorer == null ? Double.NaN : recall(explorer, fixture.expectedPaths());
    }

    public EvidenceCitation baselineEvidence() {
      return EvidenceCitation.from(baseline);
    }

    public EvidenceCitation explorerEvidence() {
      return explorer == null ? null : EvidenceCitation.from(explorer);
    }

    private static double recall(RepositorySearchService.SearchResult result, Set<String> expected) {
      if (expected.isEmpty()) return result.hits().isEmpty() ? 1.0 : 0.0;
      long matched =
          result.hits().stream().map(RepositorySearchService.SearchHit::path).distinct()
              .filter(expected::contains).count();
      return (double) matched / expected.size();
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
      return compared.isEmpty() ? Double.NaN : compared.stream().mapToDouble(CaseReport::explorerRecall).average().orElse(0);
    }

    /** Approximate context cost from returned hit token estimates; this is not provider billing. */
    public int baselineTokenEstimate() {
      return cases.stream().flatMap(c -> c.baseline().hits().stream()).mapToInt(RepositorySearchService.SearchHit::tokenEstimate).sum();
    }

    public int explorerTokenEstimate() {
      return cases.stream().filter(c -> c.explorer() != null).flatMap(c -> c.explorer().hits().stream()).mapToInt(RepositorySearchService.SearchHit::tokenEstimate).sum();
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
      double candidate = compared.stream().mapToDouble(CaseReport::explorerRecall).average().orElse(0);
      return candidate > base ? ADOPT_EXPLORER : KEEP_BASELINE;
    }
  }
}
