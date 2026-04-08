package com.navneet.executespec.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
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
public class BatchResumeService {

    private final StateStore stateStore;
    private final SseBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Process batch results and write files into the workspace.
     * Called by {@link BatchPollService} when a batch completes.
     */
    public void resumeFromBatch(PipelineRun run, BatchResults results) {
        String runId = run.getRunId();
        log.info("[Run {}] Resuming from batch: {} results", runId, results.results().size());

        stateStore.updateStatus(runId, "WORKING");
        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, "harness", "Processing batch results..."));

        // Map customId → batch result
        Map<String, BatchResult> resultMap = results.results().stream()
                .collect(Collectors.toMap(BatchResult::customId, r -> r, (a, b) -> a));

        // Get all tasks for this run
        List<AgentTask> tasks = stateStore.getAllTasks(runId);
        String workspacePath = run.getWorkspacePath();

        int completed = 0;
        int failed = 0;

        for (AgentTask task : tasks) {
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

                stateStore.markTaskComplete(task.getTaskId());
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

        // Hand off to pipeline harness for compile → merge → review → report
        // This is published as an event so PipelineHarness can pick it up asynchronously
        eventPublisher.publishEvent(new BatchCompleteEvent(runId));
    }

    /**
     * Parse the batch LLM output (structured JSON with file paths and contents)
     * and write files to the workspace.
     */
    private int writeBatchOutput(String workspacePath, String content, String taskId, String runId)
            throws IOException {
        // Try to parse as JSON first (structured output)
        try {
            JsonNode root = objectMapper.readTree(content);
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
                log.debug("[Run {}] Task {}: wrote {} files from structured JSON", runId, taskId, count);
                return count;
            }
        } catch (Exception ignored) {
            // Not valid JSON — fall through to raw content handling
        }

        // Fallback: treat the entire response as a single file
        // (this handles cases where the LLM didn't follow the JSON format)
        log.warn("[Run {}] Task {}: batch response not in expected JSON format — storing as raw output",
                runId, taskId);
        Path outputFile = Path.of(workspacePath, ".batch-output", taskId + ".txt");
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, content);
        return 0;
    }

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
