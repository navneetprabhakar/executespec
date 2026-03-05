package com.navneet.agentteam.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.model.TaskDefinition;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.tool.GitTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitMergeService {

    private final GitTool gitTool;
    private final ObjectMapper mapper;

    /**
     * Merges all task branches into main in topological order.
     *
     * <p>If a branch has a merge conflict, the conflict is aborted and that branch
     * is skipped rather than failing the entire run. The returned list contains the
     * branch names that could not be merged due to conflicts — an empty list means
     * all branches merged cleanly.
     *
     * @return list of branch names that had merge conflicts (may be empty)
     */
    public List<String> mergeAll(String runId, List<AgentTask> tasks) {
        // Sort tasks by dependency order (topological sort)
        List<AgentTask> ordered = topologicalSort(tasks);
        List<String> conflicted = new ArrayList<>();

        log.info("Merging {} branches to main", ordered.size());
        for (AgentTask task : ordered) {
            if (task.getBranchName() == null || "FAILED".equals(task.getStatus()))
                continue;

            String result = gitTool.mergeBranchToMain(runId, task.getBranchName());
            if (result.startsWith("ERROR")) {
                log.warn("Merge conflict on {} — aborting and skipping: {}", task.getBranchName(), result);
                gitTool.abortMerge(runId);
                conflicted.add(task.getBranchName());
            } else {
                log.info("  Merged {}", task.getBranchName());
            }
        }

        // Tag the run's workspace repo after all branches are merged (non-fatal)
        gitTool.tagRun(runId);
        return conflicted;
    }

    private List<AgentTask> topologicalSort(List<AgentTask> tasks) {
        // Kahn's algorithm
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
            if (deg == 0)
                queue.add(id);
        });

        List<AgentTask> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            AgentTask task = byId.get(id);
            if (task != null)
                sorted.add(task);
            List<String> deps = dependents.getOrDefault(id, List.of());
            for (String dep : deps) {
                inDegree.merge(dep, -1, Integer::sum);
                if (inDegree.get(dep) == 0)
                    queue.add(dep);
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
