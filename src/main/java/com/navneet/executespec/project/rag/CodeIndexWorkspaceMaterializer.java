package com.navneet.executespec.project.rag;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Safely materializes the canonical project/repository/revision clone used by read-only tools. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeIndexWorkspaceMaterializer {

  private final WorkspaceInitService workspaceInitService;
  private final ConcurrentHashMap<String, CompletableFuture<Path>> flights =
      new ConcurrentHashMap<>();

  public Path materialize(
      String projectId,
      ProjectRepo repository,
      String credentialUsername,
      String credentialSecret) {
    String workspaceId = CodeIndexWorkspaceIdentity.workspaceId(projectId, repository);
    Path ready = readyWorkspace(workspaceId, repository);
    if (ready != null) return ready;

    CompletableFuture<Path> mine = new CompletableFuture<>();
    CompletableFuture<Path> active = flights.putIfAbsent(workspaceId, mine);
    if (active != null) return await(active);
    try {
      Path result =
          materializeAtomically(workspaceId, repository, credentialUsername, credentialSecret);
      mine.complete(result);
      return result;
    } catch (RuntimeException failure) {
      mine.completeExceptionally(failure);
      throw failure;
    } finally {
      flights.remove(workspaceId, mine);
    }
  }

  private Path materializeAtomically(
      String workspaceId, ProjectRepo repository, String username, String secret) {
    Path ready = readyWorkspace(workspaceId, repository);
    if (ready != null) return ready;

    Path target = workspaceInitService.runWorkspacePath(workspaceId);
    String temporaryId = workspaceId + "-tmp-" + UUID.randomUUID();
    Path temporary = workspaceInitService.runWorkspacePath(temporaryId);
    try {
      deleteTree(target);
      String cloneUrl = cloneUrl(repository);
      if (cloneUrl == null) throw new MaterializationException(Code.SOURCE_CLONE_URL_UNAVAILABLE);
      workspaceInitService.cloneReadOnlyRepository(temporaryId, cloneUrl, username, secret);
      if (!isRepository(temporary)) {
        throw new MaterializationException(Code.SOURCE_CLONE_INCOMPLETE);
      }
      String revision = CodeIndexWorkspaceIdentity.sourceRevision(repository);
      if (isFullCommitSha(revision)
          && !workspaceInitService.checkoutExactCommit(temporaryId, revision)) {
        throw new MaterializationException(Code.SOURCE_REVISION_UNAVAILABLE);
      }
      moveAtomically(temporary, target);
      return target;
    } catch (MaterializationException known) {
      throw known;
    } catch (Exception failure) {
      throw new MaterializationException(Code.SOURCE_CLONE_FAILED, failure);
    } finally {
      deleteTree(temporary);
    }
  }

  private Path readyWorkspace(String workspaceId, ProjectRepo repository) {
    Path candidate = workspaceInitService.runWorkspacePath(workspaceId);
    if (!isRepository(candidate)) return null;
    String revision = CodeIndexWorkspaceIdentity.sourceRevision(repository);
    if (isFullCommitSha(revision) && !headMatches(candidate, revision)) {
      return null;
    }
    return candidate;
  }

  private static boolean isRepository(Path root) {
    return Files.isDirectory(root, NOFOLLOW_LINKS)
        && Files.isDirectory(root.resolve(".git"), NOFOLLOW_LINKS)
        && !Files.isSymbolicLink(root)
        && !Files.isSymbolicLink(root.resolve(".git"));
  }

  private static String cloneUrl(ProjectRepo repository) {
    if (repository.getScmCloneUrl() != null && !repository.getScmCloneUrl().isBlank()) {
      return repository.getScmCloneUrl();
    }
    String fullName = repository.getGithubRepoFullName();
    return fullName == null || fullName.isBlank()
        ? null
        : "https://github.com/" + fullName + ".git";
  }

  private static boolean isFullCommitSha(String revision) {
    return revision != null && revision.matches("(?i)[0-9a-f]{40}");
  }

  private static boolean headMatches(Path repository, String revision) {
    try {
      Process process =
          new ProcessBuilder("git", "rev-parse", "HEAD")
              .directory(repository.toFile())
              .redirectErrorStream(true)
              .start();
      String head = new String(process.getInputStream().readAllBytes()).trim();
      return process.waitFor() == 0 && revision.equalsIgnoreCase(head);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException unavailable) {
      return false;
    }
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, target);
    }
  }

  private static void deleteTree(Path root) {
    if (root == null || !Files.exists(root, NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(CodeIndexWorkspaceMaterializer::deleteQuietly);
    } catch (IOException cleanupFailure) {
      log.warn(
          "Repository materialization cleanup could not walk target={} type={}",
          root.getFileName(),
          cleanupFailure.getClass().getSimpleName());
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException cleanupFailure) {
      log.debug(
          "Repository materialization cleanup could not delete target={} type={}",
          path.getFileName(),
          cleanupFailure.getClass().getSimpleName());
    }
  }

  private static Path await(CompletableFuture<Path> active) {
    try {
      return active.join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw failure;
    }
  }

  public enum Code {
    SOURCE_CLONE_URL_UNAVAILABLE,
    SOURCE_CLONE_INCOMPLETE,
    SOURCE_REVISION_UNAVAILABLE,
    SOURCE_CLONE_FAILED
  }

  public static final class MaterializationException extends RuntimeException {
    private final Code code;

    public MaterializationException(Code code) {
      super(code.name());
      this.code = code;
    }

    public MaterializationException(Code code, Throwable cause) {
      super(code.name(), cause);
      this.code = code;
    }

    public Code code() {
      return code;
    }
  }
}
