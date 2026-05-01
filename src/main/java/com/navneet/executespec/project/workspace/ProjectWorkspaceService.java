package com.navneet.executespec.project.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

import com.navneet.executespec.config.AgentProperties;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the per-project workspace at {@code <agent.workspace>/projects/<projectId>/}.
 *
 * <p>Layout:
 *
 * <pre>
 *   projects/&lt;projectId&gt;/
 *     codemaps/&lt;repo-short-name&gt;.md
 *     architecture.md
 *     summary.md
 *     docs/&lt;original-filename&gt;          (P2)
 *     .meta/*.json
 *     .git/
 * </pre>
 *
 * <p>All writes go through {@link #writeAndCommit}, which holds a Redis-backed per-project lock for
 * the duration of the git commit so concurrent analyse requests never race on the same file.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectWorkspaceService {

  private static final Duration LOCK_TTL = Duration.ofMinutes(5);

  private final AgentProperties props;
  private final StringRedisTemplate redis;

  public Path rootFor(String projectId) {
    requireProjectId(projectId);
    return Path.of(props.getWorkspace(), "projects", projectId).toAbsolutePath().normalize();
  }

  /** Resolve a relative path inside the project workspace, rejecting traversal. */
  public Path resolve(String projectId, String relativePath) {
    Path root = rootFor(projectId);
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("relativePath required");
    }
    if (relativePath.startsWith("/") || relativePath.contains("\0")) {
      throw new IllegalArgumentException("invalid relativePath");
    }
    Path resolved = root.resolve(relativePath).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("path escapes project workspace: " + relativePath);
    }
    return resolved;
  }

  /** Creates the workspace if missing, with {@code git init} + initial commit. Idempotent. */
  public void initIfMissing(String projectId) {
    Path root = rootFor(projectId);
    try {
      if (Files.isDirectory(root.resolve(".git"))) return;
      Files.createDirectories(root.resolve("codemaps"));
      Files.createDirectories(root.resolve("docs"));
      Files.createDirectories(root.resolve(".meta"));
      runGit(root, "git", "init", "-b", "main");
      runGit(root, "git", "config", "user.email", "agent@executespec.local");
      runGit(root, "git", "config", "user.name", "ExecuteSpec Project");
      Files.writeString(root.resolve(".gitignore"), ".DS_Store\n");
      runGit(root, "git", "add", ".gitignore");
      runGit(root, "git", "commit", "--allow-empty", "-m", "init project workspace");
      log.info("Initialised project workspace {}", root);
    } catch (Exception e) {
      throw new RuntimeException("project workspace init failed for " + projectId, e);
    }
  }

  /**
   * Atomically write a file and commit it. Uses an os-level rename plus a project-scoped Redis lock
   * to serialise concurrent writers.
   */
  public void writeAndCommit(
      String projectId, String relativePath, String content, String commitMsg) {
    initIfMissing(projectId);
    String lockKey = "project:ws-lock:" + projectId;
    String token = Long.toHexString(System.nanoTime());
    Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
    if (Boolean.FALSE.equals(acquired)) {
      // Best-effort: short retry loop with a total wait ~2s before giving up.
      for (int i = 0; i < 20; i++) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
        acquired = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) break;
      }
      if (!Boolean.TRUE.equals(acquired)) {
        throw new RuntimeException("could not acquire project workspace lock for " + projectId);
      }
    }
    try {
      Path target = resolve(projectId, relativePath);
      Files.createDirectories(target.getParent());
      Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + token);
      Files.writeString(tmp, content == null ? "" : content);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      Path root = rootFor(projectId);
      runGit(root, "git", "add", "--", relativePath);
      runGit(
          root,
          "git",
          "commit",
          "--allow-empty",
          "-m",
          commitMsg == null ? ("update " + relativePath) : commitMsg);
    } catch (Exception e) {
      throw new RuntimeException("writeAndCommit failed: " + relativePath, e);
    } finally {
      // Only release the lock if we still own it (CAS).
      String current = redis.opsForValue().get(lockKey);
      if (token.equals(current)) {
        redis.delete(lockKey);
      }
    }
  }

  /** Read a project-workspace file as UTF-8. Returns empty when missing. */
  public Optional<String> readFile(String projectId, String relativePath) {
    Path target = resolve(projectId, relativePath);
    if (!Files.isRegularFile(target)) return Optional.empty();
    try {
      return Optional.of(Files.readString(target));
    } catch (IOException e) {
      throw new RuntimeException("read failed: " + relativePath, e);
    }
  }

  /**
   * Mirror a single file to disk without committing (fast path used by the async DB→disk warmer).
   * Skips the git commit step in {@link #writeAndCommit} so warming a large project doesn't
   * generate dozens of filler commits.
   *
   * <p>Safe to call on any node: writes are per-file atomic via tmp-rename. The project-scoped
   * Redis lock is still acquired so we don't race with a concurrent {@code writeAndCommit}.
   */
  public void writeRawFile(String projectId, String relativePath, String content) {
    initIfMissing(projectId);
    String lockKey = "project:ws-lock:" + projectId;
    String token = Long.toHexString(System.nanoTime());
    Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
    if (Boolean.FALSE.equals(acquired)) {
      // Don't block the warmer — another writer is active, skip this mirror pass.
      log.debug(
          "writeRawFile: lock busy for project {} path {} — skipping", projectId, relativePath);
      return;
    }
    try {
      Path target = resolve(projectId, relativePath);
      Files.createDirectories(target.getParent());
      Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + token);
      Files.writeString(tmp, content == null ? "" : content);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      log.warn(
          "writeRawFile failed for project {} path {}: {}",
          projectId,
          relativePath,
          e.getMessage());
    } finally {
      String current = redis.opsForValue().get(lockKey);
      if (token.equals(current)) {
        redis.delete(lockKey);
      }
    }
  }

  /** Remove the project workspace entirely. Used on project deletion. */
  public void delete(String projectId) {
    Path root = rootFor(projectId);
    if (!Files.exists(root)) return;
    try {
      try (var stream = Files.walk(root)) {
        stream
            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignore) {
                  }
                });
      }
      log.info("Deleted project workspace {}", root);
    } catch (IOException e) {
      throw new RuntimeException("delete failed: " + root, e);
    }
  }

  private void requireProjectId(String projectId) {
    if (projectId == null
        || projectId.isBlank()
        || projectId.contains("/")
        || projectId.contains("..")) {
      throw new IllegalArgumentException("invalid projectId");
    }
  }

  private void runGit(Path dir, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      log.debug("git [{}] exit {}: {}", String.join(" ", cmd), exit, out);
    }
  }
}
