package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>Clone-local exclusion is tested end-to-end so customer history and tracked files remain
 * byte-for-byte untouched while vendored paths are still ignored.
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

  // installLocalExcludes — idempotency + missing-pattern detection + history integrity

  @Test
  void installLocalExcludes_protectsWithoutChangingCustomerGitignore(@TempDir Path tmp)
      throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);
    // Python-only ignore — exactly the pplai-api shape from b171fa52.
    Files.writeString(repoDir.resolve(".gitignore"), "__pycache__/\nvenv/\n*.pyc\n.env\n");

    VendoredPathGuard guard = new VendoredPathGuard();
    guard.installLocalExcludes(repoDir);

    String body = Files.readString(repoDir.resolve(".git/info/exclude"));
    assertTrue(body.contains("node_modules/"), "node_modules/ should be appended");
    assertTrue(body.contains("dist/"), "dist/ should be appended");
    assertTrue(body.contains(".gradle/"), ".gradle/ should be appended");
    assertTrue(body.contains(">>> ExecuteSpec"), "should mark the appended block");
    assertFalse(
        body.lines().anyMatch(line -> line.strip().equals("__pycache__/")),
        "tracked ignore entries need not be duplicated");
    assertFalse(
        body.lines().anyMatch(line -> line.strip().equals("venv/")),
        "tracked ignore entries need not be duplicated");
    assertEquals(
        "__pycache__/\nvenv/\n*.pyc\n.env\n", Files.readString(repoDir.resolve(".gitignore")));
  }

  @Test
  void installLocalExcludes_isIdempotent(@TempDir Path tmp) throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);
    Files.writeString(repoDir.resolve(".gitignore"), "");

    VendoredPathGuard guard = new VendoredPathGuard();
    guard.installLocalExcludes(repoDir);
    String afterFirst = Files.readString(repoDir.resolve(".git/info/exclude"));
    long firstNodeModulesCount =
        afterFirst.lines().filter(l -> l.trim().equals("node_modules/")).count();

    // Second call — should be a no-op.
    guard.installLocalExcludes(repoDir);
    String afterSecond = Files.readString(repoDir.resolve(".git/info/exclude"));
    long secondNodeModulesCount =
        afterSecond.lines().filter(l -> l.trim().equals("node_modules/")).count();

    assertEquals(1, firstNodeModulesCount, "first call adds exactly one node_modules/ entry");
    assertEquals(
        firstNodeModulesCount,
        secondNodeModulesCount,
        "second call is a no-op; entry count must not grow");
  }

  @Test
  void installLocalExcludes_skipsWhenCustomerIgnoreAlreadyCoversPatterns(@TempDir Path tmp)
      throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);
    // Pre-fill with every pattern.
    StringBuilder sb = new StringBuilder();
    for (String dir : VendoredPathGuard.VENDORED_DIRS) sb.append(dir).append("/\n");
    Files.writeString(repoDir.resolve(".gitignore"), sb.toString());
    String before = Files.readString(repoDir.resolve(".gitignore"));
    String excludeBefore = Files.readString(repoDir.resolve(".git/info/exclude"));

    VendoredPathGuard guard = new VendoredPathGuard();
    guard.installLocalExcludes(repoDir);

    assertEquals(before, Files.readString(repoDir.resolve(".gitignore")));
    assertEquals(excludeBefore, Files.readString(repoDir.resolve(".git/info/exclude")));
  }

  @Test
  void installLocalExcludes_silentlyDropsNonGitDirs(@TempDir Path tmp) throws Exception {
    // No .git/ in tmp — guard should no-op rather than crash.
    new VendoredPathGuard().installLocalExcludes(tmp);
    assertFalse(Files.exists(tmp.resolve(".gitignore")));
  }

  @Test
  void installLocalExcludes_leavesRemoteBaseAncestryAndDiffUntouched(@TempDir Path tmp)
      throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);
    Files.writeString(repoDir.resolve(".gitignore"), "*.log\n");
    Files.writeString(repoDir.resolve("README.md"), "customer baseline\n");
    runCmd(repoDir, "git", "add", "-A");
    runCmd(repoDir, "git", "commit", "-q", "-m", "customer baseline");
    String baseHead = capture(repoDir, "git", "rev-parse", "HEAD").trim();
    runCmd(repoDir, "git", "update-ref", "refs/remotes/origin/main", baseHead);

    new VendoredPathGuard().installLocalExcludes(repoDir);

    assertEquals(baseHead, capture(repoDir, "git", "rev-parse", "HEAD").trim());
    assertEquals(baseHead, capture(repoDir, "git", "merge-base", "origin/main", "HEAD").trim());
    assertEquals("", capture(repoDir, "git", "diff", "--name-only", "origin/main..HEAD").trim());
    assertEquals("", capture(repoDir, "git", "status", "--porcelain").trim());
    assertEquals("*.log\n", Files.readString(repoDir.resolve(".gitignore")));

    Files.createDirectories(repoDir.resolve("node_modules/pkg"));
    Files.writeString(repoDir.resolve("node_modules/pkg/index.js"), "vendored\n");
    runCmd(repoDir, "git", "add", "-A");
    assertEquals("", capture(repoDir, "git", "diff", "--cached", "--name-only").trim());
  }

  // ---------------------------------------------------------------------------------------------
  // RR-649 — the removal target must be the exact vendored subtree, never the first path segment.
  // ---------------------------------------------------------------------------------------------

  @Test
  void firstVendoredSegmentOf_returnsExactSubtree_notFirstPathSegment() {
    // The bug: purgeStagedVendored used rootSegmentOf, so this resolved to "frontend" and the
    // subsequent `git rm --cached -r frontend` unstaged the entire application subtree.
    assertEquals(
        "frontend/node_modules",
        VendoredPathGuard.firstVendoredSegmentOf("frontend/node_modules/pkg/index.js"));
    assertEquals(
        "service/target",
        VendoredPathGuard.firstVendoredSegmentOf("service/target/classes/A.class"));
    assertEquals(
        "repos/api/node_modules",
        VendoredPathGuard.firstVendoredSegmentOf("repos/api/node_modules/foo/index.js"));
  }

  @Test
  void firstVendoredSegmentOf_rootLevelVendoredDirResolvesToItself() {
    assertEquals(
        "node_modules", VendoredPathGuard.firstVendoredSegmentOf("node_modules/pkg/index.js"));
    assertEquals("dist", VendoredPathGuard.firstVendoredSegmentOf("dist/bundle.js"));
  }

  @Test
  void firstVendoredSegmentOf_isCaseInsensitiveLikeIsVendored() {
    // isVendored uses a (?i) regex; the old contains() check was case-SENSITIVE, so a path that
    // passed isVendored fell through to the rootSegmentOf fallback — the same catastrophic
    // widening, reachable from the committed-history scrub path too.
    assertTrue(VendoredPathGuard.isVendored("Frontend/Node_Modules/pkg/index.js"));
    assertEquals(
        "Frontend/Node_Modules",
        VendoredPathGuard.firstVendoredSegmentOf("Frontend/Node_Modules/pkg/index.js"));
  }

  @Test
  void firstVendoredSegmentOf_returnsNullWhenNoSegmentIsVendored() {
    // Refusing beats guessing: a null target is skipped by the caller, never widened.
    assertNull(VendoredPathGuard.firstVendoredSegmentOf("frontend/src/App.tsx"));
    assertNull(VendoredPathGuard.firstVendoredSegmentOf("build.gradle.kts"));
    assertNull(VendoredPathGuard.firstVendoredSegmentOf(null));
    assertNull(VendoredPathGuard.firstVendoredSegmentOf(""));
  }

  @Test
  void isSafeVendoredRemovalTarget_acceptsOnlyPathsEndingInAVendoredSegment() {
    assertTrue(VendoredPathGuard.isSafeVendoredRemovalTarget("frontend/node_modules"));
    assertTrue(VendoredPathGuard.isSafeVendoredRemovalTarget("node_modules"));
    assertTrue(VendoredPathGuard.isSafeVendoredRemovalTarget("service/target"));

    // A non-vendored final segment is exactly the catastrophic case; refuse it.
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("frontend"));
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("src/main/java"));
  }

  @Test
  void isSafeVendoredRemovalTarget_refusesAdversarialTargets() {
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget(null));
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget(""));
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("   "));
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("/etc/node_modules")); // absolute
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("--force")); // option-looking
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("../node_modules")); // traversal
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("a/../node_modules"));
    assertFalse(VendoredPathGuard.isSafeVendoredRemovalTarget("a//node_modules")); // empty segment
  }

  @Test
  void purgeStagedVendored_removesOnlyTheNestedVendoredSubtree(@TempDir Path tmp) throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);

    // Tracked application source under the SAME parent directory as the vendored dir.
    Files.createDirectories(repoDir.resolve("frontend/src"));
    Files.writeString(repoDir.resolve("frontend/src/App.tsx"), "export default 1;\n");
    Files.writeString(repoDir.resolve("frontend/package.json"), "{}\n");
    Files.createDirectories(repoDir.resolve("frontend/node_modules/pkg"));
    Files.writeString(
        repoDir.resolve("frontend/node_modules/pkg/index.js"), "module.exports={};\n");

    runCmd(repoDir, "git", "add", "-A");

    int purged = new VendoredPathGuard().purgeStagedVendored(repoDir);
    assertTrue(purged > 0, "expected the vendored subtree to be purged");

    String staged = capture(repoDir, "git", "diff", "--cached", "--name-only");
    assertTrue(
        staged.contains("frontend/src/App.tsx"),
        "app source was removed from the index! staged=" + staged);
    assertTrue(
        staged.contains("frontend/package.json"),
        "app source was removed from the index! staged=" + staged);
    assertFalse(
        staged.contains("frontend/node_modules/"),
        "vendored content survived in the index! staged=" + staged);

    // The worktree is untouched either way — this guard never deletes files from disk.
    assertTrue(Files.exists(repoDir.resolve("frontend/src/App.tsx")));
    assertTrue(Files.exists(repoDir.resolve("frontend/node_modules/pkg/index.js")));
  }

  // RR-650 — the scrub commit outcome is part of the result contract. Over a real repository the
  // scrub of committed vendored content must report committed()=true and leave a clean tree with
  // the
  // vendored subtree untracked.
  @Test
  void scrubCommittedVendored_reportsCommitAndLeavesACleanTree(@TempDir Path tmp) throws Exception {
    Path repoDir = tmp.resolve("repo");
    initBareRepo(repoDir);

    Files.createDirectories(repoDir.resolve("src"));
    Files.writeString(repoDir.resolve("src/App.tsx"), "export default 1;\n");
    Files.createDirectories(repoDir.resolve("node_modules/pkg"));
    Files.writeString(repoDir.resolve("node_modules/pkg/index.js"), "module.exports={};\n");
    runCmd(repoDir, "git", "add", "-A");
    runCmd(repoDir, "git", "commit", "-q", "-m", "initial with vendored content");

    VendoredPathGuard.ScrubResult result = new VendoredPathGuard().scrubCommittedVendored(repoDir);

    assertTrue(result.changed(), "expected vendored content to be removed");
    assertTrue(result.committed(), "the scrub must land a durable commit");
    // The cleanup is durable: no staged residue, and the vendored path is no longer tracked.
    assertEquals("", capture(repoDir, "git", "status", "--porcelain").trim());
    String tracked = capture(repoDir, "git", "ls-files");
    assertTrue(tracked.contains("src/App.tsx"), "app source must survive: " + tracked);
    assertFalse(tracked.contains("node_modules/"), "vendored content must be gone: " + tracked);
  }

  private String capture(Path dir, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    String out =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    p.waitFor();
    return out;
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
