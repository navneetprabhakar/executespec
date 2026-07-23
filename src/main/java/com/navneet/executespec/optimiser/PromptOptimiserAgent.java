package com.navneet.executespec.optimiser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class PromptOptimiserAgent {

  /** RR-1277 — directory for inert prompt candidates; the live source is never overwritten. */
  static final String CANDIDATE_DIR = "prompt-optimiser-candidates";

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
  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final PromptOptimiserProperties props;
  private final StateStore stateStore;

  @Autowired
  public PromptOptimiserAgent(
      PromptPatternAnalyser analyser,
      PromptAuditService auditService,
      LlmRouter llmRouter,
      ModelResolver modelResolver,
      PromptOptimiserProperties props,
      StateStore stateStore) {
    this.analyser = analyser;
    this.auditService = auditService;
    this.llmRouter = llmRouter;
    this.modelResolver = modelResolver;
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

      log.info(
          "Prompt Optimiser: found patterns in — {}",
          String.join(", ", patterns.patternsByPrompt().keySet()));

      for (Map.Entry<String, List<String>> entry : patterns.patternsByPrompt().entrySet()) {
        optimisePrompt(entry.getKey(), entry.getValue(), patterns, completedRun);
      }

      // RR-1277 — the optimiser writes INERT candidate artifacts only; it never mutates the live
      // prompt source (and therefore never auto-commits). Operators review candidates under the
      // candidate directory and promote them deliberately through a reviewed change.
      log.info(
          "Prompt Optimiser produced candidates for run {} in '{}/' — live prompts untouched;"
              + " review and promote deliberately.",
          completedRun.getRunId(),
          CANDIDATE_DIR);

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

    int nextVersion =
        auditService.getHistory(promptFile).stream()
            .findFirst()
            .map(e -> e.getVersion() + 1)
            .orElse(1);

    // RR-1277 / RR-1301 — NEVER overwrite the canonical prompt source (nor drop backup files into
    // the checkout) at runtime. An LLM-authored prompt has no schema / size / security-include /
    // conformance / fixed-corpus-eval / signature / approval gate, so a bad aggregate heuristic or
    // a
    // compromised provider could poison instructions for EVERY tenant, and truncating a live source
    // file from a scheduled task can also mutate a developer/server checkout. Instead write an
    // inert
    // CANDIDATE artifact that an operator reviews and promotes deliberately; the live prompt is
    // untouched, so a partial write / backup failure can never leave a corrupted live prompt with
    // no
    // rollback. (Durable candidate registry + deterministic security/conformance/eval promotion is
    // the deferred full fix; this stops the runtime source mutation, which is the P1 exposure.)
    Path candidatePath = writeCandidate(promptFile, updatedContent, nextVersion);

    auditService.record(
        promptFile,
        currentContent,
        updatedContent,
        patterns,
        allPatterns.sourceRunIds(),
        determineTriggerType());

    log.info(
        "Prompt Optimiser: wrote CANDIDATE v{} for {} at {} — live source untouched; review and"
            + " promote deliberately. Patterns: {}",
        nextVersion,
        promptFile,
        candidatePath == null ? "<unavailable>" : candidatePath,
        patterns);
  }

  /**
   * RR-1277 — persist the proposed prompt as an inert candidate under a dedicated directory, never
   * over the live source. Failure to write the candidate is non-fatal (the live prompt is unchanged
   * regardless), so it is logged rather than thrown.
   */
  private Path writeCandidate(String promptFile, String updatedContent, int version) {
    try {
      Path dir = Path.of(CANDIDATE_DIR);
      Files.createDirectories(dir);
      // Flatten any subdirectory in promptFile so the candidate name is a single safe filename.
      String flat = promptFile.replace('/', '_').replace('\\', '_');
      Path candidate = dir.resolve(flat + ".v" + version + ".candidate");
      Files.writeString(
          candidate,
          updatedContent,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      return candidate;
    } catch (IOException e) {
      log.warn(
          "Prompt Optimiser: could not persist candidate for {} (v{}): {}",
          promptFile,
          version,
          e.getMessage());
      return null;
    }
  }

  private String callLlm(String currentContent, List<String> patterns, PipelineRun run) {
    String userMessage =
        "## Current prompt content\n\n"
            + currentContent
            + "\n\n## Failure patterns to address\n\n"
            + patterns.stream().map(p -> "- " + p).collect(Collectors.joining("\n"));

    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(
                    PromptTrustBoundary.wrap("PROMPT_OPTIMISER_EVIDENCE", userMessage))));

    ResolvedModel resolved = modelResolver.resolve("PromptOptimiserAgent", run.getAiProviderEnum());
    var response =
        llmRouter.callText(
            resolved.providerName(),
            resolved.getModel(),
            prompt.getInstructions(),
            run.getRunId(),
            "PromptOptimiserAgent",
            "prompt.optimize");

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

    // RR-1299 — sample the TERMINAL cohort (COMPLETE/PARTIAL/FAILED), not COMPLETE-only, so a run
    // that actually failed (status FAILED/PARTIAL) can be counted. Sampling COMPLETE-only meant the
    // failure branch could never trigger and only the modulo cadence ever ran.
    List<PipelineRun> recent = stateStore.getRecentTerminalRuns(props.getAnalysisWindowRuns());
    if (recent.size() < props.getAnalysisWindowRuns()) return false;

    long failedRuns = recent.stream().filter(this::runCountedAsFailure).count();

    double failureRate = (double) failedRuns / recent.size();
    return failureRate >= props.getFailureRateThreshold();
  }

  /**
   * RR-1299 — a run is a failure signal when it terminalized FAILED/PARTIAL, or when any of its
   * tasks is FAILED/BLOCKED (a task-level failure inside an otherwise-COMPLETE run).
   */
  boolean runCountedAsFailure(PipelineRun r) {
    String status = r.getStatus();
    if ("FAILED".equals(status) || "PARTIAL".equals(status)) return true;
    return stateStore.getTasksForRun(r.getRunId()).stream()
        .anyMatch(t -> "FAILED".equals(t.getStatus()) || "BLOCKED".equals(t.getStatus()));
  }

  private String determineTriggerType() {
    long totalCompleted = stateStore.countCompletedRuns();
    return props.getRunInterval() > 0 && totalCompleted % props.getRunInterval() == 0
        ? "SCHEDULED"
        : "FAILURE_RATE";
  }

  /**
   * Resolves a prompt file path. Caller is expected to pass the actual filename WITH its real
   * extension (`.md` for V2 canonical prompts, `.txt` for V1 legacy archives). Earlier versions
   * appended `.txt` unconditionally — which never matched anything in the V2 layout. Audit
   * 2026-05-12 review item F #43.
   */
  private Path resolvePromptPath(String promptFile) {
    return Path.of("src/main/resources/prompts", promptFile);
  }
}
