package com.navneet.executespec.repositoryexplorer;

import java.util.List;

import com.navneet.executespec.repositoryintel.RepositoryEvidence;
import com.navneet.executespec.repositoryintel.RepositorySnapshotId;

/** Immutable, bounded worker handoff. Raw explorer history is intentionally unrepresentable. */
public record TaskEvidenceBundle(
    String taskId,
    List<String> requirementIds,
    String goal,
    String localizationQuestion,
    List<RepositoryEvidence> citations,
    List<String> rejectedCandidateSummaries,
    List<String> searchStrategy,
    List<String> enginesConsulted,
    RepositorySnapshotId snapshot,
    ExplorerExperimentArm experimentArm,
    Source source,
    String fallbackReason,
    boolean insufficientEvidence,
    boolean truncated,
    int totalCharacters,
    int estimatedTokens,
    String stableFingerprint) {

  public enum Source {
    DETERMINISTIC_ONLY,
    EXPLORER_VERIFIED,
    DETERMINISTIC_FALLBACK
  }

  public TaskEvidenceBundle {
    taskId = required("taskId", taskId);
    requirementIds = List.copyOf(requirementIds);
    goal = required("goal", goal);
    localizationQuestion = required("localizationQuestion", localizationQuestion);
    citations = List.copyOf(citations);
    rejectedCandidateSummaries = List.copyOf(rejectedCandidateSummaries);
    searchStrategy = List.copyOf(searchStrategy);
    enginesConsulted = List.copyOf(enginesConsulted);
    if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
    if (experimentArm == null) throw new IllegalArgumentException("experimentArm is required");
    if (source == null) throw new IllegalArgumentException("source is required");
    fallbackReason = normalize(fallbackReason);
    if (source == Source.EXPLORER_VERIFIED && fallbackReason != null) {
      throw new IllegalArgumentException("verified explorer bundles cannot have a fallback reason");
    }
    if (totalCharacters < 0 || estimatedTokens < 0) {
      throw new IllegalArgumentException("bundle accounting cannot be negative");
    }
    stableFingerprint = required("stableFingerprint", stableFingerprint);
  }

  private static String required(String name, String value) {
    String cleaned = normalize(value);
    if (cleaned == null) throw new IllegalArgumentException(name + " is required");
    return cleaned;
  }

  private static String normalize(String value) {
    if (value == null) return null;
    String cleaned = value.strip().replaceAll("\\s+", " ");
    return cleaned.isEmpty() ? null : cleaned;
  }
}
