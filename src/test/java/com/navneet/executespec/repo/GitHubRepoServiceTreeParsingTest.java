package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class GitHubRepoServiceTreeParsingTest {

  @Test
  void parsesEachPathWithItsOwnTypeRegardlessOfGitHubFieldOrder() {
    String response =
        """
        {
          "tree": [
            {"path":".gitignore","mode":"100644","type":"blob","sha":"a"},
            {"path":".github/workflows/ci.yml","mode":"100644","type":"blob","sha":"aa"},
            {"path":".git/objects/internal","mode":"100644","type":"blob","sha":"aaa"},
            {"path":"README.md","mode":"100644","type":"blob","sha":"b"},
            {"path":"src","mode":"040000","type":"tree","sha":"c"},
            {"path":"src/AIPlayer.java","mode":"100644","type":"blob","sha":"d"},
            {"path":"src/TicTacToe.java","mode":"100644","type":"blob","sha":"e"}
          ],
          "truncated": false
        }
        """;

    assertThat(GitHubRepoService.parseTreePaths(response))
        .isEqualTo(
            List.of(
                ".github/workflows/ci.yml",
                ".gitignore",
                "README.md",
                "src/AIPlayer.java",
                "src/TicTacToe.java"));
  }

  @Test
  void malformedOrMissingTreeFailsClosedToNoFiles() {
    assertThat(GitHubRepoService.parseTreePaths("not-json")).isEmpty();
    assertThat(GitHubRepoService.parseTreePaths("{\"tree\":null}")).isEmpty();
  }
}
