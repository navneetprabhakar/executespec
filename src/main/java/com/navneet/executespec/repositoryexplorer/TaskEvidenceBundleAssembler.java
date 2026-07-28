package com.navneet.executespec.repositoryexplorer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.repositoryintel.RepositoryEvidence;
import com.navneet.executespec.repositoryintel.RepositoryEvidenceBatch;

/** Validates explorer selections and creates a deterministic, bounded worker handoff. */
public final class TaskEvidenceBundleAssembler {

  private static final int MAX_TEXT = 2_000;
  private static final int MAX_LIST_ITEM = 256;
  private static final Comparator<RepositoryEvidence> EVIDENCE_ORDER =
      Comparator.comparing((RepositoryEvidence evidence) -> value(evidence.path()))
          .thenComparing(
              evidence -> evidence.startLine() == null ? Integer.MAX_VALUE : evidence.startLine())
          .thenComparing(RepositoryEvidence::evidenceId);

  public TaskEvidenceBundle assemble(
      String taskId,
      List<String> requirementIds,
      String goal,
      String localizationQuestion,
      RepositoryEvidenceBatch deterministicBatch,
      ExplorerSelection selection,
      TaskEvidenceBudget budget) {
    if (deterministicBatch == null) {
      throw new IllegalArgumentException("deterministicBatch is required");
    }
    if (selection == null) throw new IllegalArgumentException("selection is required");
    budget = budget == null ? TaskEvidenceBudget.DEFAULT : budget;

    String normalizedTaskId = text("taskId", taskId, 256);
    List<String> normalizedRequirements =
        sortedDistinct(requirementIds, 128, 256, "requirementIds");
    String normalizedGoal = text("goal", goal, MAX_TEXT);
    String normalizedQuestion = text("localizationQuestion", localizationQuestion, MAX_TEXT);

    SelectionResolution resolution = resolve(deterministicBatch, selection);
    boolean explorerVerified = selection.status() == ExplorerSelection.Status.VERIFIED;
    List<String> rejected =
        explorerVerified
            ? boundedSorted(selection.rejectedCandidateSummaries(), budget.maxRejectedSummaries())
            : List.of();
    List<String> strategy =
        explorerVerified ? boundedSorted(selection.searchStrategy(), 16) : List.of();
    List<String> engines =
        explorerVerified ? boundedSorted(selection.enginesConsulted(), 16) : List.of();

    int baseCharacters =
        characters(normalizedTaskId)
            + characters(normalizedRequirements)
            + characters(normalizedGoal)
            + characters(normalizedQuestion)
            + characters(rejected)
            + characters(strategy)
            + characters(engines)
            + characters(deterministicBatch.requestedSnapshot().stableId())
            + 128;
    int hardCharacterCap =
        Math.min(budget.maxCharacters(), Math.multiplyExact(budget.maxEstimatedTokens(), 4));
    if (baseCharacters > hardCharacterCap) {
      throw new IllegalArgumentException("bundle metadata exceeds the evidence budget");
    }

    List<RepositoryEvidence> citations = new ArrayList<>();
    int totalCharacters = baseCharacters;
    boolean truncated = false;
    for (RepositoryEvidence candidate : resolution.candidates()) {
      if (citations.size() >= budget.maxCitations()
          || totalCharacters + candidate.characterCount() > hardCharacterCap) {
        truncated = true;
        continue;
      }
      citations.add(candidate);
      totalCharacters += candidate.characterCount();
    }
    int estimatedTokens =
        citations.isEmpty() ? Math.max(1, (totalCharacters + 3) / 4) : (totalCharacters + 3) / 4;
    boolean insufficientEvidence = citations.isEmpty();
    String fingerprint =
        fingerprint(
            normalizedTaskId,
            normalizedRequirements,
            normalizedGoal,
            normalizedQuestion,
            citations,
            rejected,
            strategy,
            engines,
            deterministicBatch.requestedSnapshot().stableId(),
            selection.arm().name(),
            resolution.source().name(),
            resolution.fallbackReason(),
            Boolean.toString(insufficientEvidence),
            Boolean.toString(truncated),
            Integer.toString(totalCharacters),
            Integer.toString(estimatedTokens));

    return new TaskEvidenceBundle(
        normalizedTaskId,
        normalizedRequirements,
        normalizedGoal,
        normalizedQuestion,
        citations,
        rejected,
        strategy,
        engines,
        deterministicBatch.requestedSnapshot(),
        selection.arm(),
        resolution.source(),
        resolution.fallbackReason(),
        insufficientEvidence,
        truncated,
        totalCharacters,
        estimatedTokens,
        fingerprint);
  }

  private static SelectionResolution resolve(
      RepositoryEvidenceBatch deterministicBatch, ExplorerSelection selection) {
    List<RepositoryEvidence> deterministic =
        deterministicBatch.evidence().stream()
            .filter(evidence -> evidence.provenance().deterministic())
            .sorted(EVIDENCE_ORDER)
            .toList();
    if (selection.status() != ExplorerSelection.Status.VERIFIED) {
      boolean notInvoked = selection.status() == ExplorerSelection.Status.NOT_INVOKED;
      return new SelectionResolution(
          deterministic,
          notInvoked
              ? TaskEvidenceBundle.Source.DETERMINISTIC_ONLY
              : TaskEvidenceBundle.Source.DETERMINISTIC_FALLBACK,
          notInvoked
              ? null
              : selection.failureReason() == null
                  ? selection.status().name()
                  : selection.failureReason());
    }

    Map<String, RepositoryEvidence> byId = new HashMap<>();
    deterministicBatch.evidence().forEach(evidence -> byId.put(evidence.evidenceId(), evidence));
    Set<String> requested = new LinkedHashSet<>(selection.selectedEvidenceIds());
    List<RepositoryEvidence> selected = new ArrayList<>();
    for (String id : requested) {
      RepositoryEvidence evidence = byId.get(id);
      if (evidence == null
          || !evidence
              .snapshot()
              .stableId()
              .equals(deterministicBatch.requestedSnapshot().stableId())) {
        return new SelectionResolution(
            deterministic,
            TaskEvidenceBundle.Source.DETERMINISTIC_FALLBACK,
            "INVALID_OR_STALE_CITATION");
      }
      selected.add(evidence);
    }
    selected.sort(EVIDENCE_ORDER);
    return new SelectionResolution(selected, TaskEvidenceBundle.Source.EXPLORER_VERIFIED, null);
  }

  private static List<String> sortedDistinct(
      List<String> source, int maxItems, int maxCharacters, String field) {
    if (source == null || source.isEmpty())
      throw new IllegalArgumentException(field + " is required");
    List<String> normalized =
        source.stream()
            .map(value -> text(field, value, maxCharacters))
            .distinct()
            .sorted()
            .toList();
    if (normalized.size() > maxItems)
      throw new IllegalArgumentException(field + " exceeds " + maxItems + " items");
    return normalized;
  }

  private static List<String> boundedSorted(List<String> source, int maxItems) {
    if (source == null || source.isEmpty() || maxItems == 0) return List.of();
    return source.stream()
        .map(value -> text("metadata item", value, MAX_LIST_ITEM))
        .distinct()
        .sorted()
        .limit(maxItems)
        .toList();
  }

  private static String text(String field, String value, int maxCharacters) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    String cleaned = value.strip().replaceAll("\\s+", " ");
    if (cleaned.isEmpty()) throw new IllegalArgumentException(field + " is required");
    if (cleaned.length() > maxCharacters) {
      throw new IllegalArgumentException(field + " exceeds " + maxCharacters + " characters");
    }
    return cleaned;
  }

  private static int characters(String value) {
    return value == null ? 0 : value.length();
  }

  private static int characters(List<String> values) {
    return values.stream().mapToInt(value -> value.length() + 4).sum();
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private static String fingerprint(Object... values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Object value : values) {
        if (value instanceof List<?> list) {
          update(digest, Integer.toString(list.size()));
          for (Object item : list) {
            update(
                digest,
                item instanceof RepositoryEvidence evidence
                    ? evidence.evidenceId()
                    : String.valueOf(item));
          }
        } else {
          update(digest, value == null ? null : String.valueOf(value));
        }
      }
      return java.util.HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private record SelectionResolution(
      List<RepositoryEvidence> candidates,
      TaskEvidenceBundle.Source source,
      String fallbackReason) {}
}
