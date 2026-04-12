package com.navneet.executespec.api.controller;

import com.navneet.executespec.agent.CostEstimator;
import com.navneet.executespec.agent.PlannerAgent;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.api.dto.FeasibilityReport;
import com.navneet.executespec.api.dto.FeasibilityRequest;
import com.navneet.executespec.api.dto.TaskFeasibility;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.ModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/feasibility")
@RequiredArgsConstructor
@Slf4j
public class FeasibilityController {

    private final PlannerAgent plannerAgent;
    private final CostEstimator costEstimator;
    private final AgentProperties props;
    private final ModelRegistry modelRegistry;

    /**
     * Runs the planner in feasibility mode (no workers), estimates cost,
     * and returns a verdict relative to the given budget.
     */
    @PostMapping
    public ResponseEntity<FeasibilityReport> estimate(@RequestBody FeasibilityRequest req) {
        log.info("POST /api/feasibility — goal: {}, budget: ${}", req.goal(), req.budgetUsd());

        String provider = req.provider() != null ? req.provider() : props.getRoles().getWorker();
        String model    = req.model() != null ? req.model() : defaultModel(provider);

        TaskPlan plan = plannerAgent.planWithEstimates(req.goal());

        double totalLow  = 0;
        double totalHigh = 0;
        List<TaskFeasibility> taskEstimates = new ArrayList<>();

        for (TaskDefinition task : plan.getTasks()) {
            double[] range = costEstimator.estimateFromTask(task, provider, model);

            int minIter = task.getEstimatedIterations() != null
                ? task.getEstimatedIterations().min() : 1;
            int maxIter = task.getEstimatedIterations() != null
                ? task.getEstimatedIterations().max() : 3;

            taskEstimates.add(new TaskFeasibility(
                task.getTaskId(), task.getTitle(), task.getComplexity(),
                minIter, maxIter, range[0], range[1]
            ));
            totalLow  += range[0];
            totalHigh += range[1];
        }

        String verdict;
        if (req.budgetUsd() == null) {
            verdict = "ESTIMATED";
        } else if (totalHigh <= req.budgetUsd()) {
            verdict = "FEASIBLE";
        } else if (totalLow <= req.budgetUsd()) {
            verdict = "MARGINAL";
        } else {
            verdict = "INFEASIBLE";
        }

        log.info("Feasibility verdict: {} (${} – ${} vs budget {})",
            verdict, String.format("%.3f", totalLow),
            String.format("%.3f", totalHigh),
            req.budgetUsd() != null ? "$" + req.budgetUsd() : "N/A");

        return ResponseEntity.ok(new FeasibilityReport(
            verdict, req.budgetUsd(), totalLow, totalHigh, taskEstimates
        ));
    }

    private String defaultModel(String provider) {
        return modelRegistry.workhouseModelFor(provider);
    }
}
