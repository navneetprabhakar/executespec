package com.navneet.agentteam.optimiser;

import com.navneet.agentteam.recovery.LlmCallGuard;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.PipelineRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PromptOptimiserAgent {

    private static final String SYSTEM_PROMPT = """
        You are a prompt optimiser for a multi-agent coding system.

        You will be given:
        - A prompt file's current content
        - One or more failure patterns observed across recent runs that this prompt
          is responsible for

        Your job is to make the MINIMUM edit to the prompt that would address the
        identified patterns. Rules:

        1. Return ONLY the complete updated prompt text — no preamble, no explanation,
           no markdown fences
        2. Change as little as possible — preserve all existing instructions verbatim
           unless they directly relate to a pattern
        3. Do not add new sections or restructure the prompt
        4. Do not remove existing rules unless they are clearly causing the pattern
        5. Additions should be concise — one or two sentences maximum per pattern
        6. If the pattern is already addressed in the prompt, return the prompt unchanged
        7. The edit must be surgical — future agents will run on this prompt immediately
        """;

    private final PromptPatternAnalyser analyser;
    private final PromptAuditService auditService;
    private final LlmCallGuard llmCallGuard;
    private final PromptOptimiserProperties props;
    private final StateStore stateStore;

    public PromptOptimiserAgent(PromptPatternAnalyser analyser, PromptAuditService auditService,
                                LlmCallGuard llmCallGuard, PromptOptimiserProperties props,
                                StateStore stateStore) {
        this.analyser = analyser;
        this.auditService = auditService;
        this.llmCallGuard = llmCallGuard;
        this.props = props;
        this.stateStore = stateStore;
    }

    public void runIfDue(PipelineRun completedRun) {
        if (!props.isEnabled()) return;

        Thread.ofVirtual()
            .name("prompt-optimiser-" + completedRun.getRunId())
            .start(() -> runIfDueInternal(completedRun));
    }

    private void runIfDueInternal(PipelineRun completedRun) {
        try {
            if (!shouldRun()) return;

            log.info("Prompt Optimiser running after run {}", completedRun.getRunId());

            PromptPatterns patterns = analyser.analyse();
            if (!patterns.hasPatterns()) {
                log.info("Prompt Optimiser: no patterns found — skipping");
                return;
            }

            log.info("Prompt Optimiser: found patterns in — {}",
                String.join(", ", patterns.patternsByPrompt().keySet()));

            for (Map.Entry<String, List<String>> entry :
                 patterns.patternsByPrompt().entrySet()) {
                optimisePrompt(entry.getKey(), entry.getValue(), patterns, completedRun);
            }

            commitToMain(completedRun);

        } catch (Exception e) {
            log.error("Prompt Optimiser failed after run {}: {}",
                completedRun.getRunId(), e.getMessage(), e);
        }
    }

    private void optimisePrompt(String promptFile, List<String> patterns,
                                PromptPatterns allPatterns, PipelineRun triggeringRun)
        throws IOException {
        Path promptPath = resolvePromptPath(promptFile);
        if (!Files.exists(promptPath)) {
            log.warn("Prompt Optimiser: prompt file not found — {}", promptFile);
            return;
        }

        String currentContent = Files.readString(promptPath, StandardCharsets.UTF_8);
        String updatedContent = callLlm(currentContent, patterns, triggeringRun);

        if (updatedContent == null || updatedContent.isBlank()) {
            log.warn("Prompt Optimiser: empty LLM response for {}", promptFile);
            return;
        }

        if (currentContent.strip().equals(updatedContent.strip())) {
            log.info("Prompt Optimiser: no change needed for {} — pattern already addressed",
                promptFile);
            return;
        }

        Files.writeString(promptPath, updatedContent,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);

        auditService.record(
            promptFile,
            currentContent,
            updatedContent,
            patterns,
            allPatterns.sourceRunIds(),
            determineTriggerType()
        );

        log.info("Prompt Optimiser: updated {} — patterns: {}", promptFile, patterns);
    }

    private String callLlm(String currentContent, List<String> patterns, PipelineRun run) {
        String userMessage = "## Current prompt content\n\n" + currentContent +
            "\n\n## Failure patterns to address\n\n" +
            patterns.stream()
                .map(p -> "- " + p)
                .collect(Collectors.joining("\n"));

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(SYSTEM_PROMPT),
            new UserMessage(userMessage)
        ));

        return llmCallGuard
            .call(prompt, props.getModel(), "prompt-optimiser-" + run.getRunId(), run.getRunId())
            .getResult().getOutput().getText();
    }

    private boolean shouldRun() {
        long totalCompleted = stateStore.countCompletedRuns();

        if (props.getRunInterval() > 0 && totalCompleted % props.getRunInterval() == 0) return true;

        List<PipelineRun> recent = stateStore.getRecentCompletedRuns(
            props.getAnalysisWindowRuns()
        );
        if (recent.size() < props.getAnalysisWindowRuns()) return false;

        long failedRuns = recent.stream()
            .filter(r -> stateStore.getTasksForRun(r.getRunId()).stream()
                .anyMatch(t -> "FAILED".equals(t.getStatus()) ||
                               "BLOCKED".equals(t.getStatus())))
            .count();

        double failureRate = (double) failedRuns / recent.size();
        return failureRate >= props.getFailureRateThreshold();
    }

    private String determineTriggerType() {
        long totalCompleted = stateStore.countCompletedRuns();
        return props.getRunInterval() > 0 && totalCompleted % props.getRunInterval() == 0
            ? "SCHEDULED"
            : "FAILURE_RATE";
    }

    private Path resolvePromptPath(String promptFile) {
        return Path.of("src/main/resources/prompts", promptFile);
    }

    private void commitToMain(PipelineRun run) {
        try (var git = org.eclipse.jgit.api.Git.open(new File("."))) {
            git.add().addFilepattern("src/main/resources/prompts/").call();

            if (git.status().call().isClean()) return;

            git.commit()
               .setMessage("[Prompt Optimiser] Update prompts after run " + run.getRunId())
               .setAuthor("Prompt Optimiser", "prompt-optimiser@devpilot.local")
               .call();

        } catch (Exception e) {
            log.error("Prompt Optimiser: commit failed for run {}: {}",
                run.getRunId(), e.getMessage());
        }
    }
}
