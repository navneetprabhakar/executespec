package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitHubRepoServiceOpenPullRequestLookupTest {

  private static final String SHA = "a".repeat(40);

  @Test
  void acceptsOnlyTheExactCompleteHeadIdentity() {
    String body =
        """
        [
          {"html_url":"https://github.com/acme/app/pull/12","number":12,
           "head":{"ref":"feature/pr-12","sha":"%s"},"base":{"ref":"main"}},
          {"html_url":"https://github.com/acme/app/pull/13","number":13,
           "head":{"ref":"feature/pr-123","sha":"%s"},"base":{"ref":"main"}}
        ]
        """
            .formatted(SHA, "b".repeat(40));

    var lookup = GitHubRepoService.parseOpenPullRequestLookup("acme/app", "feature/pr-12", body);

    assertThat(lookup.known()).isTrue();
    assertThat(lookup.present()).isTrue();
    assertThat(lookup.prNumber()).isEqualTo(12);
    assertThat(lookup.baseBranch()).isEqualTo("main");
    assertThat(lookup.headSha()).isEqualTo(SHA);
  }

  @Test
  void malformedOrAmbiguousExactMatchesFailClosed() {
    String malformed =
        """
        [{"html_url":"https://github.com/acme/app/pull/12","number":12,
          "head":{"ref":"feature/pr-12","sha":"short"},"base":{"ref":"main"}}]
        """;
    String ambiguous =
        """
        [
          {"html_url":"https://github.com/acme/app/pull/12","number":12,
           "head":{"ref":"feature/pr-12","sha":"%s"},"base":{"ref":"main"}},
          {"html_url":"https://github.com/acme/app/pull/14","number":14,
           "head":{"ref":"feature/pr-12","sha":"%s"},"base":{"ref":"main"}}
        ]
        """
            .formatted(SHA, "b".repeat(40));

    assertThat(
            GitHubRepoService.parseOpenPullRequestLookup("acme/app", "feature/pr-12", malformed)
                .known())
        .isFalse();
    assertThat(
            GitHubRepoService.parseOpenPullRequestLookup("acme/app", "feature/pr-12", ambiguous)
                .known())
        .isFalse();
    assertThat(
            GitHubRepoService.parseOpenPullRequestLookup("acme/app", "feature/pr-12", "not-json")
                .known())
        .isFalse();
  }

  @Test
  void aValidResponseWithoutAnExactHeadProvesAbsence() {
    String body =
        """
        [{"html_url":"https://github.com/acme/app/pull/13","number":13,
          "head":{"ref":"feature/other","sha":"%s"},"base":{"ref":"main"}}]
        """
            .formatted(SHA);

    var lookup = GitHubRepoService.parseOpenPullRequestLookup("acme/app", "feature/pr-12", body);

    assertThat(lookup.known()).isTrue();
    assertThat(lookup.present()).isFalse();
  }
}
