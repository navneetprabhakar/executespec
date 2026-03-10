package com.navneet.executespec.context;

import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ContextAgentScanner {

    @Value("${agent.workspace}")
    private String workspaceRoot;

    public ContextAgentInput scan(
        PipelineRun run,
        List<WorkerReport> workerReports,
        ReviewReport reviewReport
    ) throws IOException {
        Path projectPath = Path.of(workspaceRoot);
        String currentDoc = readContextDoc(projectPath);

        return new ContextAgentInput(
            currentDoc,
            buildGitDiffSummary(projectPath, run),
            detectNewPackages(projectPath, currentDoc),
            detectNewFiles(projectPath, currentDoc),
            summariseWorkerReports(workerReports),
            summariseReviewReport(reviewReport),
            readRecentDecisions(projectPath),
            run.getRunId(),
            run.getUserRequest()
        );
    }

    private String buildGitDiffSummary(Path projectPath, PipelineRun run) {
        try (var git = org.eclipse.jgit.api.Git.open(projectPath.toFile())) {
            List<String> lines = new ArrayList<>();
            lines.add("Files changed in run " + run.getRunId() + ":");
            git.diff().call().forEach(entry ->
                lines.add("  " + entry.getChangeType().name() + " " + entry.getNewPath())
            );
            return String.join("\n", lines);
        } catch (Exception e) {
            log.warn("Could not build git diff for run {}: {}", run.getRunId(), e.getMessage());
            return "Git diff unavailable";
        }
    }

    private List<String> detectNewPackages(Path projectPath, String currentDoc)
        throws IOException {
        Path srcPath = projectPath.resolve("src/main/java");
        if (!Files.exists(srcPath)) return List.of();

        return Files.walk(srcPath)
            .filter(Files::isDirectory)
            .map(p -> srcPath.relativize(p).toString().replace("/", "."))
            .filter(pkg -> !pkg.isEmpty() && !currentDoc.contains(pkg))
            .sorted()
            .collect(Collectors.toList());
    }

    private List<String> detectNewFiles(Path projectPath, String currentDoc)
        throws IOException {
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
            .map(r -> "Task [" + r.getTaskId() + "]: " + r.getSelfReview() +
                (r.getFilesModified() != null && !r.getFilesModified().isEmpty()
                    ? " | Modified: " + String.join(", ", r.getFilesModified()) : ""))
            .collect(Collectors.toList());
    }

    private String summariseReviewReport(ReviewReport report) {
        if (report == null) return "No reviewer report available";
        return "Verdict: " + report.getRecommendation() + " | Summary: " + report.getSummary() +
            (report.getMergeBlockers() != null && !report.getMergeBlockers().isEmpty()
                ? " | Blockers: " + String.join(", ", report.getMergeBlockers()) : "");
    }

    private String readContextDoc(Path projectPath) throws IOException {
        Path p = projectPath.resolve(".agent-context/CODEBASE_CONTEXT.md");
        return Files.exists(p) ? Files.readString(p) : "";
    }

    private String readRecentDecisions(Path projectPath) throws IOException {
        Path p = projectPath.resolve(".agent-context/decisions.md");
        if (!Files.exists(p)) return "No decisions recorded yet";
        String content = Files.readString(p);
        String[] sections = content.split("(?=## )");
        int from = Math.max(0, sections.length - 5);
        return String.join("", Arrays.copyOfRange(sections, from, sections.length));
    }
}
