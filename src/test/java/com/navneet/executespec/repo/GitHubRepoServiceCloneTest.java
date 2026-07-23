package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-150 — a missing GitHub credential must never silently substitute an empty {@code git init}
 * workspace for an existing-repo clone. Without a credential we attempt an unauthenticated clone
 * (which succeeds only for public repos); when that fails we fail closed rather than proceeding
 * against an empty source.
 */
class GitHubRepoServiceCloneTest {

  @Test
  void missingCredentialCloneFailsClosedInsteadOfInitializingAnEmptyRepo(@TempDir Path tmp) {
    GitHubRepoService service = new GitHubRepoService();
    // A local, non-existent source path fails the clone immediately and offline.
    String unreachable = tmp.resolve("does-not-exist.git").toString();
    Path workspace = tmp.resolve("ws");

    assertThatThrownBy(() -> service.cloneRepo(unreachable, workspace.toString(), null))
        .isInstanceOf(GitHubRepoService.MissingCredentialException.class)
        .hasMessageContaining("MISSING_GITHUB_PAT");
  }
}
