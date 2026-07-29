package com.navneet.executespec.repositoryintel;

import java.util.List;

/** Provider-free persisted-artifact reader used behind the repository-intelligence port. */
interface RepositoryArtifactEvidenceSource {

  Contribution query(
      RepositoryCapability capability,
      RepositoryQuery query,
      AuthorizedRepositoryHandle handle,
      int maxHits);

  static RepositoryArtifactEvidenceSource unavailable() {
    return (capability, query, handle, maxHits) ->
        Contribution.unavailable("CAPABILITY_NOT_SUPPORTED_BY_NATIVE_ADAPTER");
  }

  record Contribution(
      List<RepositoryEvidence> evidence,
      RepositoryCapabilityState state,
      String reason,
      boolean hasMore) {

    public Contribution {
      evidence = List.copyOf(evidence == null ? List.of() : evidence);
      if (state == null) throw new IllegalArgumentException("state is required");
      reason = normalize(reason);
      if (requiresReason(state) && reason == null) {
        throw new IllegalArgumentException(state + " requires a reason");
      }
      if (state != RepositoryCapabilityState.AVAILABLE_HIT
          && state != RepositoryCapabilityState.PARTIAL
          && !evidence.isEmpty()) {
        throw new IllegalArgumentException(state + " cannot return evidence");
      }
      if (state == RepositoryCapabilityState.AVAILABLE_HIT && evidence.isEmpty()) {
        throw new IllegalArgumentException("AVAILABLE_HIT requires evidence");
      }
      if (hasMore && state != RepositoryCapabilityState.PARTIAL) {
        throw new IllegalArgumentException("hasMore requires PARTIAL state");
      }
    }

    static Contribution available(List<RepositoryEvidence> evidence) {
      return evidence == null || evidence.isEmpty()
          ? new Contribution(List.of(), RepositoryCapabilityState.AVAILABLE_NO_MATCH, null, false)
          : new Contribution(evidence, RepositoryCapabilityState.AVAILABLE_HIT, null, false);
    }

    static Contribution partial(List<RepositoryEvidence> evidence, String reason, boolean hasMore) {
      return new Contribution(evidence, RepositoryCapabilityState.PARTIAL, reason, hasMore);
    }

    static Contribution unavailable(String reason) {
      return new Contribution(List.of(), RepositoryCapabilityState.UNAVAILABLE, reason, false);
    }

    static Contribution stale(String reason) {
      return new Contribution(List.of(), RepositoryCapabilityState.STALE_REJECTED, reason, false);
    }

    CapabilityOutcome outcome(
        RepositoryCapability capability, int accepted, boolean resultTruncated) {
      if (resultTruncated) {
        return new CapabilityOutcome(
            capability,
            RepositoryCapabilityState.PARTIAL,
            state == RepositoryCapabilityState.PARTIAL ? reason : "RESULT_BUDGET",
            accepted,
            true);
      }
      if (state == RepositoryCapabilityState.AVAILABLE_HIT
          || state == RepositoryCapabilityState.AVAILABLE_NO_MATCH) {
        return new CapabilityOutcome(
            capability,
            accepted == 0
                ? RepositoryCapabilityState.AVAILABLE_NO_MATCH
                : RepositoryCapabilityState.AVAILABLE_HIT,
            null,
            accepted,
            false);
      }
      return new CapabilityOutcome(capability, state, reason, accepted, false);
    }

    private static boolean requiresReason(RepositoryCapabilityState state) {
      return state == RepositoryCapabilityState.UNAVAILABLE
          || state == RepositoryCapabilityState.STALE_REJECTED
          || state == RepositoryCapabilityState.PARTIAL
          || state == RepositoryCapabilityState.FAILED;
    }

    private static String normalize(String value) {
      if (value == null) return null;
      String cleaned = value.strip().replaceAll("\\s+", " ");
      if (cleaned.isEmpty()) return null;
      return cleaned.substring(
          0, Math.min(cleaned.length(), CapabilityOutcome.MAX_REASON_CHARACTERS));
    }
  }
}
