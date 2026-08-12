package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceIgnoredArtifactGuardTest {

  @TempDir Path workspace;

  @Test
  void removesEveryIgnoredRootSoNoUnattestedDependencyStateCrossesTasks() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(
        workspace.resolve(".gitignore"),
        "target/\ndist/\nscratch/\nbaseline-cache/\n",
        StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.txt"), "source\n");
    git("add", "-A");
    git("commit", "-m", "baseline");

    Files.createDirectories(workspace.resolve("target"));
    Files.writeString(workspace.resolve("target/stale.class"), "stale");
    Files.createDirectories(workspace.resolve("baseline-cache"));
    Files.writeString(workspace.resolve("baseline-cache/dependency.bin"), "keep");

    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = transactions.begin(workspace);
    WorkspaceIgnoredArtifactGuard guard = new WorkspaceIgnoredArtifactGuard(new ObjectMapper());
    WorkspaceIgnoredArtifactGuard.Baseline baseline = guard.prepare(checkpoint, () -> {});

    assertThat(workspace.resolve("target")).doesNotExist();
    assertThat(workspace.resolve("baseline-cache")).doesNotExist();

    Files.createDirectories(workspace.resolve("dist"));
    Files.writeString(workspace.resolve("dist/app.js"), "generated");
    Files.createDirectories(workspace.resolve("scratch"));
    Files.writeString(workspace.resolve("scratch/hidden.txt"), "hidden");

    var removed =
        guard.cleanAttemptArtifacts(checkpoint, baseline.json(), baseline.digest(), () -> {});

    assertThat(workspace.resolve("dist")).doesNotExist();
    assertThat(workspace.resolve("scratch")).doesNotExist();
    assertThat(removed).anyMatch(path -> path.endsWith(":scratch/hidden.txt"));
  }

  @Test
  void cleansIgnoredSiblingsWithoutDeletingNestedRepositories() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve("source.txt"), "source\n");
    git("add", "-A");
    git("commit", "-m", "baseline");

    Path nested = Files.createDirectories(workspace.resolve("repos/api"));
    git(nested, "init", "-b", "main");
    git(nested, "config", "user.email", "test@executespec.local");
    git(nested, "config", "user.name", "ExecuteSpec Test");
    Files.writeString(nested.resolve("api.txt"), "accepted\n");
    git(nested, "add", "-A");
    git(nested, "commit", "-m", "api baseline");
    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);

    Files.writeString(workspace.resolve(".gitignore"), "repos/\n", StandardCharsets.UTF_8);
    Path hiddenSibling = Files.createDirectories(workspace.resolve("repos/cache"));
    Files.writeString(hiddenSibling.resolve("state.bin"), "unattested");

    new WorkspaceIgnoredArtifactGuard(new ObjectMapper()).prepare(checkpoint, () -> {});

    assertThat(nested.resolve("api.txt")).hasContent("accepted\n");
    assertThat(hiddenSibling).doesNotExist();
  }

  @Test
  void cleansDirectoryCollapsedByGitWhenOnlyItsChildrenMatchAnIgnoreRule() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "*.pyc\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.py"), "print('ok')\n", StandardCharsets.UTF_8);
    git("add", "-A");
    git("commit", "-m", "baseline");

    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = transactions.begin(workspace);
    WorkspaceIgnoredArtifactGuard guard = new WorkspaceIgnoredArtifactGuard(new ObjectMapper());
    WorkspaceIgnoredArtifactGuard.Baseline baseline = guard.prepare(checkpoint, () -> {});

    Path cache = Files.createDirectories(workspace.resolve(".executespec/bootstrap/__pycache__"));
    Files.writeString(cache.resolve("test_acceptance.cpython-313.pyc"), "bytecode");

    assertThat(
            guard.cleanAttemptArtifacts(checkpoint, baseline.json(), baseline.digest(), () -> {}))
        .anyMatch(
            path ->
                path.endsWith(
                    ":.executespec/bootstrap/__pycache__/test_acceptance.cpython-313.pyc"));
    assertThat(cache).doesNotExist();
  }

  @Test
  void exactIgnoredInventoryNeverDeletesTrackedSiblingBytes() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "*.pyc\n", StandardCharsets.UTF_8);
    Path bootstrap = Files.createDirectories(workspace.resolve(".executespec/bootstrap"));
    Path tracked = bootstrap.resolve("test_acceptance.py");
    Files.writeString(tracked, "print('tracked')\n", StandardCharsets.UTF_8);
    git("add", "-A");
    git("commit", "-m", "baseline");
    Path cache = Files.createDirectories(bootstrap.resolve("__pycache__"));
    Files.writeString(cache.resolve("test_acceptance.pyc"), "ignored");

    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);
    new WorkspaceIgnoredArtifactGuard(new ObjectMapper()).prepare(checkpoint, () -> {});

    assertThat(tracked).hasContent("print('tracked')\n");
    assertThat(cache).doesNotExist();
  }

  @Test
  void ignoredProductSourceIsAContractErrorInsteadOfSilentDeletion() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "generated/\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.txt"), "source\n", StandardCharsets.UTF_8);
    git("add", "-A");
    git("commit", "-m", "baseline");
    Path generated = Files.createDirectories(workspace.resolve("generated"));
    Path source = generated.resolve("ApiClient.java");
    Files.writeString(source, "class ApiClient {}\n");

    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);

    assertThatThrownBy(
            () ->
                new WorkspaceIgnoredArtifactGuard(new ObjectMapper()).prepare(checkpoint, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("IGNORED_PRODUCT_PATH");
    assertThat(source).exists();
  }

  @Test
  void ignoredSymlinkIsUnlinkedWithoutTouchingItsExternalTarget() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "cache-link\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.txt"), "source\n", StandardCharsets.UTF_8);
    git("add", "-A");
    git("commit", "-m", "baseline");
    Path external = Files.writeString(workspace.getParent().resolve("external-cache.bin"), "keep");
    Path link = workspace.resolve("cache-link");
    Files.createSymbolicLink(link, external);

    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);
    new WorkspaceIgnoredArtifactGuard(new ObjectMapper()).prepare(checkpoint, () -> {});

    assertThat(link).doesNotExist();
    assertThat(external).hasContent("keep");
  }

  @Test
  void ignoredBrokenSymlinkIsStillRemoved() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "broken-cache\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.txt"), "source\n", StandardCharsets.UTF_8);
    git("add", "-A");
    git("commit", "-m", "baseline");
    Path link = workspace.resolve("broken-cache");
    Files.createSymbolicLink(link, Path.of("missing-target"));

    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);
    new WorkspaceIgnoredArtifactGuard(new ObjectMapper()).prepare(checkpoint, () -> {});

    assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
  }

  @Test
  void rejectsCorruptedBaselineReceiptBeforeDeletingIgnoredBytes() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "scratch/\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.txt"), "source\n", StandardCharsets.UTF_8);
    git("add", "-A");
    git("commit", "-m", "baseline");

    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);
    WorkspaceIgnoredArtifactGuard guard = new WorkspaceIgnoredArtifactGuard(new ObjectMapper());
    WorkspaceIgnoredArtifactGuard.Baseline baseline = guard.capture(checkpoint, () -> {});
    Path ignored = Files.createDirectories(workspace.resolve("scratch"));
    Files.writeString(ignored.resolve("unattested.bin"), "keep-until-receipt-valid");
    String corrupted =
        (baseline.digest().startsWith("0") ? "1" : "0") + baseline.digest().substring(1);

    assertThatThrownBy(
            () -> guard.cleanAttemptArtifacts(checkpoint, baseline.json(), corrupted, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_IGNORED_BASELINE_DIGEST_MISMATCH");
    assertThat(ignored.resolve("unattested.bin")).exists();
  }

  @Test
  void acceptsJsonbEquivalentBaselineTextWithoutWeakeningDigestBinding() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "scratch/\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.txt"), "source\n", StandardCharsets.UTF_8);
    git("add", "-A");
    git("commit", "-m", "baseline");

    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);
    ObjectMapper mapper = new ObjectMapper();
    WorkspaceIgnoredArtifactGuard guard = new WorkspaceIgnoredArtifactGuard(mapper);
    WorkspaceIgnoredArtifactGuard.Baseline baseline = guard.capture(checkpoint, () -> {});
    String normalizedByTransport =
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(mapper.readTree(baseline.json()));
    Path ignored = Files.createDirectories(workspace.resolve("scratch"));
    Files.writeString(ignored.resolve("unattested.bin"), "remove-after-valid-receipt");

    guard.cleanAttemptArtifacts(checkpoint, normalizedByTransport, baseline.digest(), () -> {});

    assertThat(ignored).doesNotExist();
  }

  private String git(String... args) throws Exception {
    return git(workspace, args);
  }

  private static String git(Path directory, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(java.util.List.of(args));
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
    return output.trim();
  }
}
