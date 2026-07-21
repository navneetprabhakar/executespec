package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * RR-1656 / RR-1631 — the two GitHub read paths must distinguish "we asked and the answer was
 * nothing" from "we could not ask". These cases are the ones reachable without a network call; the
 * upstream-status branches are covered structurally by the shared factory contract asserted here.
 */
class GitHubRepoServiceFailureSignallingTest {

  private final GitHubRepoService service = new GitHubRepoService();

  // ---- RR-1656: compareCommits -------------------------------------------------------------

  @Test
  void identicalShasAreASuccessfulEmptyCompare() {
    GitHubRepoService.CompareResult result = service.compareCommits("acme/api", "aaa", "aaa", null);

    assertThat(result.successful()).isTrue();
    assertThat(result.files()).isEmpty();
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void missingArgumentsAreAFailedCompareNotAnEmptyOne() {
    assertThat(service.compareCommits(null, "aaa", "bbb", null).successful()).isFalse();
    assertThat(service.compareCommits("acme/api", null, "bbb", null).successful()).isFalse();
    assertThat(service.compareCommits("acme/api", "aaa", null, null).successful()).isFalse();
    assertThat(service.compareCommits(null, "aaa", "bbb", null).errorCode())
        .isEqualTo("INVALID_COMPARE_ARGUMENTS");
  }

  @Test
  void compareResultFactoriesSeparateEmptySuccessFromFailure() {
    GitHubRepoService.CompareResult emptySuccess =
        GitHubRepoService.CompareResult.success(List.of());
    GitHubRepoService.CompareResult httpFailure =
        GitHubRepoService.CompareResult.failed("GITHUB_COMPARE_HTTP_ERROR", 403);
    GitHubRepoService.CompareResult networkFailure =
        GitHubRepoService.CompareResult.failed("GITHUB_COMPARE_REQUEST_FAILED", null);

    // Byte-identical file lists, materially different states — the RR-1656 defect.
    assertThat(emptySuccess.files()).isEqualTo(httpFailure.files());
    assertThat(emptySuccess.successful()).isTrue();
    assertThat(httpFailure.successful()).isFalse();
    assertThat(httpFailure.upstreamStatus()).isEqualTo(403);
    assertThat(networkFailure.successful()).isFalse();
    assertThat(networkFailure.upstreamStatus()).isNull();
  }

  /** A compare that could not be fetched must not be read as "everything is under the prefix". */
  @Test
  void diffConfinedToIsFalseWhenTheCompareCannotBeFetched() {
    assertThat(service.diffConfinedTo(null, "aaa", "bbb", ".executespec/", null)).isFalse();
    assertThat(service.diffConfinedTo("acme/api", "aaa", "aaa", ".executespec/", null)).isFalse();
  }

  // ---- RR-1631: readRepoFile ---------------------------------------------------------------

  @Test
  void missingPatIsATypedFailureNotContent() {
    GitHubRepoService.RepoFileResult result =
        service.readRepoFile("https://github.com/a/b", "x", "");

    assertThat(result.successful()).isFalse();
    assertThat(result.errorCode()).isEqualTo("MISSING_GITHUB_PAT");
    assertThat(result.content()).isNull();
    assertThat(result.contentOrNull()).isNull();
  }

  @Test
  void invalidRepoUrlIsATypedFailureNotContent() {
    GitHubRepoService.RepoFileResult result = service.readRepoFile("not-a-url", "x", "ghp_x");

    assertThat(result.successful()).isFalse();
    assertThat(result.errorCode()).isEqualTo("INVALID_REPOSITORY_URL");
    assertThat(result.contentOrNull()).isNull();
  }

  /**
   * The collision RR-1631 was really about: a legitimate proto/GraphQL/C-style file whose first
   * line is a {@code //} comment is a SUCCESSFUL read and its content is returned verbatim.
   */
  @Test
  void contentThatLooksLikeAnOldSentinelIsStillASuccessfulRead() {
    String proto = "// Copyright 2026 Acme\nsyntax = \"proto3\";\n";
    assertThat(GitHubRepoService.RepoFileResult.success(proto).successful()).isTrue();
    assertThat(GitHubRepoService.RepoFileResult.success(proto).contentOrNull()).isEqualTo(proto);

    String looksLikeAnError = "// File not found on GitHub (status 404)";
    assertThat(GitHubRepoService.RepoFileResult.success(looksLikeAnError).successful()).isTrue();
    assertThat(GitHubRepoService.RepoFileResult.success(looksLikeAnError).contentOrNull())
        .isEqualTo(looksLikeAnError);
  }

  @Test
  void notFoundIsDistinguishableFromOtherFailures() {
    assertThat(
            GitHubRepoService.RepoFileResult.failed("GITHUB_CONTENTS_HTTP_ERROR", 404).notFound())
        .isTrue();
    assertThat(
            GitHubRepoService.RepoFileResult.failed("GITHUB_CONTENTS_HTTP_ERROR", 403).notFound())
        .isFalse();
    assertThat(
            GitHubRepoService.RepoFileResult.failed("GITHUB_CONTENTS_REQUEST_FAILED", null)
                .notFound())
        .isFalse();
  }
}
