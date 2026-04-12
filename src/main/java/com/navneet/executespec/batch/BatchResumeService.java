package com.navneet.executespec.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Processes batch results and resumes the pipeline.
 * <p>
 * Writes batch LLM outputs (file contents) into the workspace,
 * marks tasks as COMPLETE/FAILED, then hands off to PipelineHarness
 * for compile → merge → review → report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Deprecated(since = "v2")
public class BatchResumeService {

    private final StateStore stateStore;
    private final SseBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspaceInitService workspaceInitService;
    private final ModelResolver modelResolver;

    /**
     * Process batch results and write files into the workspace.
     * Called by {@link BatchPollService} when a batch completes.
     */
    private static final List<String> TERMINAL_STATUSES = List.of("CANCELLED", "FAILED", "COMPLETE", "PAUSED");

    @Deprecated(since = "v2")
    public void resumeFromBatch(PipelineRun run, BatchResults results) {
        String runId = run.getRunId();

        // C1 fix: guard against cancel-vs-complete race — if the run was cancelled
        // between poll completion and resume invocation, do not overwrite terminal status
        PipelineRun freshRun = stateStore.findRun(runId).orElse(null);
        if (freshRun == null) {
            log.warn("[Run {}] Run not found during batch resume — aborting", runId);
            return;
        }
        if (TERMINAL_STATUSES.contains(freshRun.getStatus())) {
            log.info("[Run {}] Run already in terminal state {} — skipping batch resume",
                    runId, freshRun.getStatus());
            return;
        }

        log.info("[Run {}] Resuming from batch: {} results", runId, results.results().size());

        stateStore.updateStatus(runId, "WORKING");
        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, "harness", "Processing batch results..."));

        // Map customId → batch result
        Map<String, BatchResult> resultMap = results.results().stream()
                .collect(Collectors.toMap(BatchResult::customId, r -> r, (a, b) -> a));

        // Get all tasks for this run
        List<AgentTask> tasks = stateStore.getAllTasks(runId);
        String workspacePath = workspaceInitService.runWorkspacePath(runId).toString();

        // Collect parent task IDs that have subtasks — they won't have batch results
        Set<String> decomposedParentIds = tasks.stream()
                .filter(t -> t.getParentTaskId() != null)
                .map(AgentTask::getParentTaskId)
                .collect(Collectors.toSet());

        // Resolve batch model/provider for task attribution
        String batchModel = null;
        String batchProviderName = null;
        try {
            String provStr = run.getBatchProvider();
            if (provStr != null) {
                LlmProvider batchProvider = LlmProvider.valueOf(provStr);
                ResolvedModel resolved = modelResolver.resolve("WorkerAgent", batchProvider);
                batchModel = resolved.getModel();
                batchProviderName = batchProvider.name().toLowerCase();
            }
        } catch (Exception e) {
            log.debug("[Run {}] Could not resolve batch model: {}", runId, e.getMessage());
        }

        int completed = 0;
        int failed = 0;

        for (AgentTask task : tasks) {
            // Skip parent tasks that were decomposed — their subtasks have the batch results
            if (decomposedParentIds.contains(task.getTaskId())) {
                log.debug("[Run {}] Skipping decomposed parent task {} — subtasks will be aggregated later",
                        runId, task.getTaskId());
                continue;
            }

            String customId = "task-" + task.getTaskId();
            BatchResult result = resultMap.get(customId);

            if (result == null) {
                log.warn("[Run {}] No batch result for task {}", runId, task.getTaskId());
                stateStore.markTaskFailed(task.getTaskId(), "No batch result received");
                broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "FAILED"));
                failed++;
                continue;
            }

            if (result.content() == null || result.content().isBlank()) {
                stateStore.markTaskFailed(task.getTaskId(), "Empty batch response");
                broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "FAILED"));
                failed++;
                continue;
            }

            try {
                // Parse the structured JSON response from the batch LLM
                int filesWritten = writeBatchOutput(workspacePath, result.content(), task.getTaskId(), runId);

                // Update token counts
                if (result.inputTokens() > 0 || result.outputTokens() > 0) {
                    stateStore.updateTaskTokens(task.getTaskId(), result.inputTokens(), result.outputTokens());
                    stateStore.addTokensToRun(runId, result.inputTokens(), result.outputTokens());
                }

                if (filesWritten == 0) {
                    // LLM responded but not in any parseable format — mark failed for retry
                    stateStore.markTaskFailed(task.getTaskId(), "Batch response could not be parsed into files");
                    broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "FAILED"));
                    failed++;
                    continue;
                }

                stateStore.markTaskComplete(task.getTaskId());
                // Set batch model/provider for token usage attribution
                if (batchModel != null || batchProviderName != null) {
                    stateStore.setTaskModelProvider(task.getTaskId(), batchModel, batchProviderName);
                }
                broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "COMPLETE"));
                broadcaster.emit(runId, RunEvent.log(runId, task.getTaskId(),
                        "Batch result processed — " + filesWritten + " file(s) written"));
                completed++;

            } catch (Exception e) {
                log.error("[Run {}] Failed to process batch result for task {}: {}",
                        runId, task.getTaskId(), e.getMessage());
                stateStore.markTaskFailed(task.getTaskId(), "Batch result processing error: " + e.getMessage());
                broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "FAILED"));
                failed++;
            }
        }

        log.info("[Run {}] Batch results processed: {} completed, {} failed out of {}",
                runId, completed, failed, tasks.size());

        if (completed == 0) {
            stateStore.markFailed(runId, "All batch tasks failed");
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            return;
        }

        // Aggregate subtask results back into parent tasks
        Set<String> aggregatedParents = new HashSet<>();
        for (AgentTask task : tasks) {
            if (task.getParentTaskId() != null && aggregatedParents.add(task.getParentTaskId())) {
                try {
                    stateStore.completeParentFromSubtasks(task.getParentTaskId());
                    log.info("[Run {}] Aggregated subtasks for parent task {}", runId, task.getParentTaskId());
                } catch (Exception e) {
                    log.warn("[Run {}] Failed to aggregate subtasks for parent {}: {}",
                            runId, task.getParentTaskId(), e.getMessage());
                }
            }
        }

        // Hand off to pipeline harness for compile → merge → review → report
        // This is published as an event so PipelineHarness can pick it up asynchronously
        eventPublisher.publishEvent(new BatchCompleteEvent(runId));
    }

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
    private static final Pattern CODE_FENCE_JSON = Pattern.compile(
            "```(?:json)?\\s*\\n(\\{.*?\\})\\s*\\n```", Pattern.DOTALL);

    /**
     * Parse the batch LLM output and write files to the workspace.
     * Tries multiple parsing strategies in order:
     * 1. Direct JSON with files_written array
     * 2. JSON extracted from markdown code fences
     * 3. XML tool_call extraction (writeFile calls)
     * 4. Raw output fallback
     */
    @Deprecated(since = "v2")
    private int writeBatchOutput(String workspacePath, String content, String taskId, String runId)
            throws IOException {

        // Strategy 1: Direct JSON parse
        int count = tryParseStructuredJson(workspacePath, content, taskId, runId);
        if (count > 0) return count;

        // Strategy 2: Extract JSON from markdown code fences (```json ... ```)
        Matcher fenceMatcher = CODE_FENCE_JSON.matcher(content);
        if (fenceMatcher.find()) {
            count = tryParseStructuredJson(workspacePath, fenceMatcher.group(1), taskId, runId);
            if (count > 0) {
                log.debug("[Run {}] Task {}: parsed JSON from markdown code fence", runId, taskId);
                return count;
            }
        }

        // Strategy 3: Find the first { ... } JSON block in the response
        int jsonStart = content.indexOf('{');
        int jsonEnd = content.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String extracted = content.substring(jsonStart, jsonEnd + 1);
            count = tryParseStructuredJson(workspacePath, extracted, taskId, runId);
            if (count > 0) {
                log.debug("[Run {}] Task {}: parsed JSON from extracted block", runId, taskId);
                return count;
            }
        }

        // Strategy 4: Extract writeFile calls from <tool_call> XML (worker-style output)
        count = tryParseToolCalls(workspacePath, content, taskId, runId);
        if (count > 0) return count;

        // Strategy 5: Raw output fallback
        log.warn("[Run {}] Task {}: batch response not in any expected format — storing as raw output ({} chars)",
                runId, taskId, content.length());
        Path outputFile = Path.of(workspacePath, ".batch-output", taskId + ".txt");
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, content);
        return 0;
    }

    @Deprecated(since = "v2")
    private int tryParseStructuredJson(String workspacePath, String json, String taskId, String runId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode filesArray = root.path("files_written");
            if (filesArray.isArray() && !filesArray.isEmpty()) {
                int count = 0;
                for (JsonNode fileEntry : filesArray) {
                    String path = fileEntry.path("path").asText(null);
                    String fileContent = fileEntry.path("content").asText(null);
                    if (path != null && fileContent != null) {
                        writeFile(workspacePath, path, fileContent);
                        count++;
                    }
                }
                if (count > 0) {
                    log.debug("[Run {}] Task {}: wrote {} files from structured JSON", runId, taskId, count);
                }
                return count;
            }
        } catch (Exception ignored) {
            // Not valid JSON or missing files_written
        }
        return 0;
    }

    @Deprecated(since = "v2")
    private int tryParseToolCalls(String workspacePath, String content, String taskId, String runId) {
        Matcher m = TOOL_CALL_PATTERN.matcher(content);
        int count = 0;
        while (m.find()) {
            try {
                String toolJson = m.group(1).trim();
                JsonNode node = objectMapper.readTree(toolJson);
                String toolName = node.path("name").asText("");
                if ("writeFile".equals(toolName) || "write_file".equals(toolName)) {
                    JsonNode args = node.path("args");
                    String path = args.path("path").asText(null);
                    String fileContent = args.path("content").asText(null);
                    if (path != null && fileContent != null) {
                        writeFile(workspacePath, path, fileContent);
                        count++;
                    }
                }
            } catch (Exception e) {
                log.debug("[Run {}] Task {}: failed to parse tool_call: {}", runId, taskId, e.getMessage());
            }
        }
        if (count > 0) {
            log.debug("[Run {}] Task {}: wrote {} files from tool_call XML", runId, taskId, count);
        }
        return count;
    }

    @Deprecated(since = "v2")
    private void writeFile(String workspacePath, String relativePath, String content) throws IOException {
        Path target = Path.of(workspacePath, relativePath).normalize();
        // Security: ensure path stays within workspace
        if (!target.startsWith(Path.of(workspacePath))) {
            throw new IOException("Path traversal detected: " + relativePath);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /**
     * Event published when batch results are fully processed and pipeline should resume.
     */
    public record BatchCompleteEvent(String runId) {}
}
