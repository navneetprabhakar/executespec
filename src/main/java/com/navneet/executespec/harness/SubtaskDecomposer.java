package com.navneet.executespec.harness;

import com.navneet.executespec.agent.model.TaskDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes multi-file tasks into per-file subtasks at execution time.
 *
 * <p>If a task declares N owned files (N &gt; 1), this component creates N
 * synthetic {@link TaskDefinition} objects — one per file — each constrained
 * to produce exactly one {@code writeFile} call. This eliminates fabrication
 * (model claiming files were written without tool calls) and drastically
 * reduces token waste on retries.
 *
 * <p>Rework tasks are excluded from decomposition because they target
 * specific reviewer-flagged files rather than generating from scratch.
 */
@Component
@RequiredArgsConstructor
public class SubtaskDecomposer {

    /**
     * Returns {@code true} if the task should be decomposed into subtasks.
     * Criteria: ownedFiles has 2+ entries AND task is not in rework mode.
     */
    public boolean shouldDecompose(TaskDefinition taskDef) {
        if (taskDef == null) return false;
        if (taskDef.getReworkFeedback() != null && !taskDef.getReworkFeedback().isBlank()) return false;
        List<String> files = taskDef.getOwnedFiles();
        return files != null && files.size() > 1;
    }

    /**
     * Creates N {@link TaskDefinition} objects, one per owned file.
     * Each subtask inherits the parent's context but is constrained to a single file.
     *
     * @param parent the original multi-file task definition
     * @return ordered list of per-file subtask definitions
     */
    public List<TaskDefinition> decompose(TaskDefinition parent) {
        List<String> files = parent.getOwnedFiles();
        int total = files.size();
        List<TaskDefinition> subtasks = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            String file = files.get(i);
            String shortName = extractFileName(file);

            subtasks.add(TaskDefinition.builder()
                    .taskId(parent.getTaskId() + "_" + (i + 1))
                    .title(parent.getTitle() + " [" + (i + 1) + "/" + total + ": " + shortName + "]")
                    .objective(buildSubtaskObjective(parent, file, i, files))
                    .boundaries(parent.getBoundaries())
                    .outputFormat("Create exactly ONE file: " + file)
                    .acceptanceCriteria("File " + file + " exists and is valid")
                    .dependsOn(parent.getDependsOn())
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
     * Builds a focused subtask objective that includes:
     * 1. The parent task's full objective (context)
     * 2. Which specific file to create
     * 3. Which files were already created by prior subtasks (for imports/references)
     * 4. Explicit single-file constraint
     */
    private String buildSubtaskObjective(TaskDefinition parent, String targetFile,
                                         int index, List<String> allFiles) {
        StringBuilder sb = new StringBuilder();

        // Parent context
        sb.append("PARENT TASK CONTEXT:\n");
        sb.append(parent.getObjective());
        sb.append("\n\n");

        // Prior files (available in workspace for reference)
        if (index > 0) {
            sb.append("FILES ALREADY CREATED (available in workspace — use readFile if you need to reference them):\n");
            for (int j = 0; j < index; j++) {
                sb.append("  - ").append(allFiles.get(j)).append("\n");
            }
            sb.append("\n");
        }

        // Remaining files (for awareness, not for this subtask)
        if (index < allFiles.size() - 1) {
            sb.append("FILES TO BE CREATED LATER (by subsequent subtasks — do NOT create these):\n");
            for (int j = index + 1; j < allFiles.size(); j++) {
                sb.append("  - ").append(allFiles.get(j)).append("\n");
            }
            sb.append("\n");
        }

        // Specific assignment
        sb.append("YOUR ASSIGNMENT: Create ONLY this file: ").append(targetFile).append("\n");
        sb.append("Call writeFile exactly ONCE with the complete contents of this file.\n");
        sb.append("Do NOT create any other files. Do NOT output a completion report until the file is written.");

        return sb.toString();
    }

    /** Extracts the file name from a path (e.g., "src/main/App.java" → "App.java"). */
    static String extractFileName(String path) {
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
