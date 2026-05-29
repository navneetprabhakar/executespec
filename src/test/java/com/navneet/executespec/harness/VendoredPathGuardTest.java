package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks in the contract for {@link VendoredPathGuard} — the regex must match every entry in {@link
 * VendoredPathGuard#VENDORED_DIRS} when present as a path segment, and must NOT trigger on customer
 * files that happen to have those words as substrings (e.g. {@code build.gradle.kts}).
 *
 * <p>Augmentation is also tested end-to-end against a real {@code .gitignore} on a temp dir so the
 * idempotency + comment-marker contract stays solid.
 */
class VendoredPathGuardTest {

  @Test
  void isVendored_matchesEveryDeclaredDir_atRoot() {
    for (String dir : VendoredPathGuard.VENDORED_DIRS) {
      assertTrue(
          VendoredPathGuard.isVendored(dir + "/foo.js"), "expected match for root segment " + dir);
    }
  }

  @Test
  void isVendored_matchesEveryDeclaredDir_nestedUnderRepoSubdir() {
    for (String dir : VendoredPathGuard.VENDORED_DIRS) {
      assertTrue(
          VendoredPathGuard.isVendored("repos/api/" + dir + "/foo.js"),
          "expected match for nested " + dir);
    }
  }

  @Test
  void isVendored_doesNotMatchCustomerFilesThatHappenToContainVendoredWords() {
    // The b171fa52 root cause was a regex that matched substrings — so a file called
    // "build.gradle.kts" would have been falsely classified as vendored. The new regex
    // anchors on path segments to avoid this class of false positive.
    assertFalse(VendoredPathGuard.isVendored("repos/api/build.gradle.kts"));
    assertFalse(VendoredPathGuard.isVendored("repos/api/src/Vendor.java"));
    assertFalse(VendoredPathGuard.isVendored("repos/api/scripts/coverage_report.py"));
    assertFalse(VendoredPathGuard.isVendored("repos/api/distribution.md"));
  }

  @Test
  void isVendored_handlesNullAndBlank() {
    assertFalse(VendoredPathGuard.isVendored(null));
    assertFalse(VendoredPathGuard.isVendored(""));
    assertFalse(VendoredPathGuard.isVendored("   "));
  }

  @Test
  void isVendored_caseInsensitive() {
    assertTrue(VendoredPathGuard.isVendored("Node_Modules/index.js"));
    assertTrue(VendoredPathGuard.isVendored("repos/api/.GRADLE/cache"));
  }

  // augmentGitignore — idempotency + missing-pattern detection

  @Test
  void augmentGitignore_appendsMissingPatterns(@TempDir Path tmp) throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);
    // Python-only ignore — exactly the pplai-api shape from b171fa52.
    Files.writeString(repoDir.resolve(".gitignore"), "__pycache__/\nvenv/\n*.pyc\n.env\n");

    VendoredPathGuard guard = new VendoredPathGuard();
    guard.augmentGitignore(repoDir);

    String body = Files.readString(repoDir.resolve(".gitignore"));
    assertTrue(body.contains("node_modules/"), "node_modules/ should be appended");
    assertTrue(body.contains("dist/"), "dist/ should be appended");
    assertTrue(body.contains(".gradle/"), ".gradle/ should be appended");
    assertTrue(body.contains(">>> ExecuteSpec"), "should mark the appended block");
    assertTrue(body.contains("__pycache__/"), "existing entries kept");
    assertTrue(body.contains("venv/"), "existing entries kept");
  }

  @Test
  void augmentGitignore_isIdempotent(@TempDir Path tmp) throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);
    Files.writeString(repoDir.resolve(".gitignore"), "");

    VendoredPathGuard guard = new VendoredPathGuard();
    guard.augmentGitignore(repoDir);
    String afterFirst = Files.readString(repoDir.resolve(".gitignore"));
    long firstNodeModulesCount =
        afterFirst.lines().filter(l -> l.trim().equals("node_modules/")).count();

    // Second call — should be a no-op.
    guard.augmentGitignore(repoDir);
    String afterSecond = Files.readString(repoDir.resolve(".gitignore"));
    long secondNodeModulesCount =
        afterSecond.lines().filter(l -> l.trim().equals("node_modules/")).count();

    assertEquals(1, firstNodeModulesCount, "first call adds exactly one node_modules/ entry");
    assertEquals(
        firstNodeModulesCount,
        secondNodeModulesCount,
        "second call is a no-op; entry count must not grow");
  }

  @Test
  void augmentGitignore_skipsWhenAllPatternsAlreadyPresent(@TempDir Path tmp) throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);
    // Pre-fill with every pattern.
    StringBuilder sb = new StringBuilder();
    for (String dir : VendoredPathGuard.VENDORED_DIRS) sb.append(dir).append("/\n");
    Files.writeString(repoDir.resolve(".gitignore"), sb.toString());
    String before = Files.readString(repoDir.resolve(".gitignore"));

    VendoredPathGuard guard = new VendoredPathGuard();
    guard.augmentGitignore(repoDir);

    assertEquals(before, Files.readString(repoDir.resolve(".gitignore")));
  }

  @Test
  void augmentGitignore_silentlyDropsNonGitDirs(@TempDir Path tmp) throws Exception {
    // No .git/ in tmp — guard should no-op rather than crash.
    new VendoredPathGuard().augmentGitignore(tmp);
    assertFalse(Files.exists(tmp.resolve(".gitignore")));
  }

  private void initBareRepo(Path repoDir) throws IOException, InterruptedException {
    Files.createDirectories(repoDir);
    runCmd(repoDir, "git", "init", "-q", "-b", "main");
    runCmd(repoDir, "git", "config", "user.name", "test");
    runCmd(repoDir, "git", "config", "user.email", "test@test");
  }

  private void runCmd(Path dir, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    p.waitFor();
  }
}
