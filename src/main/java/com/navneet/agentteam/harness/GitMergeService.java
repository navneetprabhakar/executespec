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

    public void mergeAll(String runId, List<AgentTask> tasks) {
        // Sort tasks by dependency order (topological sort)
        List<AgentTask> ordered = topologicalSort(tasks);

        log.info("Merging {} branches to main", ordered.size());
        for (AgentTask task : ordered) {
            if (task.getBranchName() == null || "FAILED".equals(task.getStatus()))
                continue;

            // ── Tier 3: enforce README updated before merge ───────────────────
            enforceReadmeUpdated(runId, task.getBranchName());

            String result = gitTool.mergeBranchToMain(runId, task.getBranchName());
            if (result.startsWith("ERROR")) {
                log.error("Merge failed for {}: {}", task.getBranchName(), result);
                throw new RuntimeException("Merge conflict on " + task.getBranchName() +
                        ". Resolve manually or reject and rework.");
            }
            log.info("  Merged {}", task.getBranchName());
        }

        // Tag the run's workspace repo after all branches are merged (non-fatal)
        gitTool.tagRun(runId);
    }

    /**
     * Enforces that README.md was touched on the given branch vs main.
     * Throws MergeBlockedException if it wasn't updated.
     */
    private void enforceReadmeUpdated(String runId, String branchName) {
        String diff = gitTool.getFullDiff(runId, "main", branchName);
        boolean touched = diff.toLowerCase().contains("readme.md");
        if (!touched) {
            throw new MergeBlockedException(
                    "Branch " + branchName + " blocked: README.md was not updated. " +
                            "Update README.md and push to the branch before retrying merge.");
        }
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
