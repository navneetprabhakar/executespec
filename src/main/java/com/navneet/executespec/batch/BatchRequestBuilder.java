package com.navneet.executespec.batch;

import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds provider-agnostic {@link BatchRequest}s from a {@link TaskPlan}.
 * <p>
 * Batch mode is a single-shot execution — the LLM produces all file contents
 * in one response without interactive tool calls. The prompt instructs the model
 * to output structured JSON with file paths and contents for each task.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchRequestBuilder {

    private final ModelResolver modelResolver;
    private final AgentProperties agentProperties;
    private final ModelMatrixProperties modelMatrixProperties;

    private static final String BATCH_SYSTEM_PROMPT = """
            You are a software engineering agent executing a single task from a multi-task plan.
            You must produce all required code in this single response — there is no interactive mode.

            OUTPUT FORMAT: Respond with a JSON object containing:
            {
              "status": "COMPLETE" | "PARTIAL" | "BLOCKED",
              "files_written": [
                {
                  "path": "relative/path/to/file.ext",
                  "content": "full file content here"
                }
              ],
              "completion_notes": "Brief summary of what was implemented",
              "blockers": "Only if status is BLOCKED — explain what's missing"
            }

            RULES:
            - Write COMPLETE file contents, not diffs or patches
            - Include ALL imports, headers, and boilerplate
            - Follow the project conventions described in the task
            - If a task depends on other tasks, assume their files exist as specified in the plan
            - Do NOT fabricate test data or placeholder content
            - Produce production-quality code
            """;

    /**
     * Build batch requests for all tasks in the plan.
     *
     * @param plan     The execution plan from the planner
     * @param provider User-selected LLM provider
     * @param planJson Full plan JSON for context
     * @return List of batch requests, one per task
     */
    public List<BatchRequest> buildRequests(TaskPlan plan, LlmProvider provider, String planJson) {
        double tokenMultiplier = agentProperties.getCost().getBatch().getMaxOutputTokenMultiplier();
        List<BatchRequest> requests = new ArrayList<>();

        for (TaskDefinition task : plan.getTasks()) {
            ResolvedModel resolved = modelResolver.resolve("WorkerAgent", provider);
            String model = resolved.getModel();

            // Estimate max output tokens: base on complexity, multiplied for batch generosity
            int baseTokens = estimateOutputTokens(task);
            int maxTokens = (int) (baseTokens * tokenMultiplier);

            // Cap at model's max output
            int modelMax = getModelMaxOutput(model);
            maxTokens = Math.min(maxTokens, modelMax);

            String userMessage = buildTaskMessage(task, plan, planJson);

            requests.add(new BatchRequest(
                    "task-" + task.getTaskId(),
                    model,
                    maxTokens,
                    BATCH_SYSTEM_PROMPT,
                    List.of(Map.of("role", "user", "content", userMessage))
            ));
        }

        log.info("Built {} batch requests for provider {}", requests.size(), provider);
        return requests;
    }

    private String buildTaskMessage(TaskDefinition task, TaskPlan plan, String planJson) {
        String deps = (task.getDependsOn() == null || task.getDependsOn().isEmpty())
                ? "none"
                : String.join(", ", task.getDependsOn());

        String ownedFiles = (task.getOwnedFiles() == null || task.getOwnedFiles().isEmpty())
                ? "not specified"
                : String.join(", ", task.getOwnedFiles());

        return """
                PROJECT TYPE: %s

                FULL PLAN CONTEXT:
                %s

                YOUR TASK ASSIGNMENT:

                TASK ID: %s
                TITLE: %s
                OBJECTIVE: %s
                BOUNDARIES: %s
                OUTPUT FORMAT: %s
                ACCEPTANCE CRITERIA: %s
                DEPENDS ON: %s
                COMPLEXITY: %s
                OWNED FILES: %s

                Produce the complete file contents for all files this task needs to create or modify.
                Respond with the JSON format described in the system prompt.
                """.formatted(
                plan.getProjectType() != null ? plan.getProjectType() : "unknown",
                planJson != null ? truncatePlanContext(planJson) : "not available",
                task.getTaskId(),
                task.getTitle(),
                task.getObjective(),
                task.getBoundaries() != null ? task.getBoundaries() : "none specified",
                task.getOutputFormat() != null ? task.getOutputFormat() : "standard code files",
                task.getAcceptanceCriteria() != null ? task.getAcceptanceCriteria() : "code compiles and passes review",
                deps,
                task.getComplexity() != null ? task.getComplexity() : "MEDIUM",
                ownedFiles
        );
    }

    private int estimateOutputTokens(TaskDefinition task) {
        String complexity = task.getComplexity() != null ? task.getComplexity() : "MEDIUM";
        int fileCount = task.getOwnedFiles() != null ? task.getOwnedFiles().size() : 1;

        int basePerFile = switch (complexity) {
            case "LOW"    -> 2000;
            case "MEDIUM" -> 4000;
            case "HIGH"   -> 8000;
            default       -> 4000;
        };

        return basePerFile * Math.max(1, fileCount);
    }

    private int getModelMaxOutput(String model) {
        var modelConfig = modelMatrixProperties.getModels().get(model);
        if (modelConfig != null && modelConfig.getMaxOutputTokens() > 0) {
            return modelConfig.getMaxOutputTokens();
        }
        return 16384; // safe default
    }

    private static String truncatePlanContext(String planJson) {
        // Limit plan context to ~8000 chars to stay within token budget
        if (planJson.length() > 8000) {
            return planJson.substring(0, 8000) + "\n... [truncated]";
        }
        return planJson;
    }
}
