package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.nio.file.Path;
import java.util.UUID;

import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.standards.EffectiveStandardsResolver;
import com.navneet.executespec.standards.StandardsRunner;
import com.navneet.executespec.standards.StandardsViolationRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deterministic coding-standards enforcement at run-end, extracted from {@link RunOrchestrator}
 * (god-class split, increment 5 — audit #43). Resolves the org/project's effective standards, runs
 * them against the final workspace (lint / file-pattern / AST via {@link StandardsRunner}), and
 * persists any violations to {@code standards_violation} so the {@code /standards/report} endpoint
 * reflects reality. Project-gated + flag-gated by the caller; non-fatal; a no-op when the org
 * defines no standards (resolver returns empty → early return). Behaviour is byte-identical to the
 * originals; {@code toStandardsViolationRows} stays a {@code public static} (pinned by {@code
 * RunOrchestratorEnterpriseEnforcementTest}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RunStandardsEnforcer {

  /** Cap the workspace file walk for standards enforcement (bounded cost on large repos). */
  private static final int STANDARDS_WALK_FILE_CAP = 6_000;

  /** Cap persisted standards-violation rows per run (avoid a runaway lint dump). */
  private static final int STANDARDS_MAX_VIOLATIONS_PERSIST = 200;

  /** Dirs never worth walking for standards (vendored / build output / VCS). */
  private static final java.util.Set<String> STANDARDS_SKIP_DIRS =
      java.util.Set.of(
          ".git",
          "node_modules",
          "target",
          "dist",
          "build",
          ".venv",
          "venv",
          "__pycache__",
          "vendor",
          ".gradle",
          ".idea",
          ".mvn",
          "out",
          "bin",
          "obj",
          ".next",
          "coverage");

  private final EffectiveStandardsResolver effectiveStandardsResolver;
  private final StandardsRunner standardsRunner;
  private final StandardsViolationRepository standardsViolationRepository;
  private final ProjectRepository projectRepository;

  /**
   * Run deterministic coding standards (lint / file-pattern / AST) against the final workspace and
   * persist any violations to {@code standards_violation} so the {@code /standards/report} endpoint
   * reflects reality. Project-gated by the caller; non-fatal here. Zero overhead when the org has
   * no standards defined (resolver returns empty → early return).
   */
  void runStandardsEnforcement(String runId, String projectId, Path workspaceRoot) {
    UUID runUuid;
    try {
      runUuid = UUID.fromString(runId);
    } catch (Exception e) {
      runUuid = null; // run_id is nullable on standards_violation
    }
    String orgId =
        projectRepository
            .findById(projectId)
            .map(com.navneet.executespec.auth.entity.Project::getOrgId)
            .orElse(null);
    if (orgId == null) return;
    var effective = effectiveStandardsResolver.resolve(orgId, projectId, null);
    if (effective == null || effective.isEmpty()) return; // common case → no-op
    java.util.List<String> repoFilePaths = new java.util.ArrayList<>();
    java.util.List<Path> sourceFiles = new java.util.ArrayList<>();
    collectWorkspaceFiles(workspaceRoot, repoFilePaths, sourceFiles);
    var report = standardsRunner.run(effective, workspaceRoot, repoFilePaths, sourceFiles);
    java.util.List<com.navneet.executespec.standards.StandardsViolation> rows =
        toStandardsViolationRows(runUuid, projectId, report, STANDARDS_MAX_VIOLATIONS_PERSIST);
    int persisted = 0;
    for (var row : rows) {
      try {
        standardsViolationRepository.save(row);
        persisted++;
      } catch (Exception e) {
        log.debug("RunOrchestrator run={} standards persist skipped: {}", runId, e.getMessage());
      }
    }
    log.info(
        "RunOrchestrator run={} standards: {} standard(s) resolved, {} violation(s) persisted",
        runId,
        effective.size(),
        persisted);
  }

  /** Pure mapping: StandardsRunner violations → persistable {@code standards_violation} rows. */
  static java.util.List<com.navneet.executespec.standards.StandardsViolation>
      toStandardsViolationRows(
          UUID runId,
          String projectId,
          com.navneet.executespec.standards.StandardsRunner.RunReport report,
          int cap) {
    java.util.List<com.navneet.executespec.standards.StandardsViolation> out =
        new java.util.ArrayList<>();
    if (report == null || report.violations() == null) return out;
    for (var v : report.violations()) {
      if (out.size() >= cap) break;
      out.add(
          com.navneet.executespec.standards.StandardsViolation.builder()
              .runId(runId)
              .projectId(projectId)
              .standardId(v.standardId())
              .enforcementType(v.enforcementType())
              .severity(v.severity() == null ? "ADVISORY" : v.severity().name())
              .violationSummary(truncate(v.message() + " @ " + v.location(), 1000))
              .build());
    }
    return out;
  }

  /** Bounded, pruned workspace walk for standards enforcement. */
  private void collectWorkspaceFiles(
      Path root, java.util.List<String> relPaths, java.util.List<Path> sourceFiles) {
    if (root == null || !java.nio.file.Files.isDirectory(root)) return;
    try {
      java.nio.file.Files.walkFileTree(
          root,
          new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (!dir.equals(root) && STANDARDS_SKIP_DIRS.contains(dir.getFileName().toString())) {
                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (relPaths.size() >= STANDARDS_WALK_FILE_CAP) {
                return java.nio.file.FileVisitResult.TERMINATE;
              }
              relPaths.add(root.relativize(file).toString());
              sourceFiles.add(file);
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, java.io.IOException e) {
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
    } catch (Exception e) {
      log.debug("collectWorkspaceFiles walk failed: {}", e.getMessage());
    }
  }
}
