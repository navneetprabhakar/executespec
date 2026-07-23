package com.navneet.executespec.standards.enforcement.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1051 — admission tests for repository-scoped lint execution.
 *
 * <p>The load-bearing assertion is the first one: with default configuration nothing runs. Every
 * other case only matters once an operator has explicitly accepted that this host executes
 * customer-authored code.
 */
class LintExecutionPolicyTest {

  private static LintExecutionPolicy enabledWith(Path repoRoot, Map<String, String> binaries) {
    LintExecutionProperties props = new LintExecutionProperties();
    props.setHostExecutionEnabled(true);
    props.getBinaries().putAll(binaries);
    return new LintExecutionPolicy(props);
  }

  private static Path pinnedExecutable(Path dir) throws Exception {
    Path bin = dir.resolve("fake-linter");
    Files.writeString(bin, "#!/bin/sh\nexit 0\n");
    bin.toFile().setExecutable(true);
    return bin;
  }

  @Test
  void deniedByDefault(@TempDir Path repoRoot) {
    LintExecutionPolicy policy = new LintExecutionPolicy(new LintExecutionProperties());
    LintExecutionPolicy.Admission a = policy.admit("eslint", repoRoot, null, List.of());
    assertThat(a.allowed()).isFalse();
    assertThat(a.denialReason()).contains("host-execution-enabled=false");
    assertThat(a.executable()).isNull();
  }

  @Test
  void deniedWhenNoPinnedBinaryConfigured(@TempDir Path repoRoot) {
    LintExecutionPolicy policy = enabledWith(repoRoot, Map.of());
    LintExecutionPolicy.Admission a = policy.admit("eslint", repoRoot, null, List.of());
    assertThat(a.allowed()).isFalse();
    assertThat(a.denialReason()).contains("no operator-pinned executable");
  }

  @Test
  void deniedWhenPinnedBinaryIsNotAbsolute(@TempDir Path repoRoot) {
    LintExecutionPolicy policy = enabledWith(repoRoot, Map.of("eslint", "eslint"));
    LintExecutionPolicy.Admission a = policy.admit("eslint", repoRoot, null, List.of());
    assertThat(a.allowed()).isFalse();
    assertThat(a.denialReason()).contains("absolute path");
  }

  @Test
  void allowsPinnedBinaryWithNoConfig(@TempDir Path repoRoot, @TempDir Path binDir)
      throws Exception {
    Path bin = pinnedExecutable(binDir);
    LintExecutionPolicy policy = enabledWith(repoRoot, Map.of("eslint", bin.toString()));
    LintExecutionPolicy.Admission a = policy.admit("eslint", repoRoot, null, List.of());
    assertThat(a.allowed()).isTrue();
    assertThat(a.executable()).isEqualTo(bin.toString());
    assertThat(a.configArgument()).isNull();
    assertThat(a.targetArguments()).isEmpty();
  }

  @Test
  void deniesConfigOutsideTheWorkspace(@TempDir Path repoRoot, @TempDir Path elsewhere)
      throws Exception {
    Path bin = pinnedExecutable(elsewhere);
    Path outsideConfig = Files.writeString(elsewhere.resolve("eslint.config.js"), "//");
    LintExecutionPolicy policy = enabledWith(repoRoot, Map.of("eslint", bin.toString()));
    LintExecutionPolicy.Admission a = policy.admit("eslint", repoRoot, outsideConfig, List.of());
    assertThat(a.allowed()).isFalse();
    assertThat(a.denialReason()).contains("inside the workspace root");
  }

  @Test
  void deniesTraversalConfig(@TempDir Path repoRoot, @TempDir Path binDir) throws Exception {
    Path bin = pinnedExecutable(binDir);
    LintExecutionPolicy policy = enabledWith(repoRoot, Map.of("eslint", bin.toString()));
    LintExecutionPolicy.Admission a =
        policy.admit("eslint", repoRoot, Path.of("../../etc/passwd"), List.of());
    assertThat(a.allowed()).isFalse();
  }

  @Test
  void deniesSymlinkedConfig(@TempDir Path repoRoot, @TempDir Path elsewhere) throws Exception {
    Path bin = pinnedExecutable(elsewhere);
    Path secret = Files.writeString(elsewhere.resolve("secret.js"), "//");
    Path link = repoRoot.resolve("eslint.config.js");
    try {
      Files.createSymbolicLink(link, secret);
    } catch (UnsupportedOperationException | java.io.IOException unsupported) {
      return; // symlink creation unavailable on this filesystem; nothing to assert
    }
    LintExecutionPolicy policy = enabledWith(repoRoot, Map.of("eslint", bin.toString()));
    LintExecutionPolicy.Admission a = policy.admit("eslint", repoRoot, link, List.of());
    assertThat(a.allowed()).isFalse();
  }

  @Test
  void containsTargetFilesToTheWorkspace(@TempDir Path repoRoot, @TempDir Path elsewhere)
      throws Exception {
    Path bin = pinnedExecutable(elsewhere);
    Path inside = Files.writeString(repoRoot.resolve("a.js"), "//");
    Path outside = Files.writeString(elsewhere.resolve("b.js"), "//");
    LintExecutionPolicy policy = enabledWith(repoRoot, Map.of("eslint", bin.toString()));

    assertThat(policy.admit("eslint", repoRoot, null, List.of(inside)).allowed()).isTrue();
    assertThat(policy.admit("eslint", repoRoot, null, List.of(outside)).allowed()).isFalse();
  }
}
