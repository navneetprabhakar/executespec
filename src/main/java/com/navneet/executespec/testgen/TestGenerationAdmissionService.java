package com.navneet.executespec.testgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import com.navneet.executespec.report.RunChangeEvidenceService;
import com.navneet.executespec.testgen.model.TestTask;

import org.springframework.stereotype.Component;

/** Fail-closed source admission for generated integration tests. */
@Component
@lombok.extern.slf4j.Slf4j
public class TestGenerationAdmissionService {

  private static final Set<String> SOURCE_EXTENSIONS =
      Set.of(".java", ".kt", ".js", ".jsx", ".ts", ".tsx", ".py", ".go", ".rs", ".html");

  // RR-300 — bound heap use during automatic post-run admission. A repository with large
  // generated/minified/vendored artifacts must not trigger multi-gigabyte allocation. Files above
  // the per-file cap are skipped for content scanning; vendored/generated trees are not walked.
  private static final long MAX_SCAN_FILE_BYTES = 512L * 1024; // 512 KiB content scan
  private static final long MAX_FINGERPRINT_FILE_BYTES = 2L * 1024 * 1024; // 2 MiB hashed prefix
  private static final Set<String> EXCLUDED_DIR_SEGMENTS =
      Set.of(
          "node_modules",
          "vendor",
          "dist",
          "build",
          "target",
          ".git",
          ".gradle",
          ".venv",
          "venv",
          "__pycache__",
          ".next",
          "coverage",
          "out");

  private final RunChangeEvidenceService changeEvidenceService;
  private final ApiSurfaceExtractor apiSurfaceExtractor;
  private final com.navneet.executespec.harness.WorkspaceInitService workspaceInitService;
  private final com.navneet.executespec.integration.CorrectionWorkspaceTransaction generations;

  public TestGenerationAdmissionService(
      RunChangeEvidenceService changes,
      ApiSurfaceExtractor surfaces,
      com.navneet.executespec.harness.WorkspaceInitService workspaces) {
    this(
        changes,
        surfaces,
        workspaces,
        new com.navneet.executespec.integration.CorrectionWorkspaceTransaction());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public TestGenerationAdmissionService(
      RunChangeEvidenceService changes,
      ApiSurfaceExtractor surfaces,
      com.navneet.executespec.harness.WorkspaceInitService workspaces,
      com.navneet.executespec.integration.CorrectionWorkspaceTransaction generations) {
    this.changeEvidenceService = changes;
    this.apiSurfaceExtractor = surfaces;
    this.workspaceInitService = workspaces;
    this.generations = generations;
  }

  public String currentWorkspaceGeneration(String runId) {
    Path workspace = workspaceInitService.runWorkspacePath(runId);
    if (workspace == null || !Files.isDirectory(workspace)) return null;
    try {
      return generations.captureGeneration(workspace);
    } catch (RuntimeException unavailable) {
      log.debug(
          "Canonical test source generation unavailable run={}: {}", runId, unavailable.toString());
      return null;
    }
  }

  public Admission assess(String runId, boolean isCli, boolean isStaticHtml) {
    Path workspace = workspaceInitService.runWorkspacePath(runId);
    if (!Files.isDirectory(workspace)) return Admission.blocked("workspace is unavailable");
    RunChangeEvidenceService.ChangeEvidence changes =
        changeEvidenceService.inspect(runId, List.of());
    if (!changes.valid()) return Admission.blocked(changes.invalidReason());
    boolean relevantDiff =
        changes.productFiles().stream()
            .map(f -> f.path().toLowerCase(Locale.ROOT))
            .anyMatch(TestGenerationAdmissionService::isRelevantProductSource);
    if (!relevantDiff) return Admission.blocked("no relevant product source diff");
    if (!containsSource(workspace))
      return Admission.blocked("workspace contains no runtime source");
    List<String> paths = apiSurfaceExtractor.extractPaths(workspace);
    if (!isCli && !isStaticHtml && paths.isEmpty()) {
      return Admission.blocked("HTTP runtime has no extracted endpoint surface");
    }
    String workspaceFingerprint = workspaceFingerprint(workspace, changes);
    if (changes.baselineFingerprint() == null || workspaceFingerprint == null) {
      return Admission.blocked("source provenance fingerprint is unavailable");
    }
    return new Admission(
        true, null, workspace, paths, changes.baselineFingerprint(), workspaceFingerprint);
  }

  /**
   * Recompute the relevant product-diff fingerprint for a run as it stands right now (RR-1583).
   *
   * <p>V277 persisted this fingerprint on the test plan but nothing ever read it back, so a
   * workspace that legitimately changed after planning — reviewer fix-build pass, post-review
   * step-up rerun, approved continuation — was still scored as evidence for the original diff. Test
   * execution calls this immediately before and after running, and refuses to publish pass counts
   * when it does not match what the plan was bound to.
   *
   * @return the current fingerprint, or null when it cannot be computed (treat as a mismatch)
   */
  public String currentWorkspaceFingerprint(String runId) {
    Path workspace = workspaceInitService.runWorkspacePath(runId);
    if (workspace == null || !Files.isDirectory(workspace)) return null;
    RunChangeEvidenceService.ChangeEvidence changes =
        changeEvidenceService.inspect(runId, List.of());
    if (changes == null || !changes.valid()) return null;
    return workspaceFingerprint(workspace, changes);
  }

  public boolean targetExists(Admission admission, TestTask task) {
    if (admission == null || !admission.admitted() || task == null) return false;
    String target = task.getTargetEntity();
    if (target == null || target.isBlank()) return false;
    List<String> tokens =
        Stream.of(target.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(t -> t.length() >= 3)
            .filter(t -> !Set.of("api", "test", "tests", "service", "endpoint").contains(t))
            .toList();
    if (tokens.isEmpty()) return false;
    if (admission.runtimePaths().stream()
        .map(p -> p.toLowerCase(Locale.ROOT))
        .anyMatch(path -> tokens.stream().anyMatch(path::contains))) return true;
    Path root = admission.workspace();
    try (Stream<Path> walk = Files.walk(root, 8)) {
      return walk.filter(p -> !isExcludedPath(root, p))
          .filter(Files::isRegularFile)
          .filter(p -> isSource(p.getFileName().toString().toLowerCase(Locale.ROOT)))
          .limit(2_000)
          .anyMatch(
              p -> {
                String file = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (tokens.stream().anyMatch(file::contains)) return true;
                try {
                  // RR-300 — never read a large artifact fully into heap just to token-scan it.
                  if (Files.size(p) > MAX_SCAN_FILE_BYTES) return false;
                  String content = Files.readString(p).toLowerCase(Locale.ROOT);
                  return tokens.stream().anyMatch(content::contains);
                } catch (IOException ignored) {
                  return false;
                }
              });
    } catch (IOException e) {
      return false;
    }
  }

  /** RR-300 — a path is excluded when any of its segments names a vendored/generated tree. */
  private static boolean isExcludedPath(Path root, Path path) {
    Path relative = root.relativize(path);
    for (Path segment : relative) {
      if (EXCLUDED_DIR_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private static boolean containsSource(Path workspace) {
    try (Stream<Path> walk = Files.walk(workspace, 8)) {
      return walk.filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
          .anyMatch(TestGenerationAdmissionService::isSource);
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isSource(String name) {
    return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private static boolean isRelevantProductSource(String path) {
    String normalized = path.replace('\\', '/');
    if (!isSource(normalized)) return false;
    return !normalized.contains("/src/test/")
        && !normalized.startsWith("tests/")
        && !normalized.contains("/tests/")
        && !normalized.contains("/__tests__/")
        && !normalized.matches(".*(?:test|spec)\\.[a-z0-9]+$");
  }

  private static String workspaceFingerprint(
      Path workspace, RunChangeEvidenceService.ChangeEvidence changes) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      for (RunChangeEvidenceService.ChangedFile file :
          changes.productFiles().stream()
              .sorted(java.util.Comparator.comparing(RunChangeEvidenceService.ChangedFile::path))
              .toList()) {
        digest.update(file.path().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(file.status().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path changed = workspace.resolve(file.path()).normalize();
        if (changed.startsWith(workspace) && Files.isRegularFile(changed)) {
          // RR-300 — hash a bounded prefix plus the exact size instead of loading the whole file.
          // Deterministic and collision-safe for provenance while capping per-file allocation.
          long size = Files.size(changed);
          digest.update(Long.toString(size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          digest.update(boundedPrefix(changed, size));
        }
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      return null;
    }
  }

  /** RR-300 — read at most {@link #MAX_FINGERPRINT_FILE_BYTES} bytes from the head of a file. */
  private static byte[] boundedPrefix(Path file, long size) throws IOException {
    int cap = (int) Math.min(size, MAX_FINGERPRINT_FILE_BYTES);
    byte[] buffer = new byte[cap];
    try (java.io.InputStream in = Files.newInputStream(file)) {
      int read = 0;
      while (read < cap) {
        int n = in.read(buffer, read, cap - read);
        if (n < 0) break;
        read += n;
      }
      return read == cap ? buffer : java.util.Arrays.copyOf(buffer, read);
    }
  }

  public record Admission(
      boolean admitted,
      String reason,
      Path workspace,
      List<String> runtimePaths,
      String sourceFingerprint,
      String workspaceFingerprint) {
    static Admission blocked(String reason) {
      return new Admission(false, reason, null, List.of(), null, null);
    }
  }
}
