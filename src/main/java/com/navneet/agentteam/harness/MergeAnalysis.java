package com.navneet.agentteam.harness;

import java.util.Set;

/**
 * Result of a pre-merge conflict analysis for a single task branch.
 */
public record MergeAnalysis(
    String taskId,
    boolean hasConflicts,
    Set<String> conflictedFiles
) {

    public static MergeAnalysis clean(String taskId) {
        return new MergeAnalysis(taskId, false, Set.of());
    }

    public static MergeAnalysis conflicting(String taskId, Set<String> files) {
        return new MergeAnalysis(taskId, true, files);
    }
}
