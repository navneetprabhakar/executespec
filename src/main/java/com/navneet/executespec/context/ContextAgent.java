package com.navneet.executespec.context;

import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@Slf4j
public class ContextAgent {

    private final ContextAgentScanner scanner;
    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;
    private final ContextAgentProperties props;
    private final StateStore stateStore;

    @Value("${agent.workspace}")
    private String workspaceRoot;

    @Value("classpath:prompts/context-agent.txt")
    private Resource promptTemplate;

    public ContextAgent(ContextAgentScanner scanner, LlmRouter llmRouter,
                        ModelResolver modelResolver, ContextAgentProperties props,
                        StateStore stateStore) {
        this.scanner = scanner;
        this.llmRouter = llmRouter;
        this.modelResolver = modelResolver;
        this.props = props;
        this.stateStore = stateStore;
    }

    public void runAsync(PipelineRun run, List<WorkerReport> workerReports,
                         ReviewReportV2 reviewReport) {
        Thread.ofVirtual()
            .name("context-agent-" + run.getRunId())
            .start(() -> run(run, workerReports, reviewReport));
    }

    private void run(PipelineRun run, List<WorkerReport> workerReports,
                     ReviewReportV2 reviewReport) {
        if (!props.isEnabled()) return;
        log.info("Context Agent starting for run {}", run.getRunId());

        try {
            ContextAgentInput input = scanner.scan(run, workerReports, reviewReport);

            if (!hasAnythingChanged(input)) {
                log.info("Context Agent: nothing changed in run {} — skipping", run.getRunId());
                return;
            }

            String updatedDoc = callLlm(input, run);
            if (updatedDoc == null || updatedDoc.isBlank()) {
                log.warn("Context Agent: empty LLM response for run {}", run.getRunId());
                return;
            }

            Path projectPath = Path.of(workspaceRoot);

            writeContextDoc(projectPath, updatedDoc);
            appendDecisionsEntry(projectPath, input, run);
            commitToMain(projectPath, run);

            log.info("Context Agent completed for run {}", run.getRunId());

        } catch (Exception e) {
            log.error("Context Agent failed for run {}: {}", run.getRunId(), e.getMessage(), e);
        }
    }

    private String callLlm(ContextAgentInput input, PipelineRun run) throws IOException {
        String systemPrompt = promptTemplate.getContentAsString(StandardCharsets.UTF_8);
        String userMessage  = buildUserMessage(input);

        ResolvedModel resolved = modelResolver.resolve("ContextAgent", run.getAiProviderEnum());

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userMessage)
        ));

        var response = llmRouter.callWithFallback(
            resolved.providerName(), resolved.getModel(), prompt,
            run.getRunId(), "context-agent",
            LlmRouter.FallbackMode.IMMEDIATE, 0, false);

        // Track tokens for billing accuracy
        try {
            int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
            int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
            stateStore.addTokensToRun(run.getRunId(), inTok, outTok);
            log.debug("ContextAgent tokens: run={} in={} out={}", run.getRunId(), inTok, outTok);
        } catch (Exception e) {
            log.warn("ContextAgent: failed to track tokens for run {}: {}", run.getRunId(), e.getMessage());
        }

        return response.getResult().getOutput().getText();
    }

    private String buildUserMessage(ContextAgentInput input) {
        var sb = new StringBuilder();
        sb.append("## Current CODEBASE_CONTEXT.md\n\n").append(input.currentContextDoc()).append("\n\n");
        sb.append("## Git Changes This Run\n\n").append(input.gitDiffSummary()).append("\n\n");

        if (!input.newPackagesDetected().isEmpty()) {
            sb.append("## New Packages Detected (not in context doc)\n\n");
            input.newPackagesDetected().forEach(p -> sb.append("- ").append(p).append("\n"));
            sb.append("\n");
        }
        if (!input.newFilesDetected().isEmpty()) {
            sb.append("## New Classes Detected (not in context doc)\n\n");
            input.newFilesDetected().forEach(f -> sb.append("- ").append(f).append("\n"));
            sb.append("\n");
        }

        sb.append("## Worker Reports\n\n");
        input.workerReportSummaries().forEach(r -> sb.append("- ").append(r).append("\n"));
        sb.append("\n");

        sb.append("## Reviewer Summary\n\n").append(input.reviewerSummary()).append("\n\n");
        sb.append("## Recent decisions.md (do not duplicate)\n\n").append(input.recentDecisions()).append("\n\n");
        sb.append("Run goal: ").append(input.runGoal()).append("\n");
        return sb.toString();
    }

    private void writeContextDoc(Path projectPath, String updatedDoc) throws IOException {
        Path p = projectPath.resolve(".agent-context/CODEBASE_CONTEXT.md");
        Files.createDirectories(p.getParent());
        Files.writeString(p, updatedDoc, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    private void appendDecisionsEntry(Path projectPath, ContextAgentInput input, PipelineRun run)
        throws IOException {
        Path p = projectPath.resolve(".agent-context/decisions.md");
        Files.createDirectories(p.getParent());

        String ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneOffset.UTC).format(Instant.now());

        var entry = new StringBuilder();
        entry.append("\n## Context Update — ").append(ts)
             .append(" (run: ").append(run.getRunId()).append(")\n\n");
        entry.append("**Goal:** ").append(run.getUserRequest()).append("\n");

        if (!input.newPackagesDetected().isEmpty())
            entry.append("**New packages:** ")
                 .append(String.join(", ", input.newPackagesDetected())).append("\n");
        if (!input.newFilesDetected().isEmpty())
            entry.append("**New classes:** ")
                 .append(String.join(", ", input.newFilesDetected())).append("\n");

        long fileCount = input.gitDiffSummary().lines()
            .filter(l -> l.startsWith("  ")).count();
        entry.append("**Files changed:** ").append(fileCount).append("\n");
        entry.append("*Auto-updated by Context Agent.*\n");

        Files.writeString(p, entry.toString(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
    }

    private void commitToMain(Path projectPath, PipelineRun run) {
        try (var git = org.eclipse.jgit.api.Git.open(projectPath.toFile())) {
            git.add()
               .addFilepattern(".agent-context/CODEBASE_CONTEXT.md")
               .addFilepattern(".agent-context/decisions.md")
               .call();

            if (git.status().call().isClean()) {
                log.info("Context Agent: nothing to commit for run {}", run.getRunId());
                return;
            }

            git.commit()
               .setMessage("[Context Agent] Update CODEBASE_CONTEXT.md after run " + run.getRunId())
               .setAuthor("Context Agent", "context-agent@devpilot.local")
               .call();

        } catch (Exception e) {
            log.error("Context Agent: commit failed for run {}: {}", run.getRunId(), e.getMessage());
        }
    }

    private boolean hasAnythingChanged(ContextAgentInput input) {
        return !input.newPackagesDetected().isEmpty() ||
               !input.newFilesDetected().isEmpty() ||
               !"Git diff unavailable".equals(input.gitDiffSummary()) ||
               !input.workerReportSummaries().isEmpty();
    }
}
