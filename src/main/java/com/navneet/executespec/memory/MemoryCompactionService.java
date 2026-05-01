package com.navneet.executespec.memory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.memory.entity.ProjectMemorySnapshot;
import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectMemorySnapshotRepository;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Compacts uncompacted ledger entries older than {@code currentRunNumber - 10} into a {@link
 * ProjectMemorySnapshot}. Called by {@link RunLedgerAgent} every 10 runs (starting at R20).
 * Non-fatal — any failure is logged and the run completes normally.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryCompactionService {

  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final ProjectRunLedgerRepository ledgerRepo;
  private final ProjectMemorySnapshotRepository snapshotRepo;

  /**
   * Force-compact every uncompacted ledger entry for a project, regardless of how many runs have
   * completed. Exposed via the Memory tab's Compact button so users can trigger snapshot generation
   * on demand (handy for demos + testing without driving 20+ runs). Returns the new snapshot
   * version, or 0 if nothing needed compacting.
   */
  @Transactional
  public int compactNow(String projectId, String orgId) {
    List<ProjectRunLedger> entries =
        ledgerRepo.findUncompactedBeforeRunNumber(projectId, Integer.MAX_VALUE);
    return doCompact(projectId, orgId, entries);
  }

  @Transactional
  public void compactIfNeeded(String projectId, int currentRunNumber, String orgId) {
    int cutoff = currentRunNumber - 10;
    List<ProjectRunLedger> entries = ledgerRepo.findUncompactedBeforeRunNumber(projectId, cutoff);
    doCompact(projectId, orgId, entries);
  }

  private int doCompact(String projectId, String orgId, List<ProjectRunLedger> entries) {
    if (entries.isEmpty()) {
      log.debug("MemoryCompactionService: no uncompacted entries for project {}", projectId);
      return 0;
    }

    try {
      Optional<ProjectMemorySnapshot> existing =
          snapshotRepo.findTopByProjectIdOrderByVersionDesc(projectId);
      String existingContent =
          existing.map(ProjectMemorySnapshot::getContent).orElse("(no prior history)");

      String newEntries =
          entries.stream().map(ProjectRunLedger::getEntry).collect(Collectors.joining("\n\n"));

      String template =
          new ClassPathResource("prompts/memory-compaction.txt")
              .getContentAsString(StandardCharsets.UTF_8);
      String filled =
          template
              .replace("{{EXISTING_SNAPSHOT}}", existingContent)
              .replace("{{NEW_ENTRIES}}", newEntries);

      String compacted = callLightweightLlm(filled, projectId);
      if (compacted == null || compacted.isBlank()) {
        log.warn("MemoryCompactionService: empty LLM response for project {}", projectId);
        return 0;
      }

      int fromRun =
          existing
              .map(ProjectMemorySnapshot::getCoversRunsFrom)
              .orElse(entries.get(0).getRunNumber());
      int toRun = entries.get(entries.size() - 1).getRunNumber();
      int newVersion = existing.map(s -> s.getVersion() + 1).orElse(1);

      ProjectMemorySnapshot snapshot =
          ProjectMemorySnapshot.builder()
              .projectId(projectId)
              .version(newVersion)
              .content(compacted.trim())
              .coversRunsFrom(fromRun)
              .coversRunsTo(toRun)
              .tokenCount(estimateTokens(compacted))
              .orgId(orgId != null && !orgId.isBlank() ? orgId : "default")
              .build();
      snapshotRepo.save(snapshot);

      for (ProjectRunLedger entry : entries) {
        entry.setCompactedInto(snapshot.getId());
      }
      ledgerRepo.saveAll(entries);

      log.info(
          "MemoryCompactionService: compacted {} entries (R{}-R{}) into v{} for project {}",
          entries.size(),
          fromRun,
          toRun,
          newVersion,
          projectId);
      return newVersion;
    } catch (Exception e) {
      log.warn("MemoryCompactionService failed for project {}: {}", projectId, e.getMessage(), e);
      return 0;
    }
  }

  private String callLightweightLlm(String systemPrompt, String projectId) {
    // Default provider for compaction; project's ai_provider not needed at this layer.
    ResolvedModel resolved = modelResolver.resolve("RunLedgerAgent", LlmProvider.OPENAI);
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(systemPrompt),
                new UserMessage("Emit the updated snapshot now.")));
    ChatResponse response =
        llmRouter.callWithFallback(
            resolved.providerName(),
            resolved.getModel(),
            prompt,
            "compact-" + projectId,
            "memory-compaction",
            LlmRouter.FallbackMode.IMMEDIATE,
            0,
            false);
    return response.getResult().getOutput().getText();
  }

  private int estimateTokens(String text) {
    return text == null ? 0 : (int) Math.ceil(text.split("\\s+").length * 1.3);
  }
}
