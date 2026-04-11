package com.navneet.executespec.harness;

import com.navneet.executespec.agent.model.TaskDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decomposes tasks into subtasks at execution time using two strategies:
 *
 * <h3>1. File-based decomposition (multi-file tasks)</h3>
 * <p>If a task declares N owned files (N &gt; 1), creates N subtask definitions —
 * one per file — each constrained to produce exactly one {@code writeFile} call.
 * Eliminates fabrication and reduces token waste on retries.
 *
 * <h3>2. Phase-based decomposition (complex single-file tasks)</h3>
 * <p>If a task has exactly 1 owned file but MEDIUM or HIGH complexity, creates
 * 2–3 phase subtasks that build the file incrementally at the method level.
 * Phase 1 creates the skeleton; subsequent phases read the file and extend it.
 * This prevents iteration exhaustion on complex files that need many methods.
 *
 * <p>Rework tasks are excluded from both decomposition strategies because they
 * target specific reviewer-flagged files rather than generating from scratch.
 */
@Component
@RequiredArgsConstructor
public class SubtaskDecomposer {

    private final CoherenceGroupAnalyzer coherenceAnalyzer;

    /**
     * Returns {@code true} if the task should be decomposed into subtasks.
     * <p>Triggers on:
     * <ul>
     *   <li>Multi-file tasks: ownedFiles has 2+ entries</li>
     *   <li>Complex single-file tasks: ownedFiles has 1 entry + MEDIUM or HIGH complexity,
     *       but ONLY if no dependency task already owns the same file. If a dependency task
     *       owns the same file, the worker will read-and-extend it anyway, making phase
     *       decomposition redundant and inflating task count.</li>
     * </ul>
     * Excluded: rework tasks (have reworkFeedback).
     */
    public boolean shouldDecompose(TaskDefinition taskDef) {
        return shouldDecompose(taskDef, Map.of());
    }

    /**
     * Context-aware variant that skips phase decomposition when a dependency task
     * already owns the same single file (avoids redundant phasing for sequential
     * same-file chains).
     *
     * @param taskDef        the task to evaluate
     * @param taskOwnedFiles map of taskId → set of owned file paths for all tasks in the plan
     */
    public boolean shouldDecompose(TaskDefinition taskDef, Map<String, Set<String>> taskOwnedFiles) {
        if (taskDef == null) return false;
        if (taskDef.getReworkFeedback() != null && !taskDef.getReworkFeedback().isBlank()) return false;
        List<String> files = taskDef.getOwnedFiles();
        if (files == null || files.isEmpty()) return false;
        // Multi-file: always decompose (by files, not phases)
        if (files.size() > 1) return true;
        // Single-file: decompose if MEDIUM or HIGH complexity
        String complexity = taskDef.getComplexity();
        if (!"MEDIUM".equalsIgnoreCase(complexity) && !"HIGH".equalsIgnoreCase(complexity)) return false;
        // Skip phase decomposition if a dependency task already owns this same file.
        // The worker will read the existing file and extend it — no extra phases needed.
        if (!taskOwnedFiles.isEmpty() && taskDef.getDependsOn() != null) {
            String singleFile = files.get(0);
            for (String depId : taskDef.getDependsOn()) {
                Set<String> depFiles = taskOwnedFiles.get(depId);
                if (depFiles != null && depFiles.contains(singleFile)) {
                    return false; // dep task already wrote this file — skip phase decomp
                }
            }
        }
        return true;
    }

    /**
     * Decomposes a task into subtasks using the appropriate strategy.
     * <ul>
     *   <li>Multi-file (2+ owned files) → per-file subtasks via {@link #decomposeByFiles}</li>
     *   <li>Single-file + MEDIUM/HIGH → phase-based subtasks via {@link #decomposeByPhases}</li>
     * </ul>
     *
     * @param parent the original task definition
     * @return ordered list of subtask definitions
     */
    public List<TaskDefinition> decompose(TaskDefinition parent) {
        List<String> files = parent.getOwnedFiles();
        if (files.size() == 1) {
            return decomposeByPhases(parent);
        }
        return decomposeByFiles(parent);
    }

    // ── File-based decomposition (coherence-aware) ────────────────────────────

    /**
     * Creates one {@link TaskDefinition} per coherence group.
     * Related files (interface+impl, source+test, component+styles, model+migration)
     * are grouped together so cross-file consistency is maintained within a single subtask.
     * A group of up to {@link CoherenceGroupAnalyzer#MAX_GROUP_SIZE} files may produce
     * that many {@code writeFile} calls.
     */
    private List<TaskDefinition> decomposeByFiles(TaskDefinition parent) {
        List<String> files = parent.getOwnedFiles();
        List<List<String>> groups = coherenceAnalyzer.analyze(files);
        int totalGroups = groups.size();
        List<TaskDefinition> subtasks = new ArrayList<>(totalGroups);

        // Track which groups have already been emitted (for context injection)
        List<List<String>> emittedGroups = new ArrayList<>();

        for (int i = 0; i < totalGroups; i++) {
            List<String> group = groups.get(i);
            String groupLabel = group.size() == 1
                    ? extractFileName(group.get(0))
                    : group.stream().map(f -> extractFileName(f)).reduce((a, b) -> a + "+" + b).orElse("group");

            subtasks.add(TaskDefinition.builder()
                    .taskId(parent.getTaskId() + "_" + (i + 1))
                    .title(parent.getTitle() + " [" + (i + 1) + "/" + totalGroups + ": " + groupLabel + "]")
                    .objective(buildGroupSubtaskObjective(parent, group, emittedGroups, groups, i))
                    .boundaries(parent.getBoundaries())
                    .outputFormat(buildGroupOutputFormat(group))
                    .acceptanceCriteria(buildGroupAcceptanceCriteria(group))
                    .dependsOn(parent.getDependsOn())
                    .complexity("LOW")
                    .assignedModel(parent.getAssignedModel())
                    .taskLayer(parent.getTaskLayer())
                    .ownedFiles(new ArrayList<>(group))
                    .sharedFiles(parent.getSharedFiles())
                    .build());

            emittedGroups.add(group);
        }
        return subtasks;
    }

    private String buildGroupSubtaskObjective(TaskDefinition parent, List<String> group,
                                               List<List<String>> emitted,
                                               List<List<String>> allGroups, int groupIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("PARENT TASK CONTEXT:\n").append(parent.getObjective()).append("\n\n");

        // Already created files
        List<String> alreadyCreated = emitted.stream().flatMap(List::stream).toList();
        if (!alreadyCreated.isEmpty()) {
            sb.append("FILES ALREADY CREATED (available in workspace — use readFile to reference):\n");
            alreadyCreated.forEach(f -> sb.append("  - ").append(f).append("\n"));
            sb.append("\n");
        }

        // Future files
        List<String> future = allGroups.subList(groupIndex + 1, allGroups.size())
                .stream().flatMap(List::stream).toList();
        if (!future.isEmpty()) {
            sb.append("FILES TO BE CREATED LATER (by subsequent subtasks — do NOT create these):\n");
            future.forEach(f -> sb.append("  - ").append(f).append("\n"));
            sb.append("\n");
        }

        if (group.size() == 1) {
            sb.append("YOUR ASSIGNMENT: Create ONLY this file: ").append(group.get(0)).append("\n");
            sb.append("Call writeFile exactly ONCE with the complete contents of this file.\n");
        } else {
            sb.append("YOUR ASSIGNMENT: Create these ").append(group.size())
              .append(" related files together (they are coupled — implement them consistently):\n");
            group.forEach(f -> sb.append("  - ").append(f).append("\n"));
            sb.append("\nCall writeFile once per file (").append(group.size())
              .append(" calls total). Ensure imports and interfaces are consistent across the group.\n");
        }
        sb.append("Do NOT create any other files. Do NOT output a completion report until all assigned files are written.");
        return sb.toString();
    }

    private String buildGroupOutputFormat(List<String> group) {
        if (group.size() == 1) return "Create exactly ONE file: " + group.get(0);
        return "Create exactly " + group.size() + " files: " + String.join(", ", group);
    }

    private String buildGroupAcceptanceCriteria(List<String> group) {
        if (group.size() == 1) return "File " + group.get(0) + " exists and is valid";
        return "All files exist and are mutually consistent: " + String.join(", ", group);
    }

    // ── Phase-based decomposition (method-level for complex single-file tasks) ─

    /** Phase descriptions for MEDIUM complexity (2 phases). */
    private static final String[][] MEDIUM_PHASES = {
            {"Create file skeleton and core methods",
             "Write the initial structure: imports, class/component declaration, constructor/props, " +
             "type definitions, and the first core methods.\n" +
             "Do NOT try to write the entire implementation — the next phase will add remaining methods."},
            {"Complete implementation",
             "Add the remaining methods, event handlers, error handling, helper functions, " +
             "and any missing functionality to complete the file."}
    };

    /** Phase descriptions for HIGH complexity (3 phases). */
    private static final String[][] HIGH_PHASES = {
            {"Create file skeleton and type definitions",
             "Write the initial structure: imports, class/component declaration, constructor/props, " +
             "type definitions, data models, and basic setup methods.\n" +
             "Do NOT try to write the entire implementation — subsequent phases will add remaining methods."},
            {"Implement core business logic",
             "Add the core business logic methods, primary event handlers, main API calls, " +
             "and the central functionality of this file."},
            {"Finalize with remaining methods and error handling",
             "Add remaining helper methods, error handling, edge cases, validation, " +
             "cleanup logic, and any missing functionality to complete the file."}
    };

    /**
     * Creates phase-based subtasks for a complex single-file task.
     * MEDIUM → 2 phases, HIGH → 3 phases.
     * Each phase builds on the previous one by reading and extending the same file.
     */
    private List<TaskDefinition> decomposeByPhases(TaskDefinition parent) {
        String file = parent.getOwnedFiles().get(0);
        String shortName = extractFileName(file);
        String complexity = parent.getComplexity();

        String[][] phases = "HIGH".equalsIgnoreCase(complexity) ? HIGH_PHASES : MEDIUM_PHASES;
        int total = phases.length;
        List<TaskDefinition> subtasks = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            // Phase 0 inherits parent deps; each subsequent phase depends on the prior phase.
            // Without this chaining, all phases land in the same execution tier and run in
            // parallel — Phase 2 would read an empty file before Phase 1 has written anything.
            List<String> phaseDeps = (i == 0)
                    ? (parent.getDependsOn() != null ? parent.getDependsOn() : List.of())
                    : List.of(parent.getTaskId() + "_" + i);

            subtasks.add(TaskDefinition.builder()
                    .taskId(parent.getTaskId() + "_" + (i + 1))
                    .title(parent.getTitle() + " [Phase " + (i + 1) + "/" + total + ": " + shortName + "]")
                    .objective(buildPhaseObjective(parent, file, i, total, phases))
                    .boundaries(parent.getBoundaries())
                    .outputFormat(i == 0
                            ? "Create exactly ONE file: " + file
                            : "Update file: " + file + " (read existing content first, then rewrite with additions)")
                    .acceptanceCriteria("File " + file + " exists and is valid")
                    .dependsOn(phaseDeps)
                    .complexity("LOW")
                    .assignedModel(parent.getAssignedModel())
                    .taskLayer(parent.getTaskLayer())
                    .ownedFiles(List.of(file))
                    .sharedFiles(parent.getSharedFiles())
                    .build());
        }
        return subtasks;
    }

    /**
     * Builds the objective for a phase-based subtask.
     * Phase 1 creates the file; phases 2+ read and extend it.
     */
    private String buildPhaseObjective(TaskDefinition parent, String targetFile,
                                        int phaseIndex, int totalPhases, String[][] phases) {
        StringBuilder sb = new StringBuilder();

        // Parent context
        sb.append("PARENT TASK CONTEXT:\n");
        sb.append(parent.getObjective());
        sb.append("\n\n");

        // Phase assignment
        String phaseTitle = phases[phaseIndex][0];
        String phaseGuidance = phases[phaseIndex][1];

        if (phaseIndex == 0) {
            // Phase 1: create the file
            sb.append("YOUR ASSIGNMENT — PHASE 1/").append(totalPhases).append(": ").append(phaseTitle).append("\n");
            sb.append("Create the file: ").append(targetFile).append("\n");
            sb.append(phaseGuidance).append("\n");
            sb.append("Call writeFile exactly ONCE with the initial file contents.");
        } else {
            // Phase 2+: read existing file and extend
            sb.append("FILE ALREADY CREATED (read it first to see current state):\n");
            sb.append("  - ").append(targetFile).append("\n\n");
            sb.append("YOUR ASSIGNMENT — PHASE ").append(phaseIndex + 1).append("/")
              .append(totalPhases).append(": ").append(phaseTitle).append("\n");
            sb.append("Read ").append(targetFile).append(" using readFile, then rewrite it with your additions.\n");
            sb.append(phaseGuidance).append("\n");
            sb.append("Call writeFile exactly ONCE with the COMPLETE updated file ");
            sb.append("(include ALL existing code + your additions).\n");
            sb.append("Do NOT remove any existing methods — only ADD to the file.");
        }

        return sb.toString();
    }

    /** Extracts the file name from a path (e.g., "src/main/App.java" → "App.java"). */
    static String extractFileName(String path) {
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
