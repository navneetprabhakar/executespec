package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Model-facing-contract regressions for the 2026-08-07 worker-tool audit. Each test pins what a
 * tool RETURNS TO THE MODEL, not its internals — the audit's whole thesis is that a tool silently
 * withholding information (a partial search that looks complete, a failing suite that looks like
 * "no runner", an overwritten output file) makes the model write duplicate/wrong code.
 */
@DisplayName("Worker tool truthfulness (2026-08-07 audit fixes)")
class WorkerToolTruthfulnessTest {

  private final ObjectMapper json = new ObjectMapper();

  // --- S8: ToolOutput spill files must never overwrite each other
  // ---------------------------------

  @Test
  @DisplayName(
      "ToolOutput.persistFull — many same-tool spills yield distinct, non-overwriting files")
  void toolOutputSpillsDoNotCollide(@TempDir Path workspace) throws Exception {
    ExecutionContext ctx = new ExecutionContext("run", "task", workspace);
    Set<String> paths = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      String content = "build log #" + i + " unique-marker-" + i;
      String rel = ToolOutput.persistFull(ctx, "buildProject", content);
      assertThat(rel).isNotNull();
      paths.add(rel);
      // The readFile path handed to the model must resolve to THIS call's content, not a sibling's.
      assertThat(Files.readString(workspace.resolve(rel))).isEqualTo(content);
    }
    // 50 same-tool spills → 50 distinct files. A millisecond-timestamp name collided and truncated.
    assertThat(paths).hasSize(50);
  }

  // --- S4: GlobTool must disclose the 500-result cap ---------------------------------------------

  @Test
  @DisplayName("GlobTool — a listing at the 500 cap is marked truncated, not presented as complete")
  void globDisclosesTruncation(@TempDir Path workspace) throws Exception {
    for (int i = 0; i < 510; i++) {
      Files.writeString(workspace.resolve("f" + i + ".txt"), "x");
    }
    GlobTool tool = new GlobTool();
    ToolResult result =
        tool.execute(
            json.readTree("{\"pattern\":\"*.txt\"}"),
            new ExecutionContext("run", "task", workspace));

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    String content = ((ToolResult.Ok) result).content();
    assertThat(content).contains("truncated at 500");
  }

  // --- R3b: FileSystemTool.readFile must not report a legacy-encoded file as unreadable
  // -----------

  @Test
  @DisplayName(
      "FileSystemTool.readFile — a non-UTF-8 source file is read, not reported 'Cannot read'")
  void fileSystemToolReadsNonUtf8(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("run"));
    byte[] latin1 =
        ("public class Legacy { /* café */ String token = \"present\"; }\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    Files.write(root.resolve("run/Legacy.java"), latin1);

    FileSystemTool fs = new FileSystemTool(root.toString(), (FileLockTool) null, null);
    String out = fs.readFile("run", "Legacy.java");

    assertThat(out).contains("token = \"present\"").doesNotContain("Cannot read");
  }

  // --- S1: SearchFilesTool (ripgrep path) must search gitignored files (host-independent)
  // ---------

  @Test
  @DisplayName("searchFiles — a gitignored file is still searchable (rg --no-ignore, matches walk)")
  void searchFilesFindsGitignoredContent(@TempDir Path workspace) throws Exception {
    assumeRipgrep();
    Files.writeString(workspace.resolve(".gitignore"), "ignored.txt\n");
    Files.writeString(workspace.resolve("ignored.txt"), "uniqueNeedleToken here\n");
    SearchFilesTool tool = new SearchFilesTool(new RepositorySearchService());

    ToolResult result =
        tool.execute(
            json.readTree("{\"pattern\":\"uniqueNeedleToken\",\"mode\":\"EXACT\"}"),
            new ExecutionContext("run", "task", workspace));

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    // Without --no-ignore, rg honoured .gitignore and returned "(no matches)" while the Java-walk
    // fallback would have found it — a host-dependent answer. Now both find it.
    assertThat(((ToolResult.Ok) result).content()).contains("ignored.txt");
  }

  // --- S5: GitDiffTool must reveal untracked (new) files the worker created
  // -----------------------

  @Test
  @DisplayName("gitDiff — untracked new files are listed, not silently omitted as '(no changes)'")
  void gitDiffListsUntrackedFiles(@TempDir Path workspace) throws Exception {
    assumeGit();
    git(workspace, "init", "-q");
    git(workspace, "config", "user.email", "t@t.local");
    git(workspace, "config", "user.name", "t");
    Files.writeString(workspace.resolve("committed.txt"), "base\n");
    git(workspace, "add", "-A");
    git(workspace, "commit", "-q", "-m", "base");
    // The worker's writeFile creates a NEW file but never git-adds it.
    Files.writeString(workspace.resolve("brand_new_source.py"), "print('new')\n");

    GitDiffTool tool = new GitDiffTool();
    ToolResult result =
        tool.execute(json.createObjectNode(), new ExecutionContext("run", "task", workspace));

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    String content = ((ToolResult.Ok) result).content();
    assertThat(content).contains("brand_new_source.py").contains("untracked");
  }

  private static void assumeRipgrep() throws InterruptedException {
    try {
      Process p = new ProcessBuilder("rg", "--version").start();
      Assumptions.assumeTrue(p.waitFor() == 0, "ripgrep is not installed");
    } catch (IOException e) {
      Assumptions.assumeTrue(false, "ripgrep is not installed");
    }
  }

  private static void assumeGit() throws InterruptedException {
    try {
      Process p = new ProcessBuilder("git", "--version").start();
      Assumptions.assumeTrue(p.waitFor() == 0, "git is not installed");
    } catch (IOException e) {
      Assumptions.assumeTrue(false, "git is not installed");
    }
  }

  private static void git(Path dir, String... args) throws IOException, InterruptedException {
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    for (String a : args) cmd.add(a);
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    p.waitFor();
  }
}
