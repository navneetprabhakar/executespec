package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * RR-1685 — the review prompt used to assert "File count: N / Total LOC: M" as fact, while RR-433's
 * bounded walk can stop at a read budget, a time budget or a file cap. Same family as RR-1657 /
 * RR-1658: an unmeasured value must never be presented as a measured one.
 */
class CodeReviewScanCoverageTest {

  @TempDir Path tempRoot;

  private CodeReviewService service;
  private Path workspace;

  @BeforeEach
  void setUp() throws IOException {
    service =
        new CodeReviewService(
            Mockito.mock(
                com.navneet.executespec.codereview.repository.CodeReviewScanRepository.class),
            Mockito.mock(
                com.navneet.executespec.codereview.repository.CodeReviewIssueRepository.class),
            Mockito.mock(SemgrepRunner.class),
            Mockito.mock(CodeReviewAgent.class),
            Mockito.mock(com.navneet.executespec.harness.WorkspaceInitService.class),
            Mockito.mock(com.navneet.executespec.cost.RunCostTracker.class),
            Mockito.mock(com.navneet.executespec.sse.SseBroadcaster.class),
            Mockito.mock(com.navneet.executespec.project.repository.ProjectRepoRepository.class),
            Mockito.mock(com.navneet.executespec.repo.GitHubRepoService.class),
            Mockito.mock(com.navneet.executespec.auth.repository.TierConfigRepository.class),
            Mockito.mock(com.navneet.executespec.router.ProjectProviderResolver.class));
    workspace = Files.createDirectories(tempRoot.resolve("clone"));
  }

  @Test
  void aCompletedWalkReportsCompleteCoverage() throws Exception {
    Files.writeString(workspace.resolve("A.java"), "class A {}\n");

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.coverage()).isEqualTo(CodeReviewAgent.ScanCoverage.COMPLETE);
  }

  @Test
  void aWalkStoppedByTheReadBudgetReportsATruncatedCoverage() throws Exception {
    // Each file sits just under the 1,000,000-byte per-file cap so it is READ rather than skipped;
    // 300 of them exceed the sampler's 256 MiB cumulative read budget, so the walk cannot finish.
    String body = "class Big { /*" + "x".repeat(998_000) + "*/ }\n";
    for (int i = 0; i < 300; i++) {
      Files.writeString(workspace.resolve("Big" + i + ".java"), body);
    }

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.coverage()).isEqualTo(CodeReviewAgent.ScanCoverage.TRUNCATED_READ_BUDGET);
    assertThat(result.coverage().isTruncated()).isTrue();
  }

  @Test
  void aMissingWorkspaceIsNotReportedAsAFullyMeasuredEmptyRepository() {
    CodeReviewService.SampleResult result =
        service.sampleFiles(tempRoot.resolve("does-not-exist"), List.of());

    assertThat(result.totalFiles()).isZero();
    assertThat(result.coverage()).isEqualTo(CodeReviewAgent.ScanCoverage.TRUNCATED_WALK_FAILED);
  }

  @Test
  void aTruncatedScanMarksBothCountsAsLowerBoundsInThePrompt() {
    String prompt = promptFor(CodeReviewAgent.ScanCoverage.TRUNCATED_READ_BUDGET);

    assertThat(prompt).contains("Scanned file count: 120 (LOWER BOUND — not the whole repository)");
    assertThat(prompt)
        .contains("Lines of code read: 9000 (LOWER BOUND — not the whole repository)");
    assertThat(prompt).contains("Coverage: PARTIAL");
    assertThat(prompt).contains("cumulative read budget");
  }

  @Test
  void aCompleteScanCarriesNoLowerBoundMarker() {
    String prompt = promptFor(CodeReviewAgent.ScanCoverage.COMPLETE);

    assertThat(prompt).doesNotContain("LOWER BOUND");
    assertThat(prompt).contains("Coverage: COMPLETE");
  }

  @Test
  void anUnrecordedCoverageIsTreatedAsUnknownRatherThanComplete() {
    String prompt = promptFor(null);

    assertThat(prompt).contains("LOWER BOUND");
    assertThat(prompt).contains("Coverage: UNKNOWN");
  }

  private String promptFor(CodeReviewAgent.ScanCoverage coverage) {
    // composeUserPrompt is a pure function of the request; it touches no collaborator.
    CodeReviewAgent agent = new CodeReviewAgent(null, null, null, null);
    return agent.composeUserPrompt(
        CodeReviewAgent.AgentRequest.builder()
            .scanId("scan-1")
            .projectId("project-1")
            .repoFullName("org/service")
            .scannedSha("abc123")
            .languages(List.of("java"))
            .fileCount(120)
            .totalLoc(9000)
            .scanCoverage(coverage)
            .staticFindings(List.of())
            .fileSamples(List.of())
            .build());
  }
}
