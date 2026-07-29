package com.navneet.executespec.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import com.navneet.executespec.harness.WorkspaceInitService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Clones and inventories a standalone brownfield repository before any model call is admitted.
 *
 * <p>Project-linked repositories use the multi-repository admission path. Local-hands runs use the
 * client working-tree handshake, and source-run iterations copy their immutable parent workspace.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StandaloneExistingWorkspaceAdmission {

  private static final Set<String> SOURCE_EXTENSIONS =
      Set.of(
          "c", "cc", "cpp", "cs", "css", "dart", "erl", "ex", "exs", "fs", "fsx", "go", "groovy",
          "h", "hpp", "html", "java", "js", "jsx", "kt", "kts", "lua", "php", "pl", "py", "r", "rb",
          "rs", "scala", "sh", "sol", "sql", "svelte", "swift", "ts", "tsx", "vue", "zig");
  private static final Set<String> EXCLUDED_DIRECTORIES =
      Set.of(".git", ".agent-context", "build", "dist", "node_modules", "target");

  private final WorkspaceInitService workspaceInit;

  Admission prepareInitial(
      String runId,
      String projectId,
      String sourceRunId,
      boolean localRun,
      String repoMode,
      String repoUrl,
      String credentialSecret)
      throws Exception {
    if (sourceRunId != null) {
      workspaceInit.copyFromRunRequired(sourceRunId, runId);
      return Admission.admitted(0);
    }
    if (!required(projectId, null, true, localRun, repoMode)) {
      workspaceInit.ensureRunWorkspace(runId);
      return Admission.admitted(0);
    }
    Admission admission = admit(runId, repoUrl, credentialSecret);
    if (admission.admitted()) {
      log.info(
          "Admitted standalone brownfield run={} sourceFiles={}",
          runId,
          admission.sourceFileCount());
    }
    return admission;
  }

  static boolean required(
      String projectId,
      String sourceRunId,
      boolean initialSubmission,
      boolean localRun,
      String repoMode) {
    return initialSubmission
        && !localRun
        && projectId == null
        && sourceRunId == null
        && "EXISTING".equalsIgnoreCase(repoMode);
  }

  private Admission admit(String runId, String repoUrl, String credentialSecret) {
    if (repoUrl == null || repoUrl.isBlank()) {
      return Admission.rejected("standalone EXISTING run requires a repository URL");
    }
    try {
      workspaceInit.cloneFromRepo(runId, repoUrl, credentialSecret);
    } catch (Exception cloneFailure) {
      log.warn(
          "Standalone brownfield clone failed run={} failureType={}",
          runId,
          cloneFailure.getClass().getSimpleName());
      return Admission.rejected("repository clone failed");
    }

    Path root = workspaceInit.runWorkspacePath(runId);
    if (!Files.isDirectory(root.resolve(".git"))) {
      return Admission.rejected("repository clone is missing Git metadata");
    }
    try {
      long sourceFiles = countSourceFiles(root);
      return sourceFiles == 0
          ? Admission.rejected("repository has no deterministic source inventory")
          : Admission.admitted(sourceFiles);
    } catch (IOException inventoryFailure) {
      log.warn(
          "Standalone brownfield inventory failed run={} failureType={}",
          runId,
          inventoryFailure.getClass().getSimpleName());
      return Admission.rejected("repository source inventory failed");
    }
  }

  private static long countSourceFiles(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> hasNoExcludedDirectory(root.relativize(path)))
          .filter(StandaloneExistingWorkspaceAdmission::hasSourceExtension)
          .count();
    }
  }

  private static boolean hasNoExcludedDirectory(Path relative) {
    for (Path segment : relative) {
      if (EXCLUDED_DIRECTORIES.contains(segment.toString().toLowerCase(Locale.ROOT))) return false;
    }
    return true;
  }

  private static boolean hasSourceExtension(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot >= 0 && SOURCE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  record Admission(boolean admitted, long sourceFileCount, String reason) {
    static Admission admitted(long sourceFileCount) {
      return new Admission(true, sourceFileCount, null);
    }

    static Admission rejected(String reason) {
      return new Admission(false, 0, reason);
    }
  }
}
