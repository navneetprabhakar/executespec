package com.navneet.executespec.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the shared containment boundary reused by every symlink-escape remediation. */
class SafeWorkspacePathTest {

  @TempDir Path tempRoot;

  private Path workspace;
  private Path outside;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectories(tempRoot.resolve("workspace"));
    outside = Files.createDirectories(tempRoot.resolve("outside"));
  }

  @Test
  void resolvesAnOrdinaryRelativePath() throws Exception {
    Path resolved = SafeWorkspacePath.resolveWithin(workspace, ".agent-context/project.json");
    assertThat(resolved.toString()).startsWith(workspace.toRealPath().toString());
    assertThat(resolved.toString()).endsWith(".agent-context/project.json");
  }

  @Test
  void allowsANotYetExistingLeafUnderAnExistingParent() throws Exception {
    Files.createDirectories(workspace.resolve("deep/nested"));
    assertThatCode(() -> SafeWorkspacePath.resolveWithin(workspace, "deep/nested/new.txt"))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsAnAbsoluteCandidateBuiltFromTheNonCanonicalRoot() throws Exception {
    // On macOS a temp root under /var canonicalizes to /private/var. A walk result built from the
    // caller's own root must not be misread as an escape.
    Files.writeString(workspace.resolve("real.txt"), "x");
    assertThat(SafeWorkspacePath.isWithin(workspace, workspace.resolve("real.txt"))).isTrue();
    assertThatCode(() -> SafeWorkspacePath.requireWithin(workspace, workspace.resolve("real.txt")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAnAbsoluteCandidateOutsideTheRoot() throws Exception {
    Files.writeString(outside.resolve("secret.txt"), "s");
    assertThat(SafeWorkspacePath.isWithin(workspace, outside.resolve("secret.txt"))).isFalse();
  }

  @Test
  void rejectsAbsoluteAndTraversalPaths() {
    assertThatThrownBy(() -> SafeWorkspacePath.resolveWithin(workspace, "/etc/passwd"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.resolveWithin(workspace, "../outside/x"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.resolveWithin(workspace, "a/../../b"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.resolveWithin(workspace, "  "))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void rejectsAFinalComponentThatIsASymlink() throws Exception {
    Path secret = outside.resolve("secret.txt");
    Files.writeString(secret, "secret");
    assumeThat(createLink(workspace.resolve("link.txt"), secret)).isTrue();

    assertThatThrownBy(() -> SafeWorkspacePath.resolveWithin(workspace, "link.txt"))
        .isInstanceOf(SecurityException.class);
    assertThat(SafeWorkspacePath.isWithin(workspace, workspace.resolve("link.txt"))).isFalse();
  }

  @Test
  void rejectsAParentComponentThatIsASymlink() throws Exception {
    Files.writeString(outside.resolve("decisions.md"), "secret");
    assumeThat(createLink(workspace.resolve(".agent-context"), outside)).isTrue();

    assertThatThrownBy(
            () -> SafeWorkspacePath.resolveWithin(workspace, ".agent-context/decisions.md"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void rejectsADanglingSymlinkSoAWriteCannotCreateAHostFile() throws Exception {
    assumeThat(createLink(workspace.resolve("README.md"), outside.resolve("does-not-exist")))
        .isTrue();

    assertThatThrownBy(() -> SafeWorkspacePath.resolveWithin(workspace, "README.md"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void relativePosixValidationRejectsEscapeShapes() {
    assertThatThrownBy(
            () ->
                SafeWorkspacePath.requireSafeRelativePosixPath(
                    ".executespec/contracts/../../../.github/workflows/release.yml"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.requireSafeRelativePosixPath("/etc/passwd"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.requireSafeRelativePosixPath("a\\b"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.requireSafeRelativePosixPath("a%2f..%2fb"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.requireSafeRelativePosixPath("a//b"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> SafeWorkspacePath.requireSafeRelativePosixPath("a/" + (char) 0 + "b"))
        .isInstanceOf(SecurityException.class);

    assertThat(SafeWorkspacePath.requireSafeRelativePosixPath(".executespec/contracts/a.proto"))
        .isEqualTo(".executespec/contracts/a.proto");
  }

  private boolean createLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    }
  }
}
