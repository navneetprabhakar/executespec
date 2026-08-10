package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.testgen.BoundedProcess;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Prevents ignored build/cache output from becoming hidden cross-task state or stale proof. */
@Service
@RequiredArgsConstructor
public class WorkspaceIgnoredArtifactGuard {

  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_BASELINE_ENTRIES = 20_000;

  private final ObjectMapper objectMapper;

  public Baseline prepare(CorrectionWorkspaceTransaction.Checkpoint checkpoint, Runnable fence) {
    cleanAllIgnoredRoots(checkpoint, fence);
    return capture(checkpoint, fence);
  }

  /** Read-only ignored-root snapshot safe to persist before the mutation phase starts. */
  public Baseline capture(CorrectionWorkspaceTransaction.Checkpoint checkpoint, Runnable fence) {
    Map<String, List<String>> entries = new LinkedHashMap<>();
    int total = 0;
    for (CorrectionWorkspaceTransaction.RepositoryCheckpoint repository :
        checkpoint.repositories()) {
      fence.run();
      List<String> ignored = ignoredRoots(repository.repository());
      total += ignored.size();
      if (total > MAX_BASELINE_ENTRIES) {
        throw new IllegalStateException("WORKSPACE_IGNORED_BASELINE_LIMIT_EXCEEDED");
      }
      entries.put(repository.repository().toString(), ignored);
    }
    String json = json(entries);
    return new Baseline(json, DigestUtils.sha256Hex(json));
  }

  /**
   * Cleans every ignored root, including dependency/cache roots that predated PREPARED.
   *
   * <p>Ignored bytes are absent from the content-addressed generation and its cross-node archive.
   * Preserving them would therefore let one task consume state that acceptance cannot attest or
   * recovery cannot reproduce. BUILD dependencies must be reconstructed from accepted manifests and
   * governed environment inputs.
   */
  public List<String> cleanAttemptArtifacts(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint, String baselineJson, Runnable fence) {
    readBaseline(baselineJson); // validate the immutable PREPARED receipt before cleanup
    return cleanAllIgnoredRoots(checkpoint, fence);
  }

  private List<String> cleanAllIgnoredRoots(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint, Runnable fence) {
    Set<Path> repositoryRoots =
        checkpoint.repositories().stream()
            .map(CorrectionWorkspaceTransaction.RepositoryCheckpoint::repository)
            .map(path -> path.toAbsolutePath().normalize())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    List<String> removed = new ArrayList<>();
    for (CorrectionWorkspaceTransaction.RepositoryCheckpoint repository :
        checkpoint.repositories()) {
      Path root = repository.repository().toAbsolutePath().normalize();
      for (String relative : ignoredRoots(root)) {
        Path candidate = safeResolve(root, relative);
        Set<Path> protectedRepositories =
            repositoryRoots.stream()
                .filter(other -> !other.equals(root) && other.startsWith(candidate))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        fence.run();
        if (!isIgnored(root, relative)) {
          throw new IllegalStateException("WORKSPACE_IGNORED_ARTIFACT_CLASSIFICATION_CHANGED");
        }
        deleteTreeExcept(candidate, protectedRepositories);
        removed.add(root + ":" + relative);
      }
    }
    return List.copyOf(removed);
  }

  private static List<String> ignoredRoots(Path repository) {
    String output = git(repository, "git", "clean", "-ndX");
    return output
        .lines()
        .filter(line -> line.startsWith("Would remove "))
        .map(line -> line.substring("Would remove ".length()).trim())
        .filter(path -> !path.isBlank())
        .map(WorkspaceIgnoredArtifactGuard::safeRelative)
        .distinct()
        .sorted()
        .toList();
  }

  private static boolean isIgnored(Path repository, String relative) {
    return gitExit(repository, "git", "check-ignore", "-q", "--", relative) == 0;
  }

  private Map<String, List<String>> readBaseline(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception malformed) {
      throw new IllegalStateException("WORKSPACE_IGNORED_BASELINE_INVALID", malformed);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("WORKSPACE_IGNORED_BASELINE_SERIALIZATION_FAILED", failure);
    }
  }

  private static String git(Path repository, String... command) {
    ProcessBuilder process =
        new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
    try {
      BoundedProcess.Outcome outcome = BoundedProcess.run(process, GIT_TIMEOUT);
      if (!outcome.finished() || outcome.exitCode() != 0) {
        throw new IllegalStateException("WORKSPACE_IGNORED_GIT_FAILED");
      }
      return outcome.output();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("WORKSPACE_IGNORED_GIT_FAILED", failure);
    }
  }

  private static int gitExit(Path repository, String... command) {
    ProcessBuilder process =
        new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
    try {
      BoundedProcess.Outcome outcome = BoundedProcess.run(process, GIT_TIMEOUT);
      return outcome.finished() ? outcome.exitCode() : -1;
    } catch (Exception failure) {
      return -1;
    }
  }

  private static String safeRelative(String value) {
    String unquoted =
        value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1)
            : value;
    Path path = Path.of(unquoted).normalize();
    if (path.isAbsolute() || path.startsWith("..") || unquoted.indexOf('\0') >= 0) {
      throw new IllegalStateException("WORKSPACE_IGNORED_PATH_INVALID");
    }
    return posix(path);
  }

  private static Path safeResolve(Path root, String relative) {
    Path result = root.resolve(safeRelative(relative)).normalize();
    if (!result.startsWith(root)) throw new IllegalStateException("WORKSPACE_IGNORED_PATH_ESCAPE");
    return result;
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) return;
    try {
      if (Files.isSymbolicLink(root)) {
        Files.delete(root);
        return;
      }
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException("WORKSPACE_IGNORED_ARTIFACT_CLEAN_FAILED", failure);
    }
  }

  private static void deleteTreeExcept(Path root, Set<Path> protectedRoots) {
    if (protectedRoots.isEmpty()) {
      deleteTree(root);
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        boolean protectedPath =
            protectedRoots.stream()
                .anyMatch(
                    protectedRoot ->
                        path.startsWith(protectedRoot) || protectedRoot.startsWith(path));
        if (!protectedPath) Files.deleteIfExists(path);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("WORKSPACE_IGNORED_ARTIFACT_CLEAN_FAILED", failure);
    }
  }

  private static String posix(Path path) {
    return path.toString().replace('\\', '/');
  }

  public record Baseline(String json, String digest) {}
}
