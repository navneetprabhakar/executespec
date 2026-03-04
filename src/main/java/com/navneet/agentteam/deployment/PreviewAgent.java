package com.navneet.agentteam.deployment;

import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.deployment.strategy.DeploymentStrategy;
import com.navneet.agentteam.sse.RunEvent;
import com.navneet.agentteam.sse.SseBroadcaster;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.state.entity.Deployment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic agent that generates Dockerfile, docker-compose.yml, and other
 * deployment configuration for a completed workspace — without using an LLM.
 *
 * <p>This agent is always wired into the pipeline after all worker tasks complete.
 * It handles the mandatory {@code generate_preview} task added to every plan by the
 * planner prompt. Non-fatal: if the workspace does not match any known project type,
 * the task is marked BLOCKED and the run continues to reviewer + merge gate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreviewAgent {

    /** Task ID the planner always assigns to the preview step. */
    public static final String PREVIEW_TASK_ID = "generate_preview";

    private final DockerfileGenerator dockerfileGenerator;
    private final AgentProperties agentProps;
    private final StateStore stateStore;
    private final SseBroadcaster broadcaster;

    /**
     * Runs after all worker tasks complete. Generates deployment files for the
     * workspace and marks the {@code generate_preview} task COMPLETE (or BLOCKED
     * on failure).
     *
     * @param runId    the pipeline run ID
     * @param allTasks all tasks for this run — used to find the preview task entity
     * @return the WorkerReport produced for the preview task
     */
    public WorkerReport run(String runId, List<AgentTask> allTasks) {
        AgentTask previewTask = allTasks.stream()
            .filter(t -> PREVIEW_TASK_ID.equals(t.getTaskId()))
            .findFirst()
            .orElse(null);

        Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath();

        log.info("[PreviewAgent] Generating deployment files for run {} in {}", runId, workspace);
        broadcaster.emit(runId, RunEvent.log(runId, "preview-agent",
            "🔍 Detecting project type and generating deployment files..."));

        // Lock the preview task so it shows as WORKING in the UI
        if (previewTask != null) {
            String branchName = "agent/" + runId.substring(0, 8) + "/" + PREVIEW_TASK_ID;
            stateStore.lockTask(previewTask.getTaskId(), "preview-agent", branchName);
            broadcaster.emit(runId, RunEvent.taskStatus(runId, previewTask.getTaskId(), "WORKING"));
        }

        try {
            if (!Files.isDirectory(workspace)) {
                throw new IOException("Workspace directory not found: " + workspace);
            }

            // Detect project type and generate Dockerfile + .dockerignore (+ nginx.conf for static)
            DeploymentStrategy strategy = dockerfileGenerator.detectAndGenerate(workspace);

            List<String> filesCreated = new ArrayList<>(List.of("Dockerfile", ".dockerignore"));

            // Write docker-compose.yml for easy local testing
            writeDockerCompose(workspace, strategy.type());
            filesCreated.add("docker-compose.yml");

            if (strategy.type() == Deployment.DeployStrategy.STATIC) {
                filesCreated.add("nginx.conf");
                stateStore.markPreviewReady(runId, "");
            }

            String summary = "Generated " + filesCreated.size()
                + " deployment files (" + String.join(", ", filesCreated) + ")."
                + " Project type: " + strategy.type();

            log.info("[PreviewAgent] {} for run {}", summary, runId);
            broadcaster.emit(runId, RunEvent.log(runId, "preview-agent", "✅ " + summary));

            WorkerReport report = WorkerReport.builder()
                .taskId(previewTask != null ? previewTask.getTaskId() : PREVIEW_TASK_ID)
                .status("complete")
                .filesCreated(filesCreated)
                .filesModified(List.of())
                .filesRead(List.of())
                .selfReview(summary)
                .inputTokens(0)
                .outputTokens(0)
                .build();

            if (previewTask != null) {
                stateStore.completeTask(previewTask.getTaskId(), report);
                broadcaster.emit(runId, RunEvent.taskStatus(runId, previewTask.getTaskId(), "COMPLETE"));
            }
            return report;

        } catch (Exception e) {
            String msg = "Preview generation skipped: " + e.getMessage();
            log.warn("[PreviewAgent] {} for run {}", msg, runId);
            broadcaster.emit(runId, RunEvent.log(runId, "preview-agent",
                "⚠️ " + msg + " — the run can still proceed without deployment files."));

            WorkerReport blocked = WorkerReport.blocked(
                previewTask != null ? previewTask.getTaskId() : PREVIEW_TASK_ID,
                msg, 0, 0);

            if (previewTask != null) {
                stateStore.completeTask(previewTask.getTaskId(), blocked);
                broadcaster.emit(runId, RunEvent.taskStatus(runId, previewTask.getTaskId(), "BLOCKED"));
            }
            return blocked;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeDockerCompose(Path workspace, Deployment.DeployStrategy type) throws IOException {
        // Host port 18080 is used instead of 8080 because the executespec service itself
        // runs on port 8080 during development. The container still listens internally on
        // 8080; only the host-side mapping is shifted to avoid the conflict.
        String content = switch (type) {
            case FULLSTACK -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - SPRING_PROFILES_ACTIVE=docker
                    restart: unless-stopped
                """;
            case SPRING_BOOT -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - SPRING_PROFILES_ACTIVE=docker
                    restart: unless-stopped
                """;
            case STATIC -> """
                version: '3.8'
                services:
                  web:
                    build: .
                    ports:
                      - "18080:8080"
                    restart: unless-stopped
                """;
            case PYTHON -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                    restart: unless-stopped
                """;
            case GO -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                    restart: unless-stopped
                """;
            case RUBY -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                      - RAILS_ENV=production
                    restart: unless-stopped
                """;
            case PHP -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                      - APP_ENV=production
                    restart: unless-stopped
                """;
            case GRADLE -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                    restart: unless-stopped
                """;
            case NODE -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                      - NODE_ENV=production
                    restart: unless-stopped
                """;
            case RUST -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                    restart: unless-stopped
                """;
            case DOTNET -> """
                version: '3.8'
                services:
                  app:
                    build: .
                    ports:
                      - "18080:8080"
                    environment:
                      - PORT=8080
                      - ASPNETCORE_URLS=http://+:8080
                    restart: unless-stopped
                """;
        };
        Files.writeString(workspace.resolve("docker-compose.yml"), content);
    }
}
