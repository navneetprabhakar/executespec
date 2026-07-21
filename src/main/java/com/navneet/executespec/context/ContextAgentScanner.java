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

  public ContextAgentInput scan(
      PipelineRun run,
      List<WorkerReport> workerReports,
      ReviewReport reviewReport,
      Path projectPath)
      throws IOException {
    String currentDoc = readContextDoc(projectPath);

    String diffSummary = buildGitDiffSummary(projectPath, run);
    List<String> workerSummaries = summariseWorkerReports(workerReports);
    String reviewerSummary = summariseReviewReport(reviewReport);
    String decisions = readRecentDecisions(projectPath);
    return new ContextAgentInput(
        currentDoc,
        diffSummary,
        detectNewPackages(projectPath, currentDoc),
        detectNewFiles(projectPath, currentDoc),
        workerSummaries,
        reviewerSummary,
        decisions,
        run.getRunId(),
        run.getUserRequest(),
        "", // Context Agent does not infer a hypothesis; preserve an explicit empty value.
        changedFiles(diffSummary),
        failures(workerSummaries, reviewerSummary),
        decisionSections(decisions));
  }

  private String buildGitDiffSummary(Path projectPath, PipelineRun run) {
    try (var git = org.eclipse.jgit.api.Git.open(projectPath.toFile())) {
      List<String> lines = new ArrayList<>();
      lines.add("Files changed in run " + run.getRunId() + ":");
      git.diff()
          .call()
          .forEach(
              entry -> lines.add("  " + entry.getChangeType().name() + " " + entry.getNewPath()));
      return String.join("\n", lines);
    } catch (Exception e) {
      log.warn("Could not build git diff for run {}: {}", run.getRunId(), e.getMessage());
      return "Git diff unavailable";
    }
  }

  private List<String> detectNewPackages(Path projectPath, String currentDoc) throws IOException {
    Path srcPath = projectPath.resolve("src/main/java");
    if (!Files.exists(srcPath)) return List.of();

    return Files.walk(srcPath)
        .filter(Files::isDirectory)
        .map(p -> srcPath.relativize(p).toString().replace("/", "."))
        .filter(pkg -> !pkg.isEmpty() && !currentDoc.contains(pkg))
        .sorted()
        .collect(Collectors.toList());
  }

  private List<String> detectNewFiles(Path projectPath, String currentDoc) throws IOException {
    Path srcPath = projectPath.resolve("src/main/java");
    if (!Files.exists(srcPath)) return List.of();

    return Files.walk(srcPath)
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".java"))
        .map(p -> p.getFileName().toString().replace(".java", ""))
        .filter(name -> !currentDoc.contains(name))
        .sorted()
        .collect(Collectors.toList());
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
    String[] sections = content.split("(?=## )");
    int from = Math.max(0, sections.length - 5);
    return String.join("", Arrays.copyOfRange(sections, from, sections.length));
  }

  private List<String> changedFiles(String diffSummary) {
    if (diffSummary == null || diffSummary.isBlank()) return List.of();
    return diffSummary
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith("A ") || line.startsWith("M ") || line.startsWith("D "))
        .map(line -> line.substring(2).strip())
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
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
