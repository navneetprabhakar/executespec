package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.security.guardrail.GuardrailLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B-02 fix (2026-05-09) — symlink traversal guard.
 *
 * Verifies that the sandbox refuses writes that would reach outside the
 * workspace via symbolic links, at every guardrail level (the bug was
 * present at LOW, the default — `relativize()` walked logical paths
 * only and didn't notice symlinks at all).
 */
@DisplayName("FileSandboxPolicy — B-02 symlink traversal guard")
class FileSandboxPolicySymlinkTest {

  private final FileSandboxPolicy policy = new FileSandboxPolicy();

  @Test
  @DisplayName("LOW: writing through a symlink that targets outside workspace is blocked")
  void targetIsSymlinkOutsideWorkspace(@TempDir Path tmp) throws IOException {
    Path workspace = Files.createDirectory(tmp.resolve("ws"));
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Path real = Files.createFile(outside.resolve("secret.txt"));
    Path symlink = workspace.resolve("inside.txt");
    try {
      Files.createSymbolicLink(symlink, real);
    } catch (UnsupportedOperationException | IOException e) {
      // Filesystem doesn't support symlinks (e.g., Windows without dev mode).
      // Skip silently — the production target is Linux where symlinks always
      // work.
      return;
    }
    var decision = policy.validateWrite(workspace, symlink, GuardrailLevel.LOW);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("symlink");
  }

  @Test
  @DisplayName("LOW: writing into a directory that's a symlink outside workspace is blocked")
  void parentIsSymlinkOutsideWorkspace(@TempDir Path tmp) throws IOException {
    Path workspace = Files.createDirectory(tmp.resolve("ws"));
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Path symlinkDir = workspace.resolve("escape");
    try {
      Files.createSymbolicLink(symlinkDir, outside);
    } catch (UnsupportedOperationException | IOException e) {
      return;
    }
    Path target = symlinkDir.resolve("evil.txt");
    var decision = policy.validateWrite(workspace, target, GuardrailLevel.LOW);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("symlink");
  }

  @Test
  @DisplayName("LOW: legitimate write inside workspace (no symlinks) is allowed")
  void legitimateWriteAllowed(@TempDir Path tmp) throws IOException {
    Path workspace = Files.createDirectory(tmp.resolve("ws"));
    Files.createDirectory(workspace.resolve("src"));
    Path target = workspace.resolve("src").resolve("ok.txt");
    var decision = policy.validateWrite(workspace, target, GuardrailLevel.LOW);
    assertThat(decision.allowed()).isTrue();
  }

  @Test
  @DisplayName("MEDIUM: symlink check still fires before the path-allowlist check")
  void symlinkBlockedAtMediumToo(@TempDir Path tmp) throws IOException {
    Path workspace = Files.createDirectory(tmp.resolve("ws"));
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Path real = Files.createFile(outside.resolve("secret.txt"));
    Path symlink = workspace.resolve("src");
    try {
      Files.createSymbolicLink(symlink, real);
    } catch (UnsupportedOperationException | IOException e) {
      return;
    }
    var decision = policy.validateWrite(workspace, symlink, GuardrailLevel.MEDIUM);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("symlink");
  }
}
