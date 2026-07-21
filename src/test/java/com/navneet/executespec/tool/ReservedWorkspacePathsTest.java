package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RR-1018 — control-plane paths a worker mutation tool may never write. */
@DisplayName("ReservedWorkspacePaths")
class ReservedWorkspacePathsTest {

  @Test
  @DisplayName("Git control-plane files are refused, including in a nested repo")
  void gitControlPlaneRefused() {
    assertThat(ReservedWorkspacePaths.rejectionFor(".git/hooks/pre-commit")).isNotNull();
    assertThat(ReservedWorkspacePaths.rejectionFor(".git/config")).isNotNull();
    assertThat(ReservedWorkspacePaths.rejectionFor("repos/api/.git/config")).isNotNull();
    assertThat(ReservedWorkspacePaths.rejectionFor(".git\\hooks\\pre-push")).isNotNull();
  }

  @Test
  @DisplayName("agent evidence and other VCS control planes are refused")
  void otherReservedNamespacesRefused() {
    assertThat(ReservedWorkspacePaths.rejectionFor(".agent-context/tool-output/build.log"))
        .isNotNull();
    assertThat(ReservedWorkspacePaths.rejectionFor(".hg/hgrc")).isNotNull();
    assertThat(ReservedWorkspacePaths.rejectionFor(".svn/entries")).isNotNull();
  }

  @Test
  @DisplayName("ordinary files — including dotfiles that merely start with .git — are allowed")
  void ordinaryPathsAllowed() {
    assertThat(ReservedWorkspacePaths.rejectionFor(".gitignore")).isNull();
    assertThat(ReservedWorkspacePaths.rejectionFor(".gitattributes")).isNull();
    assertThat(ReservedWorkspacePaths.rejectionFor("src/main/java/App.java")).isNull();
    assertThat(ReservedWorkspacePaths.rejectionFor("docs/git/README.md")).isNull();
    assertThat(ReservedWorkspacePaths.rejectionFor(null)).isNull();
  }
}
