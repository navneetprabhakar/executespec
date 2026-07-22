package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.arch.invariants.ArchInvariant;
import com.navneet.executespec.arch.invariants.ArchInvariantRepository;
import com.navneet.executespec.arch.invariants.ArchInvariantViolation;
import com.navneet.executespec.arch.invariants.ArchInvariantViolationRepository;
import com.navneet.executespec.arch.invariants.InvariantChecker;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Production run-end execution for configured project architecture invariants. */
@Component
@RequiredArgsConstructor
@Slf4j
class RunInvariantEnforcer {

  private static final int FILE_CAP = 6_000;
  private static final long CONTENT_FILE_CAP_BYTES = 1_000_000;
  private static final Set<String> SKIP_DIRS =
      Set.of(
          ".git",
          "node_modules",
          "target",
          "dist",
          "build",
          ".venv",
          "venv",
          "vendor",
          ".gradle",
          "coverage");

  private final ArchInvariantRepository invariantRepository;
  private final ArchInvariantViolationRepository violationRepository;
  private final InvariantChecker checker;
  private final ObjectMapper objectMapper;

  List<ReviewIssue> enforce(String runId, String projectId, Path workspaceRoot) {
    List<ArchInvariant> active =
        invariantRepository.findByProjectId(projectId).stream()
            .filter(i -> "ACTIVE".equalsIgnoreCase(i.getStatus()))
            .toList();
    if (active.isEmpty()) return List.of();

    // RR-542 — the walk may prune skip-dirs, hit the file cap, omit a >1MB file, or fail to read an
    // entry. When it does, the file list is a SUBSET of the candidate workspace, so a checker PASS
    // over it cannot soundly assert the invariant holds — a forbidden dependency/file could be in
    // the unseen part. An incomplete inventory therefore downgrades every PASS to a critical
    // UNVERIFIED outcome (fail-closed governance), exactly as VIOLATIONS/degraded statuses already
    // produce evidence. VIOLATIONS found over a partial list are still real and pass through.
    Inventory inventory = collectFiles(workspaceRoot);
    List<String> files = inventory.files();
    List<ReviewIssue> issues = new ArrayList<>();
    int issueNumber = 0;
    for (ArchInvariant invariant : active) {
      InvariantChecker.CheckResult result =
          checker.checkDetailed(
              invariant.getInvariantYaml(), files, path -> readBounded(workspaceRoot, path));
      if (result.status() == InvariantChecker.CheckStatus.PASS) {
        if (inventory.complete()) continue;
        issueNumber++;
        persist(
            invariant,
            "UNVERIFIED",
            null,
            null,
            "workspace inventory incomplete (" + inventory.reason() + "); PASS is not provable");
        issues.add(
            issue(
                "SYNTH-INVARIANT-UNAVAILABLE-" + issueNumber,
                null,
                "Architecture invariant '"
                    + invariant.getInvariantId()
                    + "' could not be verified: the workspace scan was incomplete ("
                    + inventory.reason()
                    + ").",
                "Reduce repository size / excluded paths so governance can scan the whole"
                    + " workspace, then rerun."));
        continue;
      }
      if (result.status() == InvariantChecker.CheckStatus.VIOLATIONS) {
        for (InvariantChecker.Finding finding : result.findings()) {
          issueNumber++;
          persist(invariant, "VIOLATION", finding.ruleKind(), finding.file(), finding.message());
          issues.add(
              issue(
                  "SYNTH-INVARIANT-VIOLATION-" + issueNumber,
                  finding.file(),
                  "Architecture invariant '"
                      + invariant.getInvariantId()
                      + "' failed: "
                      + truncate(finding.message(), 500),
                  "Change the implementation to satisfy the configured architecture invariant."));
        }
      } else {
        issueNumber++;
        persist(
            invariant,
            result.status().name(),
            null,
            null,
            result.diagnostic() == null
                ? "configuration cannot be evaluated"
                : result.diagnostic());
        issues.add(
            issue(
                "SYNTH-INVARIANT-UNAVAILABLE-" + issueNumber,
                null,
                "Architecture invariant '"
                    + invariant.getInvariantId()
                    + "' was not verified ("
                    + result.status()
                    + ").",
                "Correct the invariant configuration before treating governance as passed."));
      }
    }
    log.info(
        "RunOrchestrator run={} architecture invariants: {} active, {} issue(s)",
        runId,
        active.size(),
        issues.size());
    return issues;
  }

  private void persist(
      ArchInvariant invariant, String status, String ruleKind, String file, String message) {
    try {
      String detail =
          objectMapper.writeValueAsString(
              java.util.Map.of(
                  "status",
                  status,
                  "ruleKind",
                  ruleKind == null ? "" : ruleKind,
                  "file",
                  file == null ? "" : file,
                  "message",
                  truncate(message, 1000)));
      violationRepository.save(
          ArchInvariantViolation.builder()
              .invariantId(invariant.getId())
              .detectionPhase(ArchInvariantViolation.DetectionPhase.PLAN.name())
              .detail(detail)
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("Architecture invariant evidence could not be persisted", e);
    }
  }

  private static ReviewIssue issue(
      String id, String file, String description, String fixInstruction) {
    return ReviewIssue.builder()
        .id(id)
        .severity(IssueSeverity.CRITICAL)
        .category(IssueCategory.CROSS_TASK_MISMATCH)
        .file(file)
        .description(description)
        .fixInstruction(fixInstruction)
        .autoFixed(false)
        .fixSucceeded(false)
        .build();
  }

  /**
   * Bounded, pruned workspace inventory. {@code complete} is true only when the walk saw every
   * eligible file — it neither pruned a {@link #SKIP_DIRS} subtree, hit {@link #FILE_CAP}, omitted
   * a file larger than {@link #CONTENT_FILE_CAP_BYTES}, nor failed to read an entry (RR-542).
   */
  private record Inventory(List<String> files, boolean complete, String reason) {}

  private static Inventory collectFiles(Path root) {
    List<String> files = new ArrayList<>();
    if (root == null || !Files.isDirectory(root)) {
      return new Inventory(files, false, "workspace missing");
    }
    // 0=complete 1=pruned 2=cap 3=oversize 4=read-error — highest-set wins for the reason string.
    boolean[] pruned = {false};
    boolean[] cappedOrOversize = {false};
    boolean[] readError = {false};
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                pruned[0] = true; // a pruned subtree may hold a forbidden dependency/file
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (files.size() >= FILE_CAP) {
                cappedOrOversize[0] = true; // hit the cap — the rest of the tree is unseen
                return FileVisitResult.TERMINATE;
              }
              if (attrs.isRegularFile()) {
                if (attrs.size() <= CONTENT_FILE_CAP_BYTES) {
                  files.add(root.relativize(file).toString());
                } else {
                  cappedOrOversize[0] = true; // a large file was omitted from content checks
                }
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, java.io.IOException e) {
              readError[0] = true; // an unreadable entry means the inventory is not provably whole
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (Exception e) {
      throw new IllegalStateException("Architecture invariant workspace inventory failed", e);
    }
    boolean complete = !pruned[0] && !cappedOrOversize[0] && !readError[0];
    String reason =
        complete
            ? "complete"
            : (readError[0]
                ? "unreadable files"
                : (cappedOrOversize[0] ? "file cap / oversize files" : "pruned directories"));
    return new Inventory(files, complete, reason);
  }

  private static String readBounded(Path root, String relativePath) {
    try {
      Path path = root.resolve(relativePath).normalize();
      if (!path.startsWith(root.normalize())
          || !Files.isRegularFile(path)
          || Files.size(path) > CONTENT_FILE_CAP_BYTES) return null;
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }
}
