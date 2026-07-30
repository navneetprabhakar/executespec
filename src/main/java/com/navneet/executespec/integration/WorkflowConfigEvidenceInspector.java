package com.navneet.executespec.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.navneet.executespec.report.model.WorkflowConfigEvidence;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Provider-free workflow/configuration evidence lane.
 *
 * <p>Only literal {@code .github/workflows/*.yml|yaml} files are admitted. Reads, parser limits,
 * digests, paths, and diagnostics are deterministic and bounded so the result can be persisted as
 * validation evidence and reused as a correction promotion gate.
 */
@Component
public class WorkflowConfigEvidenceInspector {

  static final int FILE_CAP = 32;
  static final int FILE_BYTES_CAP = 256 * 1024;

  public WorkflowConfigEvidence inspect(Path workspaceRoot) {
    if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
      return WorkflowConfigEvidence.unavailable("Workspace is unavailable");
    }
    try {
      List<Path> workflowRoots = workflowRoots(workspaceRoot);
      List<Path> candidates = new ArrayList<>();
      for (Path root : workflowRoots) {
        if (!Files.isDirectory(root)) continue;
        try (var files = Files.list(root)) {
          files
              .filter(Files::isRegularFile)
              .filter(WorkflowConfigEvidenceInspector::isYaml)
              .sorted()
              .forEach(candidates::add);
        }
      }
      candidates.sort(Comparator.comparing(path -> stablePath(workspaceRoot, path)));
      if (candidates.isEmpty()) return WorkflowConfigEvidence.notApplicable();
      if (candidates.size() > FILE_CAP) {
        return WorkflowConfigEvidence.unavailable(
            "Workflow inventory exceeds the bounded file cap of " + FILE_CAP);
      }

      List<WorkflowConfigEvidence.FileEvidence> evidence = new ArrayList<>();
      boolean invalid = false;
      for (Path file : candidates) {
        long size = Files.size(file);
        String relative = stablePath(workspaceRoot, file);
        if (size > FILE_BYTES_CAP) {
          invalid = true;
          evidence.add(
              new WorkflowConfigEvidence.FileEvidence(
                  relative,
                  "",
                  WorkflowConfigEvidence.ParseStatus.INVALID,
                  "File exceeds the deterministic parser cap of " + FILE_BYTES_CAP + " bytes"));
          continue;
        }
        byte[] bytes = Files.readAllBytes(file);
        String problem = safeYamlProblem(new String(bytes, StandardCharsets.UTF_8));
        WorkflowConfigEvidence.ParseStatus status =
            problem == null
                ? WorkflowConfigEvidence.ParseStatus.VALID
                : WorkflowConfigEvidence.ParseStatus.INVALID;
        invalid |= status == WorkflowConfigEvidence.ParseStatus.INVALID;
        evidence.add(
            new WorkflowConfigEvidence.FileEvidence(
                relative, sha256(bytes), status, problem == null ? "" : problem));
      }
      return new WorkflowConfigEvidence(
          invalid ? WorkflowConfigEvidence.Status.FAILED : WorkflowConfigEvidence.Status.PASSED,
          evidence,
          invalid
              ? "One or more workflow files failed deterministic YAML validation"
              : "Every admitted workflow file parsed successfully");
    } catch (IOException | RuntimeException failure) {
      return WorkflowConfigEvidence.unavailable(
          "Workflow inspection failed: " + bounded(failure.getMessage()));
    }
  }

  private static List<Path> workflowRoots(Path workspaceRoot) throws IOException {
    List<Path> roots = new ArrayList<>();
    roots.add(workspaceRoot.resolve(".github/workflows"));
    Path repos = workspaceRoot.resolve("repos");
    if (Files.isDirectory(repos)) {
      try (var children = Files.list(repos)) {
        children
            .filter(Files::isDirectory)
            .sorted()
            .map(path -> path.resolve(".github/workflows"))
            .forEach(roots::add);
      }
    }
    return roots;
  }

  static String safeYamlProblem(String content) {
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(20);
      options.setNestingDepthLimit(50);
      options.setCodePointLimit(FILE_BYTES_CAP);
      new Yaml(new SafeConstructor(options)).load(content);
      return null;
    } catch (RuntimeException invalid) {
      String message = bounded(invalid.getMessage());
      return message.isBlank() ? invalid.getClass().getSimpleName() : message;
    }
  }

  private static boolean isYaml(Path path) {
    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private static String stablePath(Path workspaceRoot, Path file) {
    return workspaceRoot
        .toAbsolutePath()
        .normalize()
        .relativize(file.toAbsolutePath().normalize())
        .toString()
        .replace('\\', '/');
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String bounded(String message) {
    if (message == null) return "";
    String singleLine = message.replaceAll("\\s+", " ").trim();
    return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240) + "...";
  }
}
