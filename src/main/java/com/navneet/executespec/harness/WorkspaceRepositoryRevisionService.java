package com.navneet.executespec.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.security.SafeWorkspacePath;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns exact repository branch and revision evidence for run workspaces.
 *
 * <p>Clone lifecycle remains in {@link WorkspaceInitService}; this collaborator keeps branch
 * admission, immutable baseline persistence, exact-revision checkout, and publication-head fencing
 * in one focused boundary.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceRepositoryRevisionService {

  private final AgentProperties props;
  private final RunRepoSnapshotRepository snapshotRepo;

  static String repositoryFullName(String repoUrl) {
    if (repoUrl == null || repoUrl.isBlank()) {
      throw new IllegalArgumentException("repository URL is required");
    }
    String fullName = repoUrl.replaceFirst("^https?://[^/]+/", "");
    if (fullName.endsWith(".git")) fullName = fullName.substring(0, fullName.length() - 4);
    if (!fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
      throw new IllegalArgumentException("repository URL does not contain a safe owner/name");
    }
    return fullName;
  }

  void requireExpectedBranch(Path repoDir, String expectedBranch, String repoFullName) {
    if (expectedBranch == null || expectedBranch.isBlank()) return;
    try {
      ProcessBuilder pb =
          new ProcessBuilder("git", "branch", "--show-current").directory(repoDir.toFile());
      pb.redirectErrorStream(true);
      Process process = pb.start();
      String actual = new String(process.getInputStream().readAllBytes()).trim();
      int exit = process.waitFor();
      if (exit != 0 || !expectedBranch.equals(actual)) {
        throw new IllegalStateException(
            "repository branch mismatch for "
                + repoFullName
                + ": expected "
                + expectedBranch
                + " but workspace is on "
                + (actual.isBlank() ? "a detached or unknown branch" : actual));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "repository branch verification interrupted for " + repoFullName, interrupted);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "repository branch could not be verified for " + repoFullName, failure);
    }
  }

  void persistCloneSnapshot(
      String runId, WorkspaceInitService.RepoToClone repository, Path target) {
    try {
      String sha = captureHeadSha(target);
      if (sha == null || sha.isBlank()) {
        throw new IllegalStateException(
            "repository has no committed revision: " + repository.fullName());
      }
      String branch = captureCurrentBranch(target);
      if (branch == null || branch.isBlank()) {
        throw new IllegalStateException(
            "could not capture checked-out branch for " + repository.fullName());
      }
      RunRepoSnapshot existing =
          snapshotRepo.findByRunId(runId).stream()
              .filter(snapshot -> repository.fullName().equals(snapshot.getRepoFullName()))
              .findFirst()
              .orElse(null);
      if (existing != null) {
        if (!sha.equalsIgnoreCase(existing.getCommitSha())
            || !branch.equals(existing.getBranch())) {
          throw new IllegalStateException(
              "persisted baseline does not match checked-out repository "
                  + repository.fullName()
                  + ": expected "
                  + existing.getBranch()
                  + "@"
                  + existing.getCommitSha()
                  + " but found "
                  + branch
                  + "@"
                  + sha);
        }
        return;
      }
      RunRepoSnapshot snapshot =
          RunRepoSnapshot.builder()
              .runId(runId)
              .repoId(repository.repoId())
              .repoFullName(repository.fullName())
              .branch(branch)
              .commitSha(sha)
              .build();
      snapshotRepo.save(snapshot);
      log.debug(
          "Snapshot saved run={} repo={} branch={} sha={}",
          runId,
          repository.fullName(),
          branch,
          sha);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "could not persist repository baseline for " + repository.fullName(), failure);
    }
  }

  public void requirePublicationHead(
      String runId, String publicationRepoFullName, String expectedHeadSha) {
    if (expectedHeadSha == null || !expectedHeadSha.matches("[0-9a-fA-F]{40}")) {
      throw new IllegalStateException("existing-PR publication head SHA is missing or malformed");
    }
    Path workspace = runWorkspacePath(runId);
    Path repository = workspace;
    if (publicationRepoFullName != null && !publicationRepoFullName.isBlank()) {
      Path projectRepos = workspace.resolve("repos");
      Path projectClone =
          projectRepos.resolve(WorkspaceInitService.repoDirectoryName(publicationRepoFullName));
      if (Files.isDirectory(projectRepos)) {
        if (!Files.isDirectory(projectClone.resolve(".git"))) {
          throw new IllegalStateException(
              "existing-PR project repository clone is unavailable for " + publicationRepoFullName);
        }
        repository = projectClone;
      }
    }
    if (!Files.isDirectory(repository.resolve(".git"))) {
      throw new IllegalStateException("existing-PR workspace repository is unavailable");
    }
    try {
      String actualHead = captureHeadSha(repository);
      if (actualHead == null || !expectedHeadSha.equalsIgnoreCase(actualHead)) {
        throw new IllegalStateException(
            "pull-request head changed before workspace admission: expected "
                + expectedHeadSha
                + " but cloned "
                + (actualHead == null ? "an unknown revision" : actualHead));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "pull-request head verification was interrupted", interrupted);
    } catch (IOException failure) {
      throw new IllegalStateException("pull-request head could not be verified", failure);
    }
  }

  public boolean checkoutExactCommit(String runId, String commitSha) {
    if (commitSha == null || commitSha.isBlank()) return false;
    Path workspace = runWorkspacePath(runId);
    try {
      runGit(workspace, "checkout", "--detach", commitSha);
      String head = captureHeadSha(workspace);
      boolean matched = head != null && commitSha.equalsIgnoreCase(head.trim());
      if (!matched) {
        log.warn(
            "RR-641 — workspace {} could not be pinned to {} (HEAD={})", runId, commitSha, head);
      }
      return matched;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.warn("RR-641 — checkoutExactCommit interrupted for {} @ {}", runId, commitSha);
      return false;
    } catch (IOException failure) {
      log.warn(
          "RR-641 — checkoutExactCommit failed for {} @ {}: {}",
          runId,
          commitSha,
          failure.getMessage());
      return false;
    }
  }

  private Path runWorkspacePath(String runId) {
    SafeWorkspacePath.requireSafeRelativePosixPath(runId);
    return Path.of(props.getWorkspace(), runId).toAbsolutePath().normalize();
  }

  private String captureHeadSha(Path repoDir) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repoDir.toFile());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    int exit = process.waitFor();
    if (exit != 0) {
      log.warn("git rev-parse HEAD exit {} in {}: {}", exit, repoDir, output);
      return null;
    }
    return output;
  }

  private String captureCurrentBranch(Path repoDir) throws IOException, InterruptedException {
    ProcessBuilder pb =
        new ProcessBuilder("git", "branch", "--show-current").directory(repoDir.toFile());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String branch = new String(process.getInputStream().readAllBytes()).trim();
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git branch --show-current exited with " + exit);
    }
    return branch;
  }

  private static void runGit(Path directory, String... arguments)
      throws IOException, InterruptedException {
    String[] command = new String[arguments.length + 1];
    command[0] = "git";
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    ProcessBuilder pb = new ProcessBuilder(command).directory(directory.toFile());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    process.getInputStream().readAllBytes();
    process.waitFor();
  }
}
