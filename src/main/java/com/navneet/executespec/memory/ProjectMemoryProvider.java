package com.navneet.executespec.memory;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.memory.entity.ProjectMemorySnapshot;
import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectMemorySnapshotRepository;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the project memory block injected into {@code PlannerAgent}'s system prompt. Returns empty
 * string for projects with no history (first run).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectMemoryProvider {

  private final ProjectRunLedgerRepository ledgerRepo;
  private final ProjectMemorySnapshotRepository snapshotRepo;

  private static final int MAX_MEMORY_TOKENS = 12000;

  /** Conservative character budget (four characters per token) for planner memory. */
  static final int MAX_MEMORY_CHARS = MAX_MEMORY_TOKENS * 4;

  /** Renders the full memory context (snapshot + last 10 entries). Empty if no history. */
  public String buildPlannerContext(String projectId) {
    if (projectId == null || projectId.isBlank()) return "";
    StringBuilder snapshotBlock = new StringBuilder();

    Optional<ProjectMemorySnapshot> snapshot =
        snapshotRepo.findTopByProjectIdOrderByVersionDesc(projectId);
    if (snapshot.isPresent()) {
      ProjectMemorySnapshot s = snapshot.get();
      snapshotBlock
          .append("## PROJECT HISTORY (R")
          .append(s.getCoversRunsFrom())
          .append("-R")
          .append(s.getCoversRunsTo())
          .append(", compacted)\n")
          .append(s.getContent())
          .append("\n\n");
    }

    List<ProjectRunLedger> recent =
        ledgerRepo.findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc(projectId);
    // The snapshot is the durable project baseline and always wins over recency. Select only the
    // newest complete ledger entries that fit after that baseline; never emit an oversized prompt
    // block and silently rely on provider truncation.
    int remainingChars = MAX_MEMORY_CHARS;
    String snapshot = truncate(snapshotBlock.toString(), remainingChars);
    remainingChars -= snapshot.length();
    List<String> selectedRecent = new java.util.ArrayList<>();
    if (remainingChars > 0 && !recent.isEmpty()) {
      // Repository order is newest-first. Add newest entries first, then render chronologically.
      int sectionOverhead = "## RECENT RUNS\n".length();
      remainingChars -= Math.min(remainingChars, sectionOverhead);
      for (ProjectRunLedger entry : recent) {
        String rendered = truncate(entry.getEntry(), remainingChars);
        String candidate = rendered + "\n\n";
        if (rendered.isBlank() || candidate.length() > remainingChars) break;
        selectedRecent.add(rendered);
        remainingChars -= candidate.length();
      }
    }
    StringBuilder ctx = new StringBuilder(snapshot);
    if (!selectedRecent.isEmpty()) {
      ctx.append("## RECENT RUNS\n");
      for (int i = selectedRecent.size() - 1; i >= 0; i--) {
        ctx.append(selectedRecent.get(i)).append("\n\n");
      }
    }
    return ctx.toString();
  }

  private static String truncate(String value, int maxChars) {
    if (value == null || value.isEmpty() || maxChars <= 0) return "";
    return value.length() <= maxChars ? value : value.substring(0, maxChars);
  }

  public MemoryStats getStats(String projectId) {
    int total = ledgerRepo.countByProjectId(projectId);
    Optional<ProjectMemorySnapshot> snapshot =
        snapshotRepo.findTopByProjectIdOrderByVersionDesc(projectId);
    List<ProjectRunLedger> recent =
        ledgerRepo.findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc(projectId);

    int snapTokens = snapshot.map(s -> s.getTokenCount() != null ? s.getTokenCount() : 0).orElse(0);
    int recentTokens =
        recent.stream().mapToInt(e -> e.getTokenCount() != null ? e.getTokenCount() : 0).sum();

    int measuredTokens = (int) Math.ceil((snapTokens + recentTokens) * 1.0);
    return new MemoryStats(
        total,
        snapshot.map(ProjectMemorySnapshot::getVersion).orElse(0),
        snapshot.map(ProjectMemorySnapshot::getCoversRunsTo).orElse(0),
        recent.size(),
        Math.min(MAX_MEMORY_TOKENS, measuredTokens),
        MAX_MEMORY_TOKENS);
  }

  public record MemoryStats(
      int totalRuns,
      int snapshotVersion,
      int snapshotCoversUpTo,
      int recentEntryCount,
      int currentTokens,
      int tokenBudget) {}
}
