package com.navneet.executespec.harness;
import com.navneet.executespec.config.ModelConstants;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ConflictResolverAgent;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.harness.merge.FileMergerRegistry;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.MergeConflict;
import com.navneet.executespec.state.repository.MergeConflictRepository;
import com.navneet.executespec.tool.GitTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.MergeResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Merges task branches into main in topological order with a multi-tier
 * conflict resolution cascade:
 *
 * <ol>
 *   <li>SpecializedMerger (pom.xml, YAML, package.json) — deterministic, zero LLM cost</li>
 *   <li>ConflictResolverAgent (Haiku) — LLM-powered, cheap</li>
 *   <li>ConflictResolverAgent (Sonnet) — escalation for complex conflicts</li>
 *   <li>FAILED — human retry via merge gate</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitMergeService {

    private final GitTool gitTool;
    private final ObjectMapper mapper;
    private final FileMergerRegistry mergerRegistry;
    private final ConflictResolverAgent conflictResolver;
    private final SseBroadcaster broadcaster;
    private final MergeConflictRepository conflictRepo;

    /**
     * Result of merging a single task branch.
     */
    public record TaskMergeResult(String taskId, String branchName, MergeOutcome outcome,
                                   List<String> resolvedFiles, List<String> failedFiles) {

        public static TaskMergeResult clean(String taskId, String branch) {
            return new TaskMergeResult(taskId, branch, MergeOutcome.CLEAN, List.of(), List.of());
        }

        public static TaskMergeResult resolved(String taskId, String branch, MergeOutcome outcome,
                                                List<String> resolved) {
            return new TaskMergeResult(taskId, branch, outcome, resolved, List.of());
        }

        public static TaskMergeResult failed(String taskId, String branch, List<String> failed) {
            return new TaskMergeResult(taskId, branch, MergeOutcome.FAILED, List.of(), failed);
        }
    }

    /**
     * Merges all task branches into main in topological order.
     * Attempts auto-resolution for conflicts before giving up.
     *
     * @return list of branch names that had unresolvable merge conflicts (may be empty)
     */
    public List<String> mergeAll(String runId, List<AgentTask> tasks) {
        List<AgentTask> ordered = topologicalSort(tasks);
        List<String> conflicted = new ArrayList<>();
        List<AgentTask> previouslyMerged = new ArrayList<>();

        log.info("Merging {} branches to main with conflict resolution", ordered.size());
        for (AgentTask task : ordered) {
            if (task.getBranchName() == null || task.getBranchName().isBlank()
                || "FAILED".equals(task.getStatus()))
                continue;

            TaskMergeResult result = mergeWithRetry(runId, task, previouslyMerged);

            switch (result.outcome()) {
                case CLEAN -> {
                    log.info("  Merged {} (clean)", task.getBranchName());
                    previouslyMerged.add(task);
                }
                case RESOLVED_DETERMINISTIC, RESOLVED_LLM -> {
                    log.info("  Merged {} (auto-resolved: {} files)", task.getBranchName(),
                        result.resolvedFiles().size());
                    broadcaster.emit(runId, RunEvent.log(runId, "merge",
                        "✅ Auto-resolved merge conflict for " + task.getTaskId()
                        + " in: " + String.join(", ", result.resolvedFiles())));
                    previouslyMerged.add(task);
                }
                case ESCALATED -> {
                    log.info("  Merged {} (escalated to Sonnet: {} files)", task.getBranchName(),
                        result.resolvedFiles().size());
                    broadcaster.emit(runId, RunEvent.log(runId, "merge",
                        "⬆️ Escalated and resolved merge conflict for " + task.getTaskId()
                        + " in: " + String.join(", ", result.resolvedFiles())));
                    previouslyMerged.add(task);
                }
                case FAILED -> {
                    log.warn("  Merge FAILED for {} — unresolvable conflicts in: {}",
                        task.getBranchName(), result.failedFiles());
                    broadcaster.emit(runId, RunEvent.log(runId, "merge",
                        "❌ Unresolvable merge conflict for " + task.getTaskId()
                        + " in: " + String.join(", ", result.failedFiles())));
                    conflicted.add(task.getBranchName());
                }
            }
        }

        gitTool.tagRun(runId);
        return conflicted;
    }

    private static final int MAX_MERGE_RETRIES = 2;

    /**
     * Public entry point for progressive merge (called by WorkerPool).
     * Merges a single task branch into main with the full resolution cascade.
     * Retries up to {@link #MAX_MERGE_RETRIES} times on transient failures
     * (JGit errors, lock contention) before giving up.
     */
    public TaskMergeResult mergeTaskBranchWithResolution(String runId, AgentTask task,
                                                          List<AgentTask> previouslyMerged) {
        TaskMergeResult result = null;
        for (int attempt = 0; attempt <= MAX_MERGE_RETRIES; attempt++) {
            result = mergeTaskBranch(runId, task, previouslyMerged);
            if (result.outcome() != MergeOutcome.FAILED) {
                return result;
            }
            // Check if the failure is transient (JGit/IO error, not a true conflict)
            boolean isTransient = result.failedFiles().stream()
                .anyMatch(f -> f.startsWith("merge status:") || f.startsWith("finalize merge")
                    || f.equals("unknown conflict") || f.contains("ERROR"));
            if (!isTransient) {
                // Real conflict — retrying won't help
                return result;
            }
            if (attempt < MAX_MERGE_RETRIES) {
                log.warn("Merge attempt {} failed for task {} (transient) — retrying in 500ms",
                    attempt + 1, task.getTaskId());
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * Merges a single task branch into main with the full resolution cascade.
     */
    private TaskMergeResult mergeTaskBranch(String runId, AgentTask task,
                                             List<AgentTask> previouslyMerged) {
        String branchName = task.getBranchName();

        // Guard: no branch means nothing to merge (task produced no commits)
        if (branchName == null || branchName.isBlank()) {
            log.info("Task {} has no branch — skipping merge", task.getTaskId());
            return TaskMergeResult.clean(task.getTaskId(), "(no branch)");
        }

        // Rebase copilot and review task branches onto main before merging
        // to ensure they are current relative to concurrently merged tasks.
        if (task.getTaskId() != null && 
           (task.getTaskId().contains("_cop") || task.getTaskId().contains("_rev"))) {
            log.info("Attempting to rebase branch {} onto main", branchName);
            boolean rebased = gitTool.rebaseBranchOnMain(runId, branchName);
            if (!rebased) {
                log.info("Skipping fast-forward rebase for {} due to conflict; falling back to normal merge.", branchName);
            }
        }

        // Step 1: Try normal merge (no-commit so we can inspect)
        MergeResult mergeResult = gitTool.mergeBranchNoCommit(runId, branchName);
        if (mergeResult == null) {
            // JGit error — fall back to simple merge
            return fallbackSimpleMerge(runId, task);
        }

        if (mergeResult.getMergeStatus().isSuccessful()) {
            // Clean merge — commit it
            gitTool.commitMerge(runId, "chore: merge " + branchName);
            return TaskMergeResult.clean(task.getTaskId(), branchName);
        }

        if (mergeResult.getMergeStatus() != MergeResult.MergeStatus.CONFLICTING) {
            // Non-conflict failure (e.g., ALREADY_UP_TO_DATE, ABORTED, etc.)
            gitTool.abortMerge(runId);
            return TaskMergeResult.failed(task.getTaskId(), branchName,
                List.of("merge status: " + mergeResult.getMergeStatus()));
        }

        // Step 2: We have conflicts — try to resolve each conflicted file
        Map<String, int[][]> conflicts = mergeResult.getConflicts();
        if (conflicts == null || conflicts.isEmpty()) {
            gitTool.abortMerge(runId);
            return TaskMergeResult.failed(task.getTaskId(), branchName, List.of("unknown conflict"));
        }

        Set<String> conflictedFiles = conflicts.keySet();
        log.info("Merge conflict for task {} on {} files: {}", task.getTaskId(),
            conflictedFiles.size(), conflictedFiles);

        // Abort the conflicted merge state so we can read clean versions
        gitTool.abortMerge(runId);

        // Find the merge base between main and the task branch
        String mergeBase = gitTool.getMergeBase(runId, "main", branchName);

        List<String> resolvedFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        MergeOutcome highestOutcome = MergeOutcome.RESOLVED_DETERMINISTIC;

        for (String conflictPath : conflictedFiles) {
            // Read three versions
            String baseContent = mergeBase != null
                ? gitTool.readFileFromRef(runId, mergeBase, conflictPath) : "";
            String oursContent = gitTool.readFileFromRef(runId, "main", conflictPath);
            String theirsContent = gitTool.readFileFromRef(runId, branchName, conflictPath);

            if (baseContent == null) baseContent = "";
            if (oursContent == null) oursContent = "";
            if (theirsContent == null) theirsContent = "";

            // Tier 1: SpecializedMerger (deterministic, no LLM)
            Optional<String> deterministicResult = mergerRegistry.tryMerge(
                conflictPath, baseContent, oursContent, theirsContent);

            if (deterministicResult.isPresent()) {
                writeResolvedFile(runId, conflictPath, deterministicResult.get());
                resolvedFiles.add(conflictPath);
                recordConflict(runId, task.getTaskId(), conflictPath, "TEXTUAL",
                    "DETERMINISTIC", null);
                continue;
            }

            // Tier 2: ConflictResolverAgent (Haiku)
            String taskADesc = buildMergedTaskDescription(previouslyMerged);
            String taskBDesc = getTaskGoal(task);

            String haikuResult = conflictResolver.resolveConflict(
                runId, conflictPath, oursContent, theirsContent,
                baseContent, taskADesc, taskBDesc);

            if (haikuResult != null && !haikuResult.isBlank()) {
                writeResolvedFile(runId, conflictPath, haikuResult);
                resolvedFiles.add(conflictPath);
                if (highestOutcome == MergeOutcome.RESOLVED_DETERMINISTIC) {
                    highestOutcome = MergeOutcome.RESOLVED_LLM;
                }
                recordConflict(runId, task.getTaskId(), conflictPath, "TEXTUAL",
                    "AUTO_HAIKU", ModelConstants.HAIKU);
                continue;
            }

            // Tier 3: ConflictResolverAgent (Sonnet escalation)
            String sonnetResult = conflictResolver.resolveWithEscalation(
                runId, conflictPath, oursContent, theirsContent,
                baseContent, taskADesc, taskBDesc);

            if (sonnetResult != null && !sonnetResult.isBlank()) {
                writeResolvedFile(runId, conflictPath, sonnetResult);
                resolvedFiles.add(conflictPath);
                highestOutcome = MergeOutcome.ESCALATED;
                recordConflict(runId, task.getTaskId(), conflictPath, "TEXTUAL",
                    "AUTO_SONNET", ModelConstants.SONNET);
                continue;
            }

            // Tier 4: FAILED — could not resolve
            failedFiles.add(conflictPath);
            recordConflict(runId, task.getTaskId(), conflictPath, "TEXTUAL",
                "FAILED", null);
        }

        if (!failedFiles.isEmpty()) {
            // Could not resolve all files — don't merge this branch
            return TaskMergeResult.failed(task.getTaskId(), branchName, failedFiles);
        }

        // All files resolved — now re-merge with the resolved files applied
        return finalizeMerge(runId, task, branchName, resolvedFiles, highestOutcome);
    }

    /**
     * Wraps mergeTaskBranch with retry logic for transient failures.
     * Used by the batch mergeAll path.
     */
    private TaskMergeResult mergeWithRetry(String runId, AgentTask task,
                                            List<AgentTask> previouslyMerged) {
        TaskMergeResult result = null;
        for (int attempt = 0; attempt <= MAX_MERGE_RETRIES; attempt++) {
            result = mergeTaskBranch(runId, task, previouslyMerged);
            if (result.outcome() != MergeOutcome.FAILED) return result;
            boolean isTransient = result.failedFiles().stream()
                .anyMatch(f -> f.startsWith("merge status:") || f.startsWith("finalize merge")
                    || f.equals("unknown conflict") || f.contains("ERROR"));
            if (!isTransient) return result;
            if (attempt < MAX_MERGE_RETRIES) {
                log.warn("Batch merge attempt {} failed for task {} (transient) — retrying",
                    attempt + 1, task.getTaskId());
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * After resolving all conflicts, re-do the merge and apply resolved files.
     */
    private TaskMergeResult finalizeMerge(String runId, AgentTask task, String branchName,
                                           List<String> resolvedFiles, MergeOutcome outcome) {
        // Re-attempt the merge (no-commit)
        MergeResult retry = gitTool.mergeBranchNoCommit(runId, branchName);
        if (retry == null) {
            return TaskMergeResult.failed(task.getTaskId(), branchName,
                List.of("finalize merge failed"));
        }

        // Whether it's conflicting or not, write our resolved versions and stage them
        Path wsPath = gitTool.getWorkspacePath(runId);
        for (String file : resolvedFiles) {
            // The file was already written to the workspace — just stage it
            gitTool.stageFile(runId, file);
        }

        gitTool.commitMerge(runId, "merge: " + branchName + " (auto-resolved)");
        return TaskMergeResult.resolved(task.getTaskId(), branchName, outcome, resolvedFiles);
    }

    /**
     * Writes resolved content to the workspace and stages it.
     */
    private void writeResolvedFile(String runId, String filePath, String content) {
        try {
            Path wsPath = gitTool.getWorkspacePath(runId);
            Path target = wsPath.resolve(filePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            log.error("Failed to write resolved file {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Fallback to simple merge (original behavior) when JGit advanced merge fails.
     */
    private TaskMergeResult fallbackSimpleMerge(String runId, AgentTask task) {
        String result = gitTool.mergeBranchToMain(runId, task.getBranchName());
        if (result != null && result.startsWith("ERROR")) {
            gitTool.abortMerge(runId);
            return TaskMergeResult.failed(task.getTaskId(), task.getBranchName(),
                List.of(result));
        }
        return TaskMergeResult.clean(task.getTaskId(), task.getBranchName());
    }

    /**
     * Records a merge conflict in the database for analytics.
     */
    private void recordConflict(String runId, String taskId, String filePath,
                                 String conflictType, String resolution, String resolverModel) {
        try {
            conflictRepo.save(MergeConflict.builder()
                .runId(runId)
                .taskId(taskId)
                .filePath(filePath)
                .conflictType(conflictType)
                .resolution(resolution)
                .resolverModel(resolverModel)
                .resolvedAt("FAILED".equals(resolution) ? null : Instant.now())
                .build());
        } catch (Exception e) {
            log.warn("Failed to record merge conflict: {}", e.getMessage());
        }
    }

    /**
     * Builds a description of what tasks already merged to main have done.
     */
    private String buildMergedTaskDescription(List<AgentTask> mergedTasks) {
        if (mergedTasks.isEmpty()) return "No prior tasks merged.";
        StringBuilder sb = new StringBuilder();
        for (AgentTask t : mergedTasks) {
            TaskDefinition def = parseTaskDef(t.getTaskJson());
            sb.append("- ").append(def.getTitle()).append(": ").append(def.getObjective()).append("\n");
        }
        return sb.toString();
    }

    private String getTaskGoal(AgentTask task) {
        TaskDefinition def = parseTaskDef(task.getTaskJson());
        return def.getTitle() + ": " + def.getObjective();
    }

    // ── Topological sort (Kahn's algorithm) ───────────────────────────────────

    private List<AgentTask> topologicalSort(List<AgentTask> tasks) {
        Map<String, AgentTask> byId = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (AgentTask t : tasks) {
            byId.put(t.getTaskId(), t);
            inDegree.put(t.getTaskId(), 0);
            dependents.put(t.getTaskId(), new ArrayList<>());
        }
        for (AgentTask t : tasks) {
            TaskDefinition def = parseTaskDef(t.getTaskJson());
            if (def.getDependsOn() != null) {
                for (String dep : def.getDependsOn()) {
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(t.getTaskId());
                    inDegree.merge(t.getTaskId(), 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        inDegree.forEach((id, deg) -> {
            if (deg == 0) queue.add(id);
        });

        List<AgentTask> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            AgentTask task = byId.get(id);
            if (task != null) sorted.add(task);
            List<String> deps = dependents.getOrDefault(id, List.of());
            for (String dep : deps) {
                inDegree.merge(dep, -1, Integer::sum);
                if (inDegree.get(dep) == 0) queue.add(dep);
            }
        }
        return sorted;
    }

    private TaskDefinition parseTaskDef(String json) {
        try {
            return mapper.readValue(json, TaskDefinition.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot parse task definition", e);
        }
    }
}
