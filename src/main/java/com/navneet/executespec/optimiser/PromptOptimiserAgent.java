package com.navneet.executespec.optimiser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.config.PromptVersioningService;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class PromptOptimiserAgent {

  private static final String SYSTEM_PROMPT =
      """
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
  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;
  private final PromptOptimiserProperties props;
  private final StateStore stateStore;
  private final PromptVersioningService versioningService;

  public PromptOptimiserAgent(
      PromptPatternAnalyser analyser,
      PromptAuditService auditService,
      FallbackLlmRouter fallbackLlmRouter,
      ModelResolver modelResolver,
      PromptOptimiserProperties props,
      StateStore stateStore,
      PromptVersioningService versioningService) {
    this.analyser = analyser;
    this.auditService = auditService;
    this.fallbackLlmRouter = fallbackLlmRouter;
    this.modelResolver = modelResolver;
    this.props = props;
    this.stateStore = stateStore;
    this.versioningService = versioningService;
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

      log.info(
          "Prompt Optimiser: found patterns in — {}",
          String.join(", ", patterns.patternsByPrompt().keySet()));

      for (Map.Entry<String, List<String>> entry : patterns.patternsByPrompt().entrySet()) {
        optimisePrompt(entry.getKey(), entry.getValue(), patterns, completedRun);
      }

      commitToMain(completedRun);

    } catch (Exception e) {
      log.error(
          "Prompt Optimiser failed after run {}: {}", completedRun.getRunId(), e.getMessage(), e);
    }
  }

  private void optimisePrompt(
      String promptFile,
      List<String> patterns,
      PromptPatterns allPatterns,
      PipelineRun triggeringRun)
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
      log.info("Prompt Optimiser: no change needed for {} — pattern already addressed", promptFile);
      return;
    }

    // Determine next version and backup the current file before overwriting
    int nextVersion =
        auditService.getHistory(promptFile).stream()
            .findFirst()
            .map(e -> e.getVersion() + 1)
            .orElse(1);
    versioningService.backup(promptPath, nextVersion);

    Files.writeString(
        promptPath,
        updatedContent,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.CREATE);

    auditService.record(
        promptFile,
        currentContent,
        updatedContent,
        patterns,
        allPatterns.sourceRunIds(),
        determineTriggerType());

    log.info("Prompt Optimiser: updated {} — patterns: {}", promptFile, patterns);
  }

  private String callLlm(String currentContent, List<String> patterns, PipelineRun run) {
    String userMessage =
        "## Current prompt content\n\n"
            + currentContent
            + "\n\n## Failure patterns to address\n\n"
            + patterns.stream().map(p -> "- " + p).collect(Collectors.joining("\n"));

    Prompt prompt =
        new Prompt(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userMessage)));

    ResolvedModel resolved = modelResolver.resolve("PromptOptimiserAgent", run.getAiProviderEnum());
    var response =
        fallbackLlmRouter.callWithFallback(
            resolved.providerName(),
            resolved.getModel(),
            prompt,
            run.getRunId(),
            "prompt-optimiser",
            FallbackLlmRouter.FallbackMode.IMMEDIATE,
            0,
            false);

    // Track tokens for billing accuracy
    try {
      int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
      int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
      stateStore.addTokensToRun(run.getRunId(), inTok, outTok);
      log.debug("PromptOptimiser tokens: run={} in={} out={}", run.getRunId(), inTok, outTok);
    } catch (Exception e) {
      log.warn(
          "PromptOptimiser: failed to track tokens for run {}: {}", run.getRunId(), e.getMessage());
    }

    return response.getResult().getOutput().getText();
  }

  private boolean shouldRun() {
    long totalCompleted = stateStore.countCompletedRuns();

    if (props.getRunInterval() > 0 && totalCompleted % props.getRunInterval() == 0) return true;

    List<PipelineRun> recent = stateStore.getRecentCompletedRuns(props.getAnalysisWindowRuns());
    if (recent.size() < props.getAnalysisWindowRuns()) return false;

    long failedRuns =
        recent.stream()
            .filter(
                r ->
                    stateStore.getTasksForRun(r.getRunId()).stream()
                        .anyMatch(
                            t -> "FAILED".equals(t.getStatus()) || "BLOCKED".equals(t.getStatus())))
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
      log.error("Prompt Optimiser: commit failed for run {}: {}", run.getRunId(), e.getMessage());
    }
  }
}
