package com.navneet.executespec.repositoryintel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Deterministic JSON-envelope accounting used by the repository-intelligence response budget. */
final class RepositoryJsonBudget {

  private RepositoryJsonBudget() {}

  static int evidenceCharacters(RepositoryEvidence evidence) {
    int fields = 0;
    fields += field("evidenceId", string(evidence.evidenceId()));
    fields += field("scope", string(evidence.scope().name()));
    fields += field("capability", string(evidence.capability().name()));
    fields += field("kind", string(evidence.kind().name()));
    fields += field("path", string(evidence.path()));
    fields += field("startLine", number(evidence.startLine()));
    fields += field("endLine", number(evidence.endLine()));
    fields += field("symbol", string(evidence.symbol()));
    fields += field("relationship", string(evidence.relationship()));
    fields += field("excerpt", string(evidence.excerpt()));
    fields += field("contentDigest", string(evidence.contentDigest()));
    fields += field("provenance", string(evidence.provenance().name()));
    fields += field("confidence", Double.toString(evidence.confidence()).length());
    fields += field("ambiguity", string(evidence.ambiguity().name()));
    fields += field("snapshot", snapshotCharacters(evidence.snapshot()));
    fields += field("queryFingerprint", string(evidence.queryFingerprint()));
    return object(fields, 16);
  }

  static int batchCharacters(
      String queryFingerprint,
      RepositorySnapshotId snapshot,
      List<RepositoryEvidence> evidence,
      Map<RepositoryCapability, CapabilityOutcome> outcomes,
      boolean truncated) {
    int evidenceArray = 2;
    for (int i = 0; i < evidence.size(); i++) {
      evidenceArray += evidenceCharacters(evidence.get(i));
      if (i > 0) evidenceArray++;
    }
    int outcomeObject = 2;
    int outcomeIndex = 0;
    for (Map.Entry<RepositoryCapability, CapabilityOutcome> entry : outcomes.entrySet()) {
      outcomeObject += string(entry.getKey().name()) + 1 + outcomeCharacters(entry.getValue());
      if (outcomeIndex++ > 0) outcomeObject++;
    }
    int fields = 0;
    fields += field("queryFingerprint", string(queryFingerprint));
    fields += field("requestedSnapshot", snapshotCharacters(snapshot));
    fields += field("evidence", evidenceArray);
    fields += field("outcomes", outcomeObject);
    fields += field("truncated", Boolean.toString(truncated).length());
    fields += field("returnedCharacters", Integer.toString(Integer.MAX_VALUE).length());
    fields += field("estimatedTokens", Integer.toString(Integer.MAX_VALUE).length());
    return object(fields, 7);
  }

  static int reservedBatchCharacters(RepositoryQuery query) {
    java.util.EnumMap<RepositoryCapability, CapabilityOutcome> worstCase =
        new java.util.EnumMap<>(RepositoryCapability.class);
    String maximumReason = "R".repeat(CapabilityOutcome.MAX_REASON_CHARACTERS);
    query
        .capabilities()
        .forEach(
            capability ->
                worstCase.put(
                    capability,
                    new CapabilityOutcome(
                        capability,
                        RepositoryCapabilityState.PARTIAL,
                        maximumReason,
                        Integer.MAX_VALUE,
                        true)));
    return batchCharacters(
        query.fingerprint(), query.expectedSnapshot(), List.of(), worstCase, true);
  }

  private static int outcomeCharacters(CapabilityOutcome outcome) {
    int fields = 0;
    fields += field("capability", string(outcome.capability().name()));
    fields += field("state", string(outcome.state().name()));
    fields += field("reason", string(outcome.reason()));
    fields += field("hitCount", Integer.toString(outcome.hitCount()).length());
    fields += field("truncated", Boolean.toString(outcome.truncated()).length());
    return object(fields, 5);
  }

  private static int snapshotCharacters(RepositorySnapshotId snapshot) {
    int fields = 0;
    fields += field("projectId", string(snapshot.projectId()));
    fields += field("repositoryId", string(snapshot.repositoryId()));
    fields += field("commitSha", string(snapshot.commitSha()));
    fields += field("dirtyFingerprint", string(snapshot.dirtyFingerprint()));
    fields += field("inventoryFingerprint", string(snapshot.inventoryFingerprint()));
    fields += field("engine", string(snapshot.engine()));
    fields += field("schemaVersion", Integer.toString(snapshot.schemaVersion()).length());
    fields += field("capturedAt", string(Instant.from(snapshot.capturedAt()).toString()));
    fields += field("freshness", string(snapshot.freshness().name()));
    return object(fields, 9);
  }

  private static int object(int fieldCharacters, int fieldCount) {
    return 2 + fieldCharacters + Math.max(0, fieldCount - 1);
  }

  private static int field(String name, int valueCharacters) {
    return string(name) + 1 + valueCharacters;
  }

  private static int number(Integer value) {
    return value == null ? 4 : Integer.toString(value).length();
  }

  /** Counts the standard JSON representation, including quotes and required escaping. */
  private static int string(String value) {
    if (value == null) return 4;
    int characters = 2;
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current == '"'
          || current == '\\'
          || current == '\b'
          || current == '\f'
          || current == '\n'
          || current == '\r'
          || current == '\t') {
        characters += 2;
      } else if (current <= 0x1f) {
        characters += 6;
      } else {
        characters++;
      }
    }
    return characters;
  }
}
