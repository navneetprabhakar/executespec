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
                  return new RepositorySearchService()
                      .search(workspace, request);
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
                List.of(new FastContextExperiment.FixtureCase("control", request, Set.of("src.java"), false)),
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
}
