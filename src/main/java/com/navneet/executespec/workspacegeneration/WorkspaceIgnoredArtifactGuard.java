package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Prevents ignored build/cache output from becoming hidden cross-task state or stale proof. */
@Service
public class WorkspaceIgnoredArtifactGuard {

  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_BASELINE_ENTRIES = 20_000;
  private static final int MAX_IGNORED_INVENTORY_BYTES = 1_000_000;

  private final ObjectMapper objectMapper;
  private final BuildEnvironmentReceiptStore environmentReceipts;

  public WorkspaceIgnoredArtifactGuard(ObjectMapper objectMapper) {
    this(objectMapper, null);
  }

  @Autowired
  public WorkspaceIgnoredArtifactGuard(
      ObjectMapper objectMapper, BuildEnvironmentReceiptStore environmentReceipts) {
    this.objectMapper = objectMapper;
    this.environmentReceipts = environmentReceipts;
  }

  public Baseline prepare(CorrectionWorkspaceTransaction.Checkpoint checkpoint, Runnable fence) {
    cleanAllIgnoredRoots(checkpoint, fence);
    return capture(checkpoint, fence);
  }

  public Baseline prepare(
      java.util.UUID attemptId,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      Runnable fence) {
    List<String> removed = cleanAllIgnoredRoots(checkpoint, fence);
    recordCleanup(attemptId, "PREPARATION_CLEANUP", removed);
    return capture(checkpoint, fence);
  }

  /** Read-only ignored-root snapshot safe to persist before the mutation phase starts. */
  public Baseline capture(CorrectionWorkspaceTransaction.Checkpoint checkpoint, Runnable fence) {
    Map<String, List<String>> entries = new LinkedHashMap<>();
    int total = 0;
    for (CorrectionWorkspaceTransaction.RepositoryCheckpoint repository :
        checkpoint.repositories()) {
      fence.run();
      List<String> ignored = ignoredPaths(repository.repository());
      total += ignored.size();
      if (total > MAX_BASELINE_ENTRIES) {
        throw new IllegalStateException("WORKSPACE_IGNORED_BASELINE_LIMIT_EXCEEDED");
      }
      entries.put(repository.repository().toString(), ignored);
    }
    // The column is jsonb, so PostgreSQL may change whitespace and object-key order on read. Bind
    // the digest to a deterministic semantic representation rather than transport text.
    String json = json(new java.util.TreeMap<>(entries));
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
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      String baselineJson,
      String baselineDigest,
      Runnable fence) {
    if (baselineJson == null || baselineDigest == null) {
      throw new IllegalStateException("WORKSPACE_IGNORED_BASELINE_DIGEST_MISMATCH");
    }
    Map<String, List<String>> parsed = readBaseline(baselineJson);
    String canonical = json(new java.util.TreeMap<>(parsed));
    if (!DigestUtils.sha256Hex(canonical).equals(baselineDigest)) {
      throw new IllegalStateException("WORKSPACE_IGNORED_BASELINE_DIGEST_MISMATCH");
    }
    return cleanAllIgnoredRoots(checkpoint, fence);
  }

  public List<String> cleanAttemptArtifacts(
      java.util.UUID attemptId,
      String phase,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      String baselineJson,
      String baselineDigest,
      Runnable fence) {
    List<String> removed = cleanAttemptArtifacts(checkpoint, baselineJson, baselineDigest, fence);
    recordCleanup(attemptId, phase, removed);
    return removed;
  }

  private void recordCleanup(java.util.UUID attemptId, String phase, List<String> removed) {
    if (environmentReceipts == null || attemptId == null) return;
    String removedJson = json(removed.stream().sorted().toList());
    String removedDigest = DigestUtils.sha256Hex(removedJson);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", 1);
    evidence.put("phase", phase);
    evidence.put("removedRootsDigest", removedDigest);
    evidence.put("removedRootCount", removed.size());
    evidence.put("productGenerationIncludesIgnoredBytes", false);
    String evidenceJson = json(evidence);
    environmentReceipts.recordCleanup(
        attemptId,
        phase,
        removedJson,
        removedDigest,
        evidenceJson,
        DigestUtils.sha256Hex(evidenceJson));
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
      // Cache-local .gitignore files (pytest creates one containing '*') classify their siblings.
      // Delete those rule files last, deepest first, so our own cleanup cannot reclassify bytes
      // that are still awaiting their fresh per-path check. External rule changes still fail
      // closed.
      List<String> cleanupOrder =
          ignoredPaths(root).stream()
              .sorted(
                  Comparator.comparing(
                          (String path) ->
                              path.equals(".gitignore") || path.endsWith("/.gitignore"))
                      .thenComparing(Comparator.reverseOrder()))
              .toList();
      for (String relative : cleanupOrder) {
        Path candidate = safeResolve(root, relative);
        Set<Path> protectedRepositories =
            repositoryRoots.stream()
                .filter(other -> !other.equals(root) && other.startsWith(candidate))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        fence.run();
        if (!ignoredPaths(root).contains(relative)) {
          throw new IllegalStateException("WORKSPACE_IGNORED_ARTIFACT_CLASSIFICATION_CHANGED");
        }
        if (isIgnoredProductPath(relative)) {
          throw new IllegalStateException("IGNORED_PRODUCT_PATH");
        }
        if (!protectedRepositories.isEmpty()) continue;
        deleteTreeExcept(candidate, protectedRepositories);
        pruneEmptyParents(candidate.getParent(), root, protectedRepositories);
        removed.add(root + ":" + relative);
      }
    }
    return List.copyOf(removed);
  }

  private static List<String> ignoredPaths(Path repository) {
    String output = gitNullInventory(repository);
    return java.util.Arrays.stream(output.split("\\u0000", -1))
        .filter(path -> !path.isBlank())
        .map(WorkspaceIgnoredArtifactGuard::safeRelative)
        .distinct()
        .sorted()
        .toList();
  }

  private static String gitNullInventory(Path repository) {
    Process process;
    try {
      process =
          new ProcessBuilder("git", "ls-files", "--others", "--ignored", "--exclude-standard", "-z")
              .directory(repository.toFile())
              .redirectErrorStream(true)
              .start();
    } catch (IOException failure) {
      throw new IllegalStateException("WORKSPACE_IGNORED_GIT_FAILED", failure);
    }
    java.util.concurrent.FutureTask<byte[]> drain =
        new java.util.concurrent.FutureTask<>(
            () -> {
              try (var input = process.getInputStream();
                  var bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                  if (bytes.size() + read > MAX_IGNORED_INVENTORY_BYTES) {
                    throw new IllegalStateException("WORKSPACE_IGNORED_INVENTORY_LIMIT_EXCEEDED");
                  }
                  bytes.write(buffer, 0, read);
                }
                return bytes.toByteArray();
              }
            });
    Thread.ofVirtual().name("ignored-inventory-drain").start(drain);
    try {
      if (!process.waitFor(GIT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("WORKSPACE_IGNORED_GIT_FAILED");
      }
      byte[] bytes = drain.get(5, java.util.concurrent.TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        throw new IllegalStateException("WORKSPACE_IGNORED_GIT_FAILED");
      }
      return java.nio.charset.StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IllegalStateException("WORKSPACE_IGNORED_GIT_FAILED", interrupted);
    } catch (java.nio.charset.CharacterCodingException invalidPath) {
      throw new IllegalStateException("WORKSPACE_IGNORED_PATH_ENCODING_UNSUPPORTED", invalidPath);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      process.destroyForcibly();
      throw new IllegalStateException("WORKSPACE_IGNORED_GIT_FAILED", failure);
    }
  }

  private static boolean isIgnoredProductPath(String relative) {
    String normalized = "/" + relative.toLowerCase(java.util.Locale.ROOT) + "/";
    boolean underEnvironmentRoot =
        List.of(
                "/node_modules/",
                "/.venv/",
                "/venv/",
                "/target/",
                "/build/",
                "/dist/",
                "/.gradle/",
                "/__pycache__/",
                "/.pytest_cache/",
                "/.mypy_cache/",
                "/.ruff_cache/",
                "/.executespec/environment/")
            .stream()
            .anyMatch(normalized::contains);
    if (underEnvironmentRoot) return false;
    String file = Path.of(relative).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return file.matches(
        ".*\\.(java|kt|kts|scala|groovy|js|jsx|ts|tsx|py|go|rs|c|cc|cpp|h|hpp|cs|rb|php|swift)$");
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
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
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

  private static void pruneEmptyParents(Path candidate, Path repository, Set<Path> protectedRoots) {
    Path current = candidate;
    while (current != null && !current.equals(repository) && current.startsWith(repository)) {
      Path next = current.getParent();
      Path inspected = current;
      boolean protectedPath =
          protectedRoots.stream()
              .anyMatch(root -> inspected.startsWith(root) || root.startsWith(inspected));
      if (protectedPath || !Files.isDirectory(current) || Files.isSymbolicLink(current)) return;
      try (var entries = Files.list(current)) {
        if (entries.findAny().isPresent()) return;
      } catch (IOException failure) {
        throw new IllegalStateException("WORKSPACE_IGNORED_ARTIFACT_CLEAN_FAILED", failure);
      }
      try {
        Files.deleteIfExists(current);
      } catch (IOException failure) {
        throw new IllegalStateException("WORKSPACE_IGNORED_ARTIFACT_CLEAN_FAILED", failure);
      }
      current = next;
    }
  }

  private static String posix(Path path) {
    return path.toString().replace('\\', '/');
  }

  public record Baseline(String json, String digest) {}
}
