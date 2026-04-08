package com.navneet.executespec.batch;

import com.navneet.executespec.agent.model.TaskDefinition;

import java.util.List;
import java.util.Map;

/**
 * Result of {@link BatchRequestBuilder#buildRequests} — includes batch requests
 * and decomposition metadata so the caller can create subtask DB rows.
 *
 * @param requests           The batch requests to submit (one per task or subtask)
 * @param decomposedTasks    Map of parent taskId → list of subtask definitions (only multi-file tasks)
 */
public record BatchBuildResult(
        List<BatchRequest> requests,
        Map<String, List<TaskDefinition>> decomposedTasks
) {}
