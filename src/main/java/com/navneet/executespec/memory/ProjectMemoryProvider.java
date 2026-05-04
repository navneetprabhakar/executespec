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

  /** Renders the full memory context (snapshot + last 10 entries). Empty if no history. */
  public String buildPlannerContext(String projectId) {
    if (projectId == null || projectId.isBlank()) return "";
    StringBuilder ctx = new StringBuilder();

    Optional<ProjectMemorySnapshot> snapshot =
        snapshotRepo.findTopByProjectIdOrderByVersionDesc(projectId);
    if (snapshot.isPresent()) {
      ProjectMemorySnapshot s = snapshot.get();
      ctx.append("## PROJECT HISTORY (R")
          .append(s.getCoversRunsFrom())
          .append("-R")
          .append(s.getCoversRunsTo())
          .append(", compacted)\n")
          .append(s.getContent())
          .append("\n\n");
    }

    List<ProjectRunLedger> recent =
        ledgerRepo.findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc(projectId);
    if (!recent.isEmpty()) {
      ctx.append("## RECENT RUNS\n");
      // Render chronologically for readability.
      for (int i = recent.size() - 1; i >= 0; i--) {
        ctx.append(recent.get(i).getEntry()).append("\n\n");
      }
    }

    return ctx.length() == 0 ? "" : ctx.toString();
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

    return new MemoryStats(
        total,
        snapshot.map(ProjectMemorySnapshot::getVersion).orElse(0),
        snapshot.map(ProjectMemorySnapshot::getCoversRunsTo).orElse(0),
        recent.size(),
        snapTokens + recentTokens,
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
