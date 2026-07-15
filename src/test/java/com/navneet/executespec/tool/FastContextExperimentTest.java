package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FastContextExperimentTest {
  @TempDir Path root;

  @Test
  void selectivePolicyOnlyCallsExplorerForAmbiguousFixturesAndKeepsShaCitations() throws Exception {
    Files.writeString(root.resolve("src.java"), "needle\n");
    var request =
        new RepositorySearchService.SearchRequest(
            "needle", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);
    var fixtures =
        List.of(
            new FastContextExperiment.FixtureCase("known", request, Set.of("src.java"), false),
            new FastContextExperiment.FixtureCase(
                "exploratory", request, Set.of("src.java"), true));
    AtomicInteger calls = new AtomicInteger();

    var report =
        new FastContextExperiment()
            .run(
                root,
                fixtures,
                (workspace, ignored) -> {
                  calls.incrementAndGet();
                  return new RepositorySearchService().search(workspace, request);
                },
                FastContextExperiment.InvocationPolicy.SELECTIVE);

    assertThat(calls).hasValue(1);
    assertThat(report.explorerInvocations()).isEqualTo(1);
    assertThat(report.decision()).isEqualTo(FastContextExperiment.Decision.KEEP_BASELINE);
    assertThat(report.baselineMeanRecall()).isEqualTo(1.0);
    assertThat(report.explorerMeanRecall()).isEqualTo(1.0);
    var evidence = report.cases().getFirst().baselineEvidence();
    assertThat(evidence.repositorySha()).isNotBlank();
    assertThat(evidence.inventoryFingerprint()).hasSize(64);
    assertThat(evidence.queryFingerprint()).hasSize(64);
  }

  @Test
  void controlArmNeverInvokesOptionalExplorerAndReportsBaselineOnly() throws Exception {
    Files.writeString(root.resolve("src.java"), "needle\n");
    var request =
        new RepositorySearchService.SearchRequest(
            "needle", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);
    AtomicInteger calls = new AtomicInteger();

    var report =
        new FastContextExperiment()
            .run(
                root,
                List.of(
                    new FastContextExperiment.FixtureCase(
                        "control", request, Set.of("src.java"), false)),
                (workspace, ignored) -> {
                  calls.incrementAndGet();
                  return new RepositorySearchService().search(workspace, request);
                },
                FastContextExperiment.InvocationPolicy.BASELINE_ONLY);

    assertThat(calls).hasValue(0);
    assertThat(report.explorerInvocations()).isZero();
    assertThat(report.decision()).isEqualTo(FastContextExperiment.Decision.BASELINE_ONLY);
    assertThat(report.explorerMeanRecall()).isNaN();
  }

  @Test
  void unavailableExplorerFailsClosedToDeterministicBaseline() throws Exception {
    Files.writeString(root.resolve("src.java"), "other\n");
    var request =
        new RepositorySearchService.SearchRequest(
            "missing", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);

    var report =
        new FastContextExperiment()
            .run(
                root,
                List.of(
                    new FastContextExperiment.FixtureCase(
                        "unavailable", request, Set.of("src.java"), true)),
                null,
                FastContextExperiment.InvocationPolicy.SELECTIVE);

    // An unavailable optional explorer must never turn an empty baseline into a synthetic
    // success or an adoption decision. Production callers remain on deterministic search.
    assertThat(report.explorerEngine()).isEqualTo("UNAVAILABLE");
    assertThat(report.explorerInvocations()).isZero();
    assertThat(report.explorerMatches()).isZero();
    assertThat(report.decision()).isEqualTo(FastContextExperiment.Decision.BASELINE_ONLY);
    assertThat(report.baselineMeanRecall()).isZero();
    assertThat(report.explorerMeanRecall()).isNaN();
  }

  @Test
  void syntheticPairedTraceCalculatesPrecisionLatencyAndCostAndAdoptsOnlyNoRegression()
      throws Exception {
    Files.writeString(root.resolve("good.java"), "target\n");
    Files.writeString(root.resolve("noise.java"), "noise\n");
    var request =
        new RepositorySearchService.SearchRequest(
            "target", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);
    var expected = Set.of("good.java");
    var candidateHit =
        new RepositorySearchService.SearchHit("good.java", 1, 1, "target", "TEXT", "CURRENT", 2);
    var candidate =
        new RepositorySearchService.SearchResult(
            "fixture-repo-sha",
            "fixture-inventory-sha",
            "fixture-query-sha",
            List.of(candidateHit),
            1,
            false,
            1,
            "synthetic-fastcontext");
    var explorer =
        new FastContextExperiment.Explorer() {
          @Override
          public RepositorySearchService.SearchResult search(
              Path workspace, RepositorySearchService.SearchRequest ignored) {
            return candidate;
          }

          @Override
          public FastContextExperiment.InvocationMetrics metrics(
              Path workspace,
              RepositorySearchService.SearchRequest ignored,
              RepositorySearchService.SearchResult ignoredResult) {
            return new FastContextExperiment.InvocationMetrics(80, 2, 0.000001);
          }

          @Override
          public String engine() {
            return "synthetic-fastcontext";
          }
        };
    var report =
        new FastContextExperiment()
            .run(
                root,
                List.of(new FastContextExperiment.FixtureCase("paired", request, expected, false)),
                explorer,
                FastContextExperiment.InvocationPolicy.PAIRED);

    assertThat(report.baselineMeanRecall()).isEqualTo(1.0);
    assertThat(report.baselineMeanPrecision()).isEqualTo(1.0);
    assertThat(report.explorerMeanPrecision()).isEqualTo(1.0);
    assertThat(report.baselineTokenEstimate()).isEqualTo(1);
    assertThat(report.explorerTokenEstimate()).isEqualTo(2);
    assertThat(report.explorerLatencyMicros()).isEqualTo(80);
    assertThat(report.explorerCostUsd()).isEqualTo(0.000001);
    // Equal quality is not sufficient for adoption; no synthetic claim is made.
    assertThat(report.decision()).isEqualTo(FastContextExperiment.Decision.KEEP_BASELINE);
    assertThat(report.cases().getFirst().explorerEvidence().engine())
        .isEqualTo("synthetic-fastcontext");
  }

  @Test
  void syntheticQualityGainIsRejectedWhenLatencyOrCostRegresses() throws Exception {
    Files.writeString(root.resolve("good.java"), "target\n");
    var request =
        new RepositorySearchService.SearchRequest(
            "absent", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);
    var candidateHit =
        new RepositorySearchService.SearchHit("good.java", 1, 1, "target", "TEXT", "CURRENT", 2);
    var candidate =
        new RepositorySearchService.SearchResult(
            "fixture-repo-sha",
            "fixture-inventory-sha",
            "fixture-query-sha",
            List.of(candidateHit),
            1,
            false,
            1,
            "synthetic-fastcontext");
    var explorer =
        new FastContextExperiment.Explorer() {
          @Override
          public RepositorySearchService.SearchResult search(
              Path workspace, RepositorySearchService.SearchRequest ignored) {
            return candidate;
          }

          @Override
          public FastContextExperiment.InvocationMetrics metrics(
              Path workspace,
              RepositorySearchService.SearchRequest ignored,
              RepositorySearchService.SearchResult ignoredResult) {
            return new FastContextExperiment.InvocationMetrics(10_000, 2, 0.10);
          }
        };
    var report =
        new FastContextExperiment()
            .run(
                root,
                List.of(
                    new FastContextExperiment.FixtureCase(
                        "regression", request, Set.of("good.java"), false)),
                explorer,
                FastContextExperiment.InvocationPolicy.PAIRED);

    assertThat(report.baselineMeanRecall()).isZero();
    assertThat(report.explorerMeanRecall()).isEqualTo(1.0);
    assertThat(report.decision()).isEqualTo(FastContextExperiment.Decision.KEEP_BASELINE);
  }
}
