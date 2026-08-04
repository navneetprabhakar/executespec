package com.navneet.executespec.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.security.SafeWorkspacePath;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Fail-closed, Git-tracked-only workspace copy used by conversation iteration runs. */
@RequiredArgsConstructor
@Slf4j
final class RequiredIterationWorkspaceCopier {

  private final AgentProperties properties;
  private final WorkspaceRestoreService restoreService;
  private final RunRepoSnapshotRepository snapshotRepository;

  void copy(String sourceRunId, String targetRunId) throws Exception {
    Path source = workspace(sourceRunId);
    Path target = workspace(targetRunId);
    if (!Files.isDirectory(source.resolve(".git"))) {
      try {
        source = restoreService.ensureWorkspaceAvailable(sourceRunId);
      } catch (Exception exception) {
        throw new IllegalStateException(
            "source workspace is unavailable for iteration " + sourceRunId, exception);
      }
    }
    if (!Files.isDirectory(source.resolve(".git"))) {
      throw new IllegalStateException(
          "source workspace is not a Git repository for iteration " + sourceRunId);
    }
    if (Files.exists(target) && !Files.isDirectory(target.resolve(".git"))) {
      throw new IllegalStateException(
          "target iteration workspace already exists without valid Git metadata");
    }

    int copied = cloneTrackedRepository(source, target);
    Path sourceRepos = source.resolve("repos");
    if (Files.isDirectory(sourceRepos)) {
      try (var children = Files.list(sourceRepos)) {
        for (Path child : children.filter(Files::isDirectory).toList()) {
          if (!Files.isDirectory(child.resolve(".git"))) continue;
          Path targetRepo = target.resolve("repos").resolve(child.getFileName().toString());
          copied += cloneTrackedRepository(child, targetRepo);
          excludeAgentContext(targetRepo);
        }
      }
    }
    if (copied == 0) {
      throw new IllegalStateException(
          "source workspace contains no Git-tracked files for iteration " + sourceRunId);
    }

    List<RunRepoSnapshot> sourceSnapshots = snapshotRepository.findByRunId(sourceRunId);
    for (RunRepoSnapshot sourceSnapshot : sourceSnapshots) {
      Path sourceNested =
          source
              .resolve("repos")
              .resolve(WorkspaceInitService.repoDirectoryName(sourceSnapshot.getRepoFullName()));
      Path targetRepo;
      if (sourceSnapshot.getRepoId() != null) {
        if (!Files.isDirectory(sourceNested)) {
          throw new IllegalStateException(
              "source repository workspace unavailable after restore: "
                  + sourceSnapshot.getRepoFullName());
        }
        if (!Files.isDirectory(sourceNested.resolve(".git"))) {
          throw new IllegalStateException(
              "source repository Git metadata unavailable after restore: "
                  + sourceSnapshot.getRepoFullName());
        }
        targetRepo =
            target
                .resolve("repos")
                .resolve(WorkspaceInitService.repoDirectoryName(sourceSnapshot.getRepoFullName()));
      } else if (sourceSnapshots.size() == 1 && Files.isDirectory(source.resolve(".git"))) {
        targetRepo = target;
      } else {
        continue;
      }
      if (!Files.isDirectory(targetRepo.resolve(".git"))) continue;
      checkoutBaselineBranch(targetRepo, sourceSnapshot);
      persistSnapshot(targetRunId, sourceSnapshot, targetRepo);
    }
    log.info(
        "Iteration workspace for run {} initialized from source run {} ({} tracked files)",
        targetRunId,
        sourceRunId,
        copied);
  }

  private int cloneTrackedRepository(Path source, Path target)
      throws IOException, InterruptedException {
    if (!Files.isDirectory(target.resolve(".git"))) {
      if (Files.isDirectory(target)) {
        try (var entries = Files.list(target)) {
          if (entries.findAny().isPresent()) {
            throw new IllegalStateException(
                "iteration repository target already contains non-Git files: " + target);
          }
        }
        Files.delete(target);
      }
      Files.createDirectories(target.getParent());
      runGitStrict(
          target.getParent(),
          "git",
          "clone",
          "--no-hardlinks",
          "--no-local",
          source.toString(),
          target.toString());
    }

    List<String> tracked = gitTrackedFiles(source);
    for (String relative : tracked) {
      SafeWorkspacePath.requireSafeRelativePosixPath(relative);
      Path sourceFile = source.resolve(relative).normalize();
      Path targetFile = target.resolve(relative).normalize();
      if (!sourceFile.startsWith(source) || !targetFile.startsWith(target)) {
        throw new SecurityException("tracked path escaped iteration workspace: " + relative);
      }
      if (!Files.exists(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(targetFile);
        continue;
      }
      if (Files.isSymbolicLink(sourceFile)) {
        throw new SecurityException(
            "symbolic links are not admitted into iteration workspaces: " + relative);
      }
      Files.createDirectories(targetFile.getParent());
      Files.copy(
          sourceFile,
          targetFile,
          LinkOption.NOFOLLOW_LINKS,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);
    }

    String originalOrigin = gitOutput(source, "git", "remote", "get-url", "origin");
    if (originalOrigin == null || originalOrigin.isBlank()) {
      runGit(target, "git", "remote", "remove", "origin");
    } else {
      runGitStrict(target, "git", "remote", "set-url", "origin", originalOrigin.trim());
    }
    runGitStrict(target, "git", "config", "user.email", "agent@executespec.local");
    runGitStrict(target, "git", "config", "user.name", "ExecuteSpec Agent");
    runGitStrict(target, "git", "add", "-A");
    runGitStrict(
        target, "git", "commit", "--allow-empty", "-m", "init: iteration workspace snapshot");
    return (int)
        tracked.stream()
            .map(source::resolve)
            .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            .count();
  }

  /**
   * Re-anchors the copied repository on the source snapshot's baseline branch. Auto-push leaves the
   * source checkout on its publication branch (`executespec/<runId>`), and the local clone follows
   * that HEAD, so without this the child's preflight branch verification fails deterministically
   * for every project run that published. The working tree and HEAD commit are unchanged — only the
   * branch name is restored to the recorded baseline.
   */
  private static void checkoutBaselineBranch(Path repository, RunRepoSnapshot snapshot)
      throws IOException, InterruptedException {
    String branch = snapshot.getBranch();
    if (branch == null || branch.isBlank()) return;
    String trimmed = branch.trim();
    if (trimmed.startsWith("-") || trimmed.contains("..") || !trimmed.matches("[A-Za-z0-9._/-]+")) {
      throw new IllegalStateException(
          "iteration baseline branch is not a safe Git branch name for "
              + snapshot.getRepoFullName());
    }
    runGitStrict(repository, "git", "checkout", "-B", trimmed);
  }

  private void persistSnapshot(String runId, RunRepoSnapshot source, Path target)
      throws IOException, InterruptedException {
    boolean exists =
        snapshotRepository.findByRunId(runId).stream()
            .anyMatch(snapshot -> source.getRepoFullName().equals(snapshot.getRepoFullName()));
    if (exists) return;
    String sha = gitOutput(target, "git", "rev-parse", "HEAD");
    if (sha == null || sha.isBlank()) {
      throw new IllegalStateException(
          "could not capture iteration baseline SHA for " + source.getRepoFullName());
    }
    snapshotRepository.save(
        RunRepoSnapshot.builder()
            .runId(runId)
            .repoId(source.getRepoId())
            .repoFullName(source.getRepoFullName())
            .branch(source.getBranch())
            .commitSha(sha)
            .build());
  }

  private Path workspace(String runId) {
    SafeWorkspacePath.requireSafeRelativePosixPath(runId);
    return Path.of(properties.getWorkspace(), runId).toAbsolutePath().normalize();
  }

  private static List<String> gitTrackedFiles(Path repository)
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("git", "ls-files", "-z")
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
    byte[] output = process.getInputStream().readAllBytes();
    if (process.waitFor() != 0) {
      throw new IllegalStateException(
          "could not enumerate tracked files in iteration source " + repository);
    }
    List<String> files = new ArrayList<>();
    for (String file : new String(output, StandardCharsets.UTF_8).split("\u0000", -1)) {
      if (!file.isBlank()) files.add(file);
    }
    return files;
  }

  private static String gitOutput(Path repository, String... command)
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    return process.waitFor() == 0 ? output : null;
  }

  private static void runGit(Path directory, String... command)
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    process.waitFor();
  }

  private static void runGitStrict(Path directory, String... command)
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(
          "git command [" + String.join(" ", command) + "] exited with " + exit + ": " + output);
    }
  }

  private static void excludeAgentContext(Path repository) {
    try {
      Path info = repository.resolve(".git").resolve("info");
      Files.createDirectories(info);
      Path exclude = info.resolve("exclude");
      String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
      if (existing.lines().noneMatch(line -> line.strip().equals(".agent-context/"))) {
        String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
        Files.writeString(exclude, existing + separator + ".agent-context/\n");
      }
    } catch (IOException exception) {
      log.warn(
          "Could not exclude iteration evidence in {}: {}", repository, exception.getMessage());
    }
  }
}
