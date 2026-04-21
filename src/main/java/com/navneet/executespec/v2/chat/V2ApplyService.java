package com.navneet.executespec.v2.chat;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.v2.agent.WorkerAgentV2;
import com.navneet.executespec.v2.agent.WorkerResult;
import com.navneet.executespec.v2.agent.WorkerTaskRequest;
import com.navneet.executespec.v2.tool.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * V2 apply-suggestion path. Runs a single-task {@link WorkerAgentV2} session
 * against the existing workspace of a TERMINAL run, using the user's
 * suggestion as the task description. Worker has full tool access (readFile,
 * writeFile, editFile, shell, git*, buildProject, completeTask) so it can
 * actually patch code — unlike {@link V2ChatService} which is read-only Q&A.
 *
 * <p>Differences from a fresh run:
 * <ul>
 *   <li>No planner — the user-provided suggestion IS the plan</li>
 *   <li>No per-task retry / reviewer / validation — single invocation</li>
 *   <li>Appends a git commit with a {@code copilot:} / {@code review:} prefix
 *       so the provenance is obvious in the branch history</li>
 *   <li>Mutates the existing {@code pipeline_run.workspace_path} — no new run id</li>
 * </ul>
 *
 * <p>Limits: 25 iterations hard cap (same as MEDIUM task), WORKHORSE tier model.
 * Workspace-level concurrency is the caller's responsibility — if two Applies
 * fire on the same run, git may throw dirty-index errors; that's acceptable
 * for now since the UI only surfaces one button at a time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class V2ApplyService {

    private static final int APPLY_ITER_CAP = 25;

    private final PipelineRunRepository runRepo;
    private final WorkerAgentV2 worker;
    private final ModelResolver modelResolver;
    private final AgentProperties agentProps;

    public record ApplyResult(int taskCount, List<TaskSummary> tasks,
                              String workerOutcome, String summary) {}
    public record TaskSummary(String taskId, String title) {}

    /**
     * @param runId      completed run to patch in-place
     * @param suggestion free-form instruction from the user (from chat copilot,
     *                   review panel, or manual input)
     * @param source     "COPILOT" or "REVIEW" — used in the commit message
     *                   prefix and in logs
     */
    public ApplyResult apply(String runId, String suggestion, String source) {
        if (suggestion == null || suggestion.isBlank()) {
            throw new IllegalArgumentException("suggestion is required");
        }
        PipelineRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!isTerminal(run.getStatus())) {
            throw new IllegalStateException(
                    "Apply only available for terminal runs (current: " + run.getStatus() + ")");
        }

        Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
        if (!workspace.toFile().isDirectory()) {
            throw new IllegalStateException("Workspace missing on disk at " + workspace);
        }

        LlmProvider provider;
        try {
            provider = run.getAiProvider() == null ? LlmProvider.ANTHROPIC
                    : LlmProvider.valueOf(run.getAiProvider().toUpperCase());
        } catch (IllegalArgumentException ex) {
            provider = LlmProvider.ANTHROPIC;
        }
        ResolvedModel model;
        try {
            model = modelResolver.resolve("V2ApplyService", provider, AgentTier.WORKHORSE);
        } catch (Exception e) {
            log.warn("V2ApplyService model resolve failed for provider={}: {} — falling back to ANTHROPIC", provider, e.getMessage());
            provider = LlmProvider.ANTHROPIC;
            model = modelResolver.resolve("V2ApplyService", provider, AgentTier.WORKHORSE);
        }

        String taskId = "APPLY-" + System.currentTimeMillis();
        String commitPrefix = "REVIEW".equalsIgnoreCase(source) ? "review" : "copilot";
        String title = firstLine(suggestion, 60);

        ExecutionContext ctx = new ExecutionContext(runId, taskId, workspace);

        String systemPrompt = """
                You are applying a code change to an already-generated project. The workspace
                is at /app (your tools see it as workspace root). Every source file that the
                previous run produced is already on disk and committed. Your job:

                1. Read only what you must to understand where the change belongs.
                2. Make the MINIMAL set of edits that satisfy the suggestion.
                3. Do NOT restructure code, do NOT rename existing files, do NOT introduce
                   new dependencies unless the suggestion explicitly calls for them.
                4. When done, call completeTask with a one-paragraph summary of what you
                   changed and which files you touched. Do not run the build yourself — the
                   caller handles that.

                Safety:
                - Never delete files outside what the suggestion calls for.
                - If the suggestion is ambiguous, make the narrowest reasonable interpretation
                  and note it in your completion summary.
                """;

        String userPrompt = "Suggestion source: " + commitPrefix + "\n\n" + suggestion;

        WorkerTaskRequest req = WorkerTaskRequest.builder()
                .provider(provider.name().toLowerCase())
                .model(model.getModel())
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .context(ctx)
                .maxIterations(APPLY_ITER_CAP)
                .temperature(0.0)
                .build();

        log.info("V2ApplyService run={} source={} model={} iterCap={} suggestionPreview={}",
                runId, commitPrefix, model.getModel(), APPLY_ITER_CAP, preview(suggestion, 100));

        WorkerResult result;
        try {
            result = worker.run(req);
        } catch (Exception e) {
            log.error("V2ApplyService run={} worker failed: {}", runId, e.getMessage(), e);
            throw new RuntimeException("Apply worker failed: " + e.getMessage(), e);
        }

        // Worker's file edits are direct writes — the commit isn't automatic.
        // Git-add + commit in the workspace so the apply is captured in history.
        String gitOut = tryGitCommit(workspace, commitPrefix, title);

        String summary = "Worker outcome: " + result.outcome()
                + " · iterations: " + result.iterations()
                + " · tool calls: " + result.toolCallsDispatched()
                + (gitOut == null ? "" : " · git: " + gitOut);

        if (result.outcome() != WorkerResult.Outcome.COMPLETED) {
            log.warn("V2ApplyService run={} non-clean outcome: {} ({} iterations, {} tool calls)",
                    runId, result.outcome(), result.iterations(), result.toolCallsDispatched());
        }

        return new ApplyResult(1,
                List.of(new TaskSummary(taskId, title)),
                result.outcome().name(),
                summary);
    }

    // ──────────────────────────────────────────────────────────────────────

    private static boolean isTerminal(String s) {
        return "COMPLETE".equals(s) || "FAILED".equals(s)
                || "CANCELLED".equals(s) || "PARTIAL".equals(s);
    }

    private static String firstLine(String s, int cap) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String first = (nl < 0) ? s : s.substring(0, nl);
        first = first.trim();
        if (first.length() > cap) first = first.substring(0, cap - 1) + "…";
        return first;
    }

    private static String preview(String s, int cap) {
        if (s == null) return "";
        String cleaned = s.replace('\n', ' ');
        if (cleaned.length() > cap) cleaned = cleaned.substring(0, cap - 1) + "…";
        return cleaned;
    }

    /** Shell out to git so the apply lands as a proper commit on the existing
     *  branch. Non-fatal — if git fails the worker's edits stay on disk. */
    private static String tryGitCommit(Path workspace, String prefix, String title) {
        try {
            runCmd(workspace, "git", "add", "-A");
            String status = runCmd(workspace, "git", "status", "--porcelain");
            if (status == null || status.isBlank()) {
                return "no-op (nothing to commit)";
            }
            String msg = prefix + ": " + (title.isBlank() ? "apply user suggestion" : title);
            runCmd(workspace, "git", "commit", "-m", msg,
                    "--author", "Executespec Copilot <copilot@executespec.local>");
            return "committed";
        } catch (Exception e) {
            log.warn("V2ApplyService git commit failed at {}: {}", workspace, e.getMessage());
            return "commit-failed: " + e.getMessage();
        }
    }

    private static String runCmd(Path cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (var br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git command timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IOException("exit=" + p.exitValue() + " cmd=" + String.join(" ", cmd)
                    + " output=" + sb.toString().trim());
        }
        return sb.toString().trim();
    }
}
