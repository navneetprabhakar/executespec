package com.navneet.executespec.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.security.SafeWorkspacePath;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ContextAgentScanner {

  /**
   * RR-1256 — cap the number of discovered package/class candidates so a large or hostile
   * repository cannot drive unbounded heap/CPU on every run completion.
   */
  private static final int MAX_DISCOVERY_ENTRIES = 5_000;

  /** RR-1256 — bound the walk depth so a pathologically deep tree cannot stall discovery. */
  private static final int MAX_WALK_DEPTH = 64;

  /** RR-1256 — only the tail of an ever-growing decisions journal is loaded before selection. */
  private static final int MAX_DECISIONS_TAIL_CHARS = 64_000;

  public ContextAgentInput scan(
      PipelineRun run,
      List<WorkerReport> workerReports,
      ReviewReport reviewReport,
      Path projectPath)
      throws IOException {
    String currentDoc = readContextDoc(projectPath);

    GitDiff diff = buildGitDiff(projectPath, run);
    List<String> workerSummaries = summariseWorkerReports(workerReports);
    String reviewerSummary = summariseReviewReport(reviewReport);
    String decisions = readRecentDecisions(projectPath);
    return new ContextAgentInput(
        currentDoc,
        diff.summary(),
        detectNewPackages(projectPath, currentDoc),
        detectNewFiles(projectPath, currentDoc),
        workerSummaries,
        reviewerSummary,
        decisions,
        run.getRunId(),
        run.getUserRequest(),
        "", // Context Agent does not infer a hypothesis; preserve an explicit empty value.
        diff.changedFiles(),
        failures(workerSummaries, reviewerSummary),
        decisionSections(decisions));
  }

  /** Display summary plus the typed changed-file list derived from the same JGit diff entries. */
  private record GitDiff(String summary, List<String> changedFiles) {}

  /**
   * RR-1255 — derive the structured changed-file list from the typed {@link
   * org.eclipse.jgit.diff.DiffEntry} entries directly, mapping every {@code ChangeType}, instead of
   * re-parsing the human display string. The display emitted full enum names (ADD/MODIFY/DELETE/
   * RENAME/COPY) while the old parser accepted only lines starting {@code "A "}/{@code "M "}/{@code
   * "D "}, so changedFiles was always empty and handoff/reload consumers received no file list.
   */
  private GitDiff buildGitDiff(Path projectPath, PipelineRun run) {
    try (var git = org.eclipse.jgit.api.Git.open(projectPath.toFile())) {
      List<String> lines = new ArrayList<>();
      lines.add("Files changed in run " + run.getRunId() + ":");
      List<String> changed = new ArrayList<>();
      for (var entry : git.diff().call()) {
        var type = entry.getChangeType();
        String path = changedPath(entry);
        lines.add("  " + type.name() + " " + path);
        if (path != null && !path.isBlank() && !"/dev/null".equals(path)) changed.add(path);
      }
      return new GitDiff(String.join("\n", lines), changed.stream().distinct().toList());
    } catch (Exception e) {
      log.warn("Could not build git diff for run {}: {}", run.getRunId(), e.getMessage());
      return new GitDiff("Git diff unavailable", List.of());
    }
  }

  private static String changedPath(org.eclipse.jgit.diff.DiffEntry entry) {
    return switch (entry.getChangeType()) {
      case DELETE -> entry.getOldPath();
      default -> entry.getNewPath();
    };
  }

  private List<String> detectNewPackages(Path projectPath, String currentDoc) throws IOException {
    Path srcPath = projectPath.resolve("src/main/java");
    if (!Files.exists(srcPath)) return List.of();

    // RR-1256 — close the walk stream (try-with-resources) and bound depth + count.
    try (var walk = Files.walk(srcPath, MAX_WALK_DEPTH)) {
      return walk.filter(Files::isDirectory)
          .map(p -> srcPath.relativize(p).toString().replace("/", "."))
          .filter(pkg -> !pkg.isEmpty() && !currentDoc.contains(pkg))
          .sorted()
          .limit(MAX_DISCOVERY_ENTRIES)
          .collect(Collectors.toList());
    }
  }

  private List<String> detectNewFiles(Path projectPath, String currentDoc) throws IOException {
    Path srcPath = projectPath.resolve("src/main/java");
    if (!Files.exists(srcPath)) return List.of();

    // RR-1256 — close the walk stream (try-with-resources) and bound depth + count.
    try (var walk = Files.walk(srcPath, MAX_WALK_DEPTH)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .map(p -> p.getFileName().toString().replace(".java", ""))
          .filter(name -> !currentDoc.contains(name))
          .sorted()
          .limit(MAX_DISCOVERY_ENTRIES)
          .collect(Collectors.toList());
    }
  }

  private List<String> summariseWorkerReports(List<WorkerReport> reports) {
    if (reports == null) return List.of();
    return reports.stream()
        .map(
            r ->
                "Task ["
                    + r.getTaskId()
                    + "]: "
                    + r.getSelfReview()
                    + (r.getFilesModified() != null && !r.getFilesModified().isEmpty()
                        ? " | Modified: " + String.join(", ", r.getFilesModified())
                        : ""))
        .collect(Collectors.toList());
  }

  private String summariseReviewReport(ReviewReport report) {
    if (report == null) return "No reviewer report available";
    return "Verdict: "
        + report.getRecommendationCompat()
        + " | Summary: "
        + report.getSummary()
        + (report.getMergeBlockers() != null && !report.getMergeBlockers().isEmpty()
            ? " | Blockers: " + String.join(", ", report.getMergeBlockers())
            : "");
  }

  /**
   * Resolve a framework-reserved metadata file inside a customer-controlled checkout, or return
   * empty when the repository shipped it as a symlink (RR-1253). Following such a link would read a
   * host file — credentials, service config, anything the server account can read — and the full
   * contents flow straight into the provider prompt. Trust-boundary labelling does not undo
   * disclosure, so the read is refused rather than sanitized.
   */
  private String readGuarded(Path projectPath, String relativePath) throws IOException {
    Path p;
    try {
      p = SafeWorkspacePath.resolveWithin(projectPath, relativePath);
    } catch (SecurityException e) {
      log.warn(
          "[ContextAgentScanner] refused unsafe context path {}: {}", relativePath, e.getMessage());
      return "";
    }
    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return "";
    return Files.readString(p);
  }

  private String readContextDoc(Path projectPath) throws IOException {
    return readGuarded(projectPath, ".agent-context/CODEBASE_CONTEXT.md");
  }

  private String readRecentDecisions(Path projectPath) throws IOException {
    String content = readGuarded(projectPath, ".agent-context/decisions.md");
    if (content.isEmpty()) return "No decisions recorded yet";
    // RR-1256 — the journal grows without bound; only the tail can hold the recent decisions we
    // keep, so cap the bytes considered before splitting to avoid unbounded heap on every run.
    if (content.length() > MAX_DECISIONS_TAIL_CHARS) {
      content = content.substring(content.length() - MAX_DECISIONS_TAIL_CHARS);
    }
    String[] sections = content.split("(?=## )");
    int from = Math.max(0, sections.length - 5);
    return String.join("", Arrays.copyOfRange(sections, from, sections.length));
  }

  private List<String> failures(List<String> workerSummaries, String reviewerSummary) {
    List<String> failures = new ArrayList<>();
    if (workerSummaries != null) {
      workerSummaries.stream().filter(this::looksLikeFailure).forEach(failures::add);
    }
    if (looksLikeFailure(reviewerSummary)) failures.add(reviewerSummary);
    return failures.stream().distinct().toList();
  }

  private boolean looksLikeFailure(String value) {
    if (value == null || value.isBlank()) return false;
    String lower = value.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("fail")
        || lower.contains("error")
        || lower.contains("blocker")
        || lower.contains("unresolved");
  }

  private List<String> decisionSections(String decisions) {
    if (decisions == null || decisions.isBlank()) return List.of();
    return Arrays.stream(decisions.split("(?=## )"))
        .map(String::strip)
        .filter(value -> !value.isBlank())
        .toList();
  }
}
