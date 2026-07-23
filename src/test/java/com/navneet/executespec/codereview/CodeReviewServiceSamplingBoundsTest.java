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
 * RR-433 (bounded retention) + RR-435 (no raw credential material reaches the review model)
 * regressions for the Code Review workspace sampler.
 */
class CodeReviewServiceSamplingBoundsTest {

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

  // ---- RR-433 ----

  @Test
  void neverReturnsMoreThanThePromptCapEvenForALargeTree() throws Exception {
    for (int i = 0; i < 500; i++) {
      Files.writeString(
          workspace.resolve("File" + i + ".java"), "class F" + i + " { void a() {} }\n");
    }

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.samples()).hasSize(20);
    assertThat(result.totalFiles()).isEqualTo(500);
    assertThat(result.totalLoc()).isEqualTo(500);
  }

  @Test
  void countsEveryLineWithoutRetainingTheWholeFile() throws Exception {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 5_000; i++) big.append("// line ").append(i).append('\n');
    Files.writeString(workspace.resolve("Big.java"), big.toString());

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.totalLoc()).isEqualTo(5_000);
    assertThat(result.samples()).hasSize(1);
    // Bounded excerpt: the 12,000-char cap plus the truncation marker, not the ~60KB file.
    assertThat(result.samples().get(0).content()).hasSizeLessThan(12_100);
    assertThat(result.samples().get(0).content()).contains("(truncated)");
  }

  @Test
  void ranksStaticFindingHotspotsAheadOfOrdinarySourceDespiteTheBoundedHeap() throws Exception {
    // 40 ordinary files walked first, then the hotspot. A bounded top-K that applied the +50 bump
    // only after the walk would already have evicted the hotspot.
    for (int i = 0; i < 40; i++) {
      Files.writeString(workspace.resolve("aaa" + i + ".java"), "class A" + i + " {}\n");
    }
    Files.writeString(workspace.resolve("zzz-hot.java"), "class Hot {}\n");

    CodeReviewService.SampleResult result =
        service.sampleFiles(
            workspace,
            List.of(
                new SemgrepRunner.Finding(
                    "rule", "CRITICAL", "security", "zzz-hot.java", 1, 1, "msg", "snippet")));

    assertThat(result.samples())
        .extracting(CodeReviewAgent.FileSample::path)
        .contains("zzz-hot.java");
  }

  // ---- RR-435 ----

  @Test
  void excludesCredentialStoresFromTheSampleEntirely() throws Exception {
    Files.writeString(workspace.resolve(".env"), "OPENAI_API_KEY=sk-proj-" + "a".repeat(40) + "\n");
    Files.writeString(workspace.resolve("id_rsa"), "-----BEGIN RSA PRIVATE KEY-----\nabc\n");
    Files.writeString(workspace.resolve("server.key"), "-----BEGIN PRIVATE KEY-----\nabc\n");
    Files.writeString(workspace.resolve("App.java"), "class App {}\n");

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.samples())
        .extracting(CodeReviewAgent.FileSample::path)
        .containsExactly("App.java");
  }

  @Test
  void keepsDeclaredTemplatesInspectable() throws Exception {
    Files.writeString(workspace.resolve(".env.example"), "OPENAI_API_KEY=\n");

    assertThat(CodeReviewService.shouldSkip(".env.example")).isFalse();
    assertThat(CodeReviewService.shouldSkip(".env")).isTrue();
    assertThat(CodeReviewService.shouldSkip(".env.production")).isTrue();
  }

  @Test
  void redactsCredentialsFoundInOtherwiseOrdinarySourceBeforeItLeavesTheSampler() throws Exception {
    String key = "sk-ant-" + "b".repeat(40);
    Files.writeString(
        workspace.resolve("Config.java"), "class Config { String k = \"" + key + "\"; }\n");

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.samples()).hasSize(1);
    assertThat(result.samples().get(0).content()).doesNotContain(key);
    assertThat(result.samples().get(0).content()).contains("ANTHROPIC_KEY");
  }

  @Test
  void sendsOnlyBoundedLineWindowsAroundAFindingAnchorForAHotSpotFile() throws Exception {
    // RR-435 — a large flagged file must disclose only the neighbourhood of the finding, not its
    // whole head. Line 1 carries a unique marker far from the anchor; line 500 carries the anchor
    // marker. With a finding at line 500 the sample must contain the anchor line and drop line 1.
    StringBuilder big = new StringBuilder();
    for (int i = 1; i <= 1000; i++) {
      if (i == 1) big.append("AAA_TOP_MARKER_FAR_FROM_ANCHOR\n");
      else if (i == 500) big.append("ZZZ_ANCHOR_MARKER\n");
      else big.append("int filler_").append(i).append(" = ").append(i).append(";\n");
    }
    Files.writeString(workspace.resolve("Big.java"), big.toString());

    CodeReviewService.SampleResult result =
        service.sampleFiles(
            workspace,
            List.of(
                new SemgrepRunner.Finding(
                    "rule", "MAJOR", "security", "Big.java", 500, 500, "msg", "snippet")),
            "scan-test");

    CodeReviewAgent.FileSample sample =
        result.samples().stream()
            .filter(s -> s.path().equals("Big.java"))
            .findFirst()
            .orElseThrow();
    assertThat(sample.content()).contains("ZZZ_ANCHOR_MARKER");
    assertThat(sample.content()).doesNotContain("AAA_TOP_MARKER_FAR_FROM_ANCHOR");
    // The whole-file line count is still reported honestly even though only a window was sent.
    assertThat(result.totalLoc()).isEqualTo(1000);
  }
}
