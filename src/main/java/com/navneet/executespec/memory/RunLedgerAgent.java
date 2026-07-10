package com.navneet.executespec.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates a compressed ledger entry for a completed run and appends it to {@code
 * project_run_ledger}. Idempotent: skips if an entry already exists for the runId. Triggers {@link
 * MemoryCompactionService} every 10th run.
 *
 * <p>Uses the canonical {@link LlmRouter} text seam at LIGHTWEIGHT tier. Memory generation must
 * never break a run — all failures are caught and logged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunLedgerAgent {

  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final ProjectRunLedgerRepository ledgerRepo;
  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final MemoryCompactionService compactionService;
  private final AgentProperties agentProperties;
  private final PromptAssembler promptAssembler;

  private static final int LEDGER_CHAR_CAP = 2000;

  @Transactional
  public void generateLedgerEntry(String runId) {
    if (runId == null || ledgerRepo.existsByRunId(runId)) {
      return;
    }
    Optional<PipelineRun> maybeRun = runRepo.findById(runId);
    if (maybeRun.isEmpty()) return;
    PipelineRun run = maybeRun.get();

    String projectId = run.getProjectId();
    String orgId = run.getOrgId();
    if (projectId == null || projectId.isBlank()) {
      return; // memory is project-scoped only
    }
    if (orgId == null || orgId.isBlank()) {
      orgId = "default";
    }

    try {
      List<AgentTask> tasks = taskRepo.findByRunId(runId);
      String taskSummaries = buildTaskSummaries(tasks);
      int runNumber = ledgerRepo.countByProjectId(projectId) + 1;

      String filled =
          promptAssembler.assemble(
              "run-ledger.txt",
              java.util.Map.of(
                  "RUN_NUMBER", String.valueOf(runNumber),
                  "USER_REQUEST", truncate(run.getUserRequest(), 500),
                  "PLAN_SUMMARY", truncate(run.getRunSummary(), 1000),
                  "TASK_SUMMARIES", truncate(taskSummaries, 2000),
                  "DIFF_STAT", diffStat(runId)));

      String entry = callLightweightLlm(filled, run, runId);
      if (entry == null || entry.isBlank()) {
        log.warn("RunLedgerAgent: empty LLM response for run {}", runId);
        return;
      }
      entry = truncate(entry.trim(), LEDGER_CHAR_CAP);

      ProjectRunLedger ledger =
          ProjectRunLedger.builder()
              .projectId(projectId)
              .runId(runId)
              .runNumber(runNumber)
              .entry(entry)
              .tokenCount(estimateTokens(entry))
              .orgId(orgId)
              .build();
      ledgerRepo.save(ledger);

      log.info(
          "RunLedgerAgent: wrote R{} for project {} (run {}), ~{} tokens",
          runNumber,
          projectId,
          runId,
          ledger.getTokenCount());

      // Compact every 10 runs, starting at R20 (we want ≥10 uncompacted + 10 to compact).
      if (runNumber >= 20 && runNumber % 10 == 0) {
        compactionService.compactIfNeeded(projectId, runNumber, orgId);
      }
    } catch (Exception e) {
      log.warn("RunLedgerAgent: failed for run {}: {}", runId, e.getMessage(), e);
    }
  }

  private String buildTaskSummaries(List<AgentTask> tasks) {
    if (tasks == null || tasks.isEmpty()) return "(no tasks)";
    String withNotes =
        tasks.stream()
            .filter(t -> t.getCompletionNotes() != null && !t.getCompletionNotes().isBlank())
            .map(t -> "- " + t.getTitle() + " [" + t.getStatus() + "]: " + t.getCompletionNotes())
            .collect(Collectors.joining("\n"));
    if (!withNotes.isBlank()) return withNotes;
    return tasks.stream()
        .map(t -> "- " + t.getTitle() + " [" + t.getStatus() + "]")
        .collect(Collectors.joining("\n"));
  }

  private String callLightweightLlm(String systemPrompt, PipelineRun run, String runId) {
    ResolvedModel resolved = modelResolver.resolve("RunLedgerAgent", run.getAiProviderEnum());
    ChatResponse response =
        llmRouter.callText(
            resolved.providerName(),
            resolved.getModel(),
            List.of(
                new SystemMessage(systemPrompt), new UserMessage("Generate the ledger entry now.")),
            runId,
            "run-ledger",
            "generate");
    return response.getResult().getOutput().getText();
  }

  private int estimateTokens(String text) {
    return text == null ? 0 : (int) Math.ceil(text.split("\\s+").length * 1.3);
  }

  /**
   * Read the final V2 output commit's stat after the orchestrator commits workspace changes. This
   * is deliberately a bounded, argument-array git invocation: no shell interpolation and no
   * unbounded output enters the ledger prompt. Missing/invalid workspaces remain an honest
   * unavailable marker instead of pretending that sequential execution explains the absence.
   */
  private String diffStat(String runId) {
    try {
      String root = agentProperties.getWorkspace();
      if (root == null || root.isBlank()) {
        return "(diff stat unavailable — workspace not configured)";
      }
      Path workspace = Path.of(root, runId).toAbsolutePath().normalize();
      if (!Files.isDirectory(workspace.resolve(".git"))) {
        return "(diff stat unavailable — workspace has no git repository)";
      }
      ProcessBuilder git =
          new ProcessBuilder("git", "show", "--stat", "--format=", "HEAD")
              .directory(workspace.toFile())
              .redirectErrorStream(true);
      git.environment().keySet().retainAll(List.of("PATH"));
      Process process = git.start();
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "(diff stat unavailable — git timed out)";
      }
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (process.exitValue() != 0 || output.isBlank()) {
        return "(diff stat unavailable — git show failed)";
      }
      return truncate(output, 4000);
    } catch (Exception e) {
      log.debug("RunLedgerAgent: diff stat unavailable for run {}: {}", runId, e.getMessage());
      return "(diff stat unavailable — " + e.getClass().getSimpleName() + ")";
    }
  }

  private String truncate(String s, int maxChars) {
    if (s == null) return "";
    return s.length() > maxChars ? s.substring(0, maxChars) + "..." : s;
  }
}
