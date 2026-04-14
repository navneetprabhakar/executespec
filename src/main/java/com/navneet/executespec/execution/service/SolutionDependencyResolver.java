package com.navneet.executespec.execution.service;

import com.navneet.executespec.execution.entity.ExecutionSolution;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Pure-logic dependency utilities for execution solutions. No DB / no LLM.
 */
@Service
public class SolutionDependencyResolver {

    /**
     * Topological order grouped by layer. Each layer contains solutions whose
     * dependencies all sit in earlier layers. Solutions in the same layer may
     * still conflict on files — {@link FileConflictDetector} filters that.
     *
     * Throws {@link IllegalStateException} if a cycle is detected.
     */
    public List<List<ExecutionSolution>> resolveExecutionOrder(List<ExecutionSolution> solutions) {
        if (solutions == null || solutions.isEmpty()) return List.of();

        Map<String, ExecutionSolution> byId = new HashMap<>();
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (ExecutionSolution s : solutions) {
            byId.put(s.getId(), s);
            Set<String> upstream = new HashSet<>();
            if (s.getDependencies() != null) {
                for (ExecutionSolution d : s.getDependencies()) upstream.add(d.getId());
            }
            deps.put(s.getId(), upstream);
            dependents.putIfAbsent(s.getId(), new HashSet<>());
            for (String up : upstream) {
                dependents.computeIfAbsent(up, k -> new HashSet<>()).add(s.getId());
            }
        }

        List<List<ExecutionSolution>> layers = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        while (placed.size() < solutions.size()) {
            List<ExecutionSolution> layer = new ArrayList<>();
            for (ExecutionSolution s : solutions) {
                if (placed.contains(s.getId())) continue;
                if (placed.containsAll(deps.get(s.getId()))) layer.add(s);
            }
            if (layer.isEmpty()) {
                throw new IllegalStateException(
                        "Cyclic solution dependencies detected; cannot schedule execution");
            }
            layer.sort(Comparator.comparingInt(ExecutionSolution::getSequenceNumber));
            layers.add(layer);
            for (ExecutionSolution s : layer) placed.add(s.getId());
        }
        return layers;
    }

    /**
     * All solutions transitively blocked by a failed solution (direct + indirect dependents).
     */
    public Set<ExecutionSolution> getBlockedDownstream(
            ExecutionSolution failed, List<ExecutionSolution> all) {
        Map<String, Set<String>> dependents = buildDependentsMap(all);
        Map<String, ExecutionSolution> byId = new HashMap<>();
        for (ExecutionSolution s : all) byId.put(s.getId(), s);

        Set<ExecutionSolution> blocked = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(dependents.getOrDefault(failed.getId(), Set.of()));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            ExecutionSolution s = byId.get(id);
            if (s == null || !blocked.add(s)) continue;
            queue.addAll(dependents.getOrDefault(id, Set.of()));
        }
        return blocked;
    }

    private Map<String, Set<String>> buildDependentsMap(List<ExecutionSolution> all) {
        Map<String, Set<String>> dependents = new HashMap<>();
        for (ExecutionSolution s : all) dependents.put(s.getId(), new HashSet<>());
        for (ExecutionSolution s : all) {
            if (s.getDependencies() == null) continue;
            for (ExecutionSolution d : s.getDependencies()) {
                dependents.computeIfAbsent(d.getId(), k -> new HashSet<>()).add(s.getId());
            }
        }
        return dependents;
    }
}
