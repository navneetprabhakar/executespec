package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the multi-repo listing helper introduced for Fix E (run a076c876 architecture=0 diagnosis,
 * 2026-05-03).
 *
 * <p>Diagnosis: {@code AccuracyAssessmentService} called {@code listFilesRecursive(runId, 400)}
 * which sorts alphabetically and truncates. For run a076c876's 3-repo workspace the budget was
 * filled by the alphabetically-first repo {@code pplai-api} (28 files, then deeper subtrees adding
 * more), then {@code frontend} (14 files), so {@code pplai-frontend-mobile} (12 files) never
 * appeared in the listing. The accuracy LLM "concluded" the mobile repo was absent and emitted 5
 * critical MISSING_FEATURE issues → architectureScore = clamp(50 - 60) = 0.
 *
 * <p>This test pins the per-repo helper:
 *
 * <ul>
 *   <li>Each {@code repos/<name>/} subdir gets its own budget — alphabetical truncation can't
 *       starve later repos
 *   <li>The output is sectioned by repo so the LLM can see which repo each file belongs to
 *   <li>Single-repo workspaces fall through to the legacy flat behaviour (back-compat)
 * </ul>
 */
class FileSystemToolPerRepoListingTest {

  /** Build a FileSystemTool pointed at {@code workspaceRoot}. */
  private FileSystemTool toolFor(Path workspaceRoot) {
    return new FileSystemTool(workspaceRoot.toString(), (FileLockTool) null, null);
  }

  /** Make a runId-rooted workspace with {@code repos/<each>/} populated. */
  private Path mkMultiRepoWs(Path tempDir, String runId, String... repoNames) throws Exception {
    Path runDir = tempDir.resolve(runId);
    Files.createDirectories(runDir.resolve("repos"));
    Files.writeString(runDir.resolve("README.md"), "root readme");
    for (String name : repoNames) {
      Path repo = runDir.resolve("repos").resolve(name);
      Files.createDirectories(repo.resolve("src"));
      Files.writeString(repo.resolve("README.md"), "readme for " + name);
      Files.writeString(repo.resolve("src/main.py"), "print('hello from " + name + "')");
      Files.writeString(repo.resolve("src/utils.py"), "# utils for " + name);
    }
    return runDir;
  }

  @Test
  void multi_repo_workspace_lists_every_repo_in_its_own_section(@TempDir Path tempDir)
      throws Exception {
    String runId = "test-multi";
    mkMultiRepoWs(tempDir, runId, "alpha-api", "beta-frontend", "gamma-mobile");
    FileSystemTool tool = toolFor(tempDir);

    String listing = tool.listFilesRecursivePerRepo(runId, 50, 500);

    // Every repo should appear with a section header — without per-repo budgeting,
    // the alphabetical sort would have put gamma at the bottom and a tight totalMax
    // could starve it. The header makes the LLM aware of the partition.
    assertThat(listing).contains("=== repos/alpha-api/ ===");
    assertThat(listing).contains("=== repos/beta-frontend/ ===");
    assertThat(listing).contains("=== repos/gamma-mobile/ ===");

    // Each repo's representative files should be visible.
    assertThat(listing)
        .contains("repos/alpha-api/src/main.py")
        .contains("repos/beta-frontend/src/main.py")
        .contains("repos/gamma-mobile/src/main.py");

    // Root-level README.md gets the dedicated "workspace root" section.
    assertThat(listing).contains("=== workspace root (excluding repos/) ===");
    assertThat(listing).contains("README.md");
  }

  @Test
  void per_repo_budget_caps_individual_repo_listings(@TempDir Path tempDir) throws Exception {
    String runId = "test-cap";
    Path runDir = tempDir.resolve(runId);
    Path repoA = runDir.resolve("repos/big-repo");
    Files.createDirectories(repoA);
    // 20 files in big-repo — should be truncated at perRepoMax=5.
    for (int i = 0; i < 20; i++) {
      Files.writeString(repoA.resolve("file" + i + ".py"), "x");
    }
    Path repoB = runDir.resolve("repos/small-repo");
    Files.createDirectories(repoB);
    Files.writeString(repoB.resolve("only.py"), "x");
    FileSystemTool tool = toolFor(tempDir);

    String listing = tool.listFilesRecursivePerRepo(runId, 5, 500);

    // big-repo got its budget capped — should mention truncation.
    assertThat(listing).contains("(truncated at 5 entries for this repo)");
    // small-repo wasn't starved — its file is visible.
    assertThat(listing).contains("repos/small-repo/only.py");
  }

  @Test
  void single_repo_workspace_falls_back_to_flat_listing(@TempDir Path tempDir) throws Exception {
    // Workspace with a SINGLE repos/ entry should use the flat path (legacy behaviour).
    String runId = "test-single";
    Path runDir = tempDir.resolve(runId);
    Path repo = runDir.resolve("repos/only-repo");
    Files.createDirectories(repo);
    Files.writeString(repo.resolve("main.py"), "x");
    FileSystemTool tool = toolFor(tempDir);

    String listing = tool.listFilesRecursivePerRepo(runId, 50, 500);

    // No per-repo headers when there's only one repo — the flat listing path was taken.
    assertThat(listing).doesNotContain("=== workspace root").doesNotContain("=== repos/");
    assertThat(listing).contains("repos/only-repo/main.py");
  }

  @Test
  void no_repos_dir_falls_back_to_flat_listing(@TempDir Path tempDir) throws Exception {
    // Greenfield workspace with no repos/ subdir at all.
    String runId = "test-greenfield";
    Path runDir = tempDir.resolve(runId);
    Files.createDirectories(runDir.resolve("src"));
    Files.writeString(runDir.resolve("src/main.py"), "x");
    FileSystemTool tool = toolFor(tempDir);

    String listing = tool.listFilesRecursivePerRepo(runId, 50, 500);

    assertThat(listing).contains("src/main.py").doesNotContain("=== repos/");
  }

  @Test
  void total_cap_truncates_remaining_repos_with_clear_message(@TempDir Path tempDir)
      throws Exception {
    String runId = "test-totalcap";
    mkMultiRepoWs(tempDir, runId, "a", "b", "c", "d");
    FileSystemTool tool = toolFor(tempDir);

    // 4 repos × 4 files each + 1 root file = 17 entries; cap at 8 should hit the limit
    // partway through.
    String listing = tool.listFilesRecursivePerRepo(runId, 50, 8);

    assertThat(listing).contains("reached total cap").contains("repos truncated");
  }
}
