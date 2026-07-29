package com.navneet.executespec.repositoryintel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministically ordered evidence plus an explicit outcome for every requested capability.
 * Construction is intentionally factory-only so another adapter cannot bypass the originating
 * query's capability, freshness, accounting, and truncation invariants.
 */
public final class RepositoryEvidenceBatch {

  private static final Comparator<RepositoryEvidence> EVIDENCE_ORDER =
      Comparator.comparing((RepositoryEvidence e) -> capabilityRank(e.kind()))
          .thenComparing(e -> value(e.path()))
          .thenComparing(e -> e.startLine() == null ? Integer.MAX_VALUE : e.startLine())
          .thenComparing(RepositoryEvidence::evidenceId);

  private final String queryFingerprint;
  private final RepositorySnapshotId requestedSnapshot;
  private final List<RepositoryEvidence> evidence;
  private final Map<RepositoryCapability, CapabilityOutcome> outcomes;
  private final boolean truncated;
  private final int returnedCharacters;
  private final int estimatedTokens;

  private RepositoryEvidenceBatch(
      String queryFingerprint,
      RepositorySnapshotId requestedSnapshot,
      List<RepositoryEvidence> evidence,
      Map<RepositoryCapability, CapabilityOutcome> outcomes,
      boolean truncated,
      int returnedCharacters,
      int estimatedTokens) {
    this.queryFingerprint = queryFingerprint;
    this.requestedSnapshot = requestedSnapshot;
    this.evidence = evidence;
    this.outcomes = outcomes;
    this.truncated = truncated;
    this.returnedCharacters = returnedCharacters;
    this.estimatedTokens = estimatedTokens;
  }

  public static RepositoryEvidenceBatch of(
      RepositoryQuery query,
      List<RepositoryEvidence> evidence,
      Map<RepositoryCapability, CapabilityOutcome> outcomes,
      boolean truncated) {
    if (query == null) throw new IllegalArgumentException("query cannot be null");
    List<RepositoryEvidence> normalizedEvidence =
        normalizeEvidence(evidence, query.fingerprint(), query.expectedSnapshot());
    Map<RepositoryCapability, CapabilityOutcome> normalizedOutcomes = normalizeOutcomes(outcomes);
    if (!normalizedOutcomes.keySet().equals(query.capabilities())) {
      throw new IllegalArgumentException("outcomes must cover exactly the requested capabilities");
    }
    validateHitAccounting(normalizedEvidence, normalizedOutcomes);
    boolean outcomeTruncated =
        normalizedOutcomes.values().stream().anyMatch(CapabilityOutcome::truncated);
    if (truncated != outcomeTruncated) {
      throw new IllegalArgumentException("top-level truncation must match the capability outcomes");
    }
    int characters =
        RepositoryJsonBudget.batchCharacters(
            query.fingerprint(),
            query.expectedSnapshot(),
            normalizedEvidence,
            normalizedOutcomes,
            truncated);
    int tokens = Math.max(1, (characters + 3) / 4);
    if (normalizedEvidence.size() > query.budget().maxTotalHits()
        || characters > query.budget().maxCharacters()
        || tokens > query.budget().maxEstimatedTokens()) {
      throw new IllegalArgumentException("response envelope exceeds the query budget");
    }
    return new RepositoryEvidenceBatch(
        query.fingerprint(),
        query.expectedSnapshot(),
        normalizedEvidence,
        normalizedOutcomes,
        truncated,
        characters,
        tokens);
  }

  public String queryFingerprint() {
    return queryFingerprint;
  }

  public RepositorySnapshotId requestedSnapshot() {
    return requestedSnapshot;
  }

  public List<RepositoryEvidence> evidence() {
    return evidence;
  }

  public Map<RepositoryCapability, CapabilityOutcome> outcomes() {
    return outcomes;
  }

  public boolean truncated() {
    return truncated;
  }

  public int returnedCharacters() {
    return returnedCharacters;
  }

  public int estimatedTokens() {
    return estimatedTokens;
  }

  public String getQueryFingerprint() {
    return queryFingerprint;
  }

  public RepositorySnapshotId getRequestedSnapshot() {
    return requestedSnapshot;
  }

  public List<RepositoryEvidence> getEvidence() {
    return evidence;
  }

  public Map<RepositoryCapability, CapabilityOutcome> getOutcomes() {
    return outcomes;
  }

  public boolean isTruncated() {
    return truncated;
  }

  public int getReturnedCharacters() {
    return returnedCharacters;
  }

  public int getEstimatedTokens() {
    return estimatedTokens;
  }

  private static List<RepositoryEvidence> normalizeEvidence(
      List<RepositoryEvidence> source,
      String queryFingerprint,
      RepositorySnapshotId requestedSnapshot) {
    Map<String, RepositoryEvidence> unique = new LinkedHashMap<>();
    List<RepositoryEvidence> sorted = new ArrayList<>(source == null ? List.of() : source);
    sorted.sort(EVIDENCE_ORDER);
    for (RepositoryEvidence item : sorted) {
      if (item == null) throw new IllegalArgumentException("evidence cannot contain null");
      if (!queryFingerprint.equals(item.queryFingerprint())) {
        throw new IllegalArgumentException("evidence query fingerprint mismatch");
      }
      if (!requestedSnapshot.stableId().equals(item.snapshot().stableId())) {
        throw new IllegalArgumentException("evidence snapshot mismatch");
      }
      if (!item.snapshot().acceptsEvidence()) {
        throw new IllegalArgumentException("only CURRENT evidence may be returned");
      }
      unique.putIfAbsent(item.evidenceId(), item);
    }
    return List.copyOf(unique.values());
  }

  private static Map<RepositoryCapability, CapabilityOutcome> normalizeOutcomes(
      Map<RepositoryCapability, CapabilityOutcome> source) {
    if (source == null || source.isEmpty()) {
      throw new IllegalArgumentException("outcomes cannot be empty");
    }
    EnumMap<RepositoryCapability, CapabilityOutcome> normalized =
        new EnumMap<>(RepositoryCapability.class);
    source.forEach(
        (capability, outcome) -> {
          if (capability == null || outcome == null || capability != outcome.capability()) {
            throw new IllegalArgumentException("outcome key must match its capability");
          }
          normalized.put(capability, outcome);
        });
    return Collections.unmodifiableMap(normalized);
  }

  private static void validateHitAccounting(
      List<RepositoryEvidence> evidence, Map<RepositoryCapability, CapabilityOutcome> outcomes) {
    EnumMap<RepositoryCapability, Integer> counts = new EnumMap<>(RepositoryCapability.class);
    for (RepositoryEvidence item : evidence) {
      if (!outcomes.containsKey(item.capability())) {
        throw new IllegalArgumentException("evidence capability was not requested");
      }
      counts.merge(item.capability(), 1, Integer::sum);
    }
    outcomes.forEach(
        (capability, outcome) -> {
          if (outcome.hitCount() != counts.getOrDefault(capability, 0)) {
            throw new IllegalArgumentException(
                "outcome hitCount must match returned evidence for " + capability);
          }
        });
  }

  private static int capabilityRank(RepositoryEvidenceKind kind) {
    return switch (kind) {
      case TEXT_MATCH -> 0;
      case SYMBOL -> 1;
      case DECLARATION -> 2;
      case RELATIONSHIP -> 3;
      case CODEMAP_SECTION -> 4;
      case CODE_CHUNK -> 5;
      case DOCUMENT_CHUNK -> 6;
      case ARCHITECTURE_SECTION -> 7;
      case ADR -> 8;
    };
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof RepositoryEvidenceBatch batch)) return false;
    return truncated == batch.truncated
        && returnedCharacters == batch.returnedCharacters
        && estimatedTokens == batch.estimatedTokens
        && queryFingerprint.equals(batch.queryFingerprint)
        && requestedSnapshot.equals(batch.requestedSnapshot)
        && evidence.equals(batch.evidence)
        && outcomes.equals(batch.outcomes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        queryFingerprint,
        requestedSnapshot,
        evidence,
        outcomes,
        truncated,
        returnedCharacters,
        estimatedTokens);
  }

  @Override
  public String toString() {
    return "RepositoryEvidenceBatch[queryFingerprint="
        + queryFingerprint
        + ", requestedSnapshot="
        + requestedSnapshot
        + ", evidence="
        + evidence
        + ", outcomes="
        + outcomes
        + ", truncated="
        + truncated
        + ", returnedCharacters="
        + returnedCharacters
        + ", estimatedTokens="
        + estimatedTokens
        + "]";
  }
}
