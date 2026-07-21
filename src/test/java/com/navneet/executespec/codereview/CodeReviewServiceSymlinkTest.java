package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * RR-434 regression — the reviewed tree is a customer-controlled clone and its sampled contents are
 * sent to an external LLM. A repository symlink pointing at a readable host file must never be
 * followed by the sampler.
 */
class CodeReviewServiceSymlinkTest {

  @TempDir Path tempRoot;

  private CodeReviewService service;
  private Path workspace;
  private Path outside;

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
    outside = Files.createDirectories(tempRoot.resolve("host"));
    Files.writeString(outside.resolve("secrets.py"), "AWS_SECRET = 'AKIA-CANARY'\n");
  }

  private boolean link(Path from, Path to) {
    try {
      Files.createSymbolicLink(from, to);
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    }
  }

  @Test
  void doesNotSampleAHostFileReachedThroughAFileSymlink() throws Exception {
    Files.writeString(workspace.resolve("real.py"), "print('hello')\n");
    assumeThat(link(workspace.resolve("leak.py"), outside.resolve("secrets.py"))).isTrue();

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.samples()).extracting(s -> s.path()).contains("real.py");
    assertThat(result.samples()).extracting(s -> s.path()).doesNotContain("leak.py");
    assertThat(result.samples()).allSatisfy(s -> assertThat(s.content()).doesNotContain("AKIA"));
  }

  @Test
  void doesNotDescendIntoASymlinkedDirectory() throws Exception {
    assumeThat(link(workspace.resolve("vendor"), outside)).isTrue();

    CodeReviewService.SampleResult result = service.sampleFiles(workspace, List.of());

    assertThat(result.samples()).allSatisfy(s -> assertThat(s.content()).doesNotContain("AKIA"));
  }
}
