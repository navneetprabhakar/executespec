package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class RepositoryEvidenceContractTest {

  @Test
  void derivesStableIdDigestAndSizeWithoutCallerSuppliedClaims() {
    RepositorySnapshotId snapshot = currentSnapshot();
    RepositoryEvidence first = evidence(null, snapshot, "query-fingerprint", "src/App.java", 7);
    RepositoryEvidence second = evidence(null, snapshot, "query-fingerprint", "src/App.java", 7);

    assertThat(first.evidenceId()).isEqualTo(second.evidenceId()).hasSize(64);
    assertThat(first.contentDigest()).hasSize(64);
    assertThat(first.characterCount()).isGreaterThan("class Widget {}".length());
    assertThat(first.estimatedTokens()).isEqualTo((first.characterCount() + 3) / 4);
  }

  @Test
  void rejectsForgedIdInvalidRangeAndEscapingPath() {
    RepositorySnapshotId snapshot = currentSnapshot();

    assertThatThrownBy(
            () -> evidence("caller-controlled", snapshot, "query-fingerprint", "src/App.java", 7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(() -> evidence(null, snapshot, "query-fingerprint", "../secret", 7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("within the evidence scope");
    assertThatThrownBy(
            () ->
                new RepositoryEvidence(
                    null,
                    RepositoryEvidenceScope.REPOSITORY,
                    RepositoryCapability.EXACT_TEXT,
                    RepositoryEvidenceKind.TEXT_MATCH,
                    "src/App.java",
                    8,
                    7,
                    null,
                    null,
                    "match",
                    null,
                    RepositoryEvidenceProvenance.EXACT,
                    1.0,
                    RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
                    snapshot,
                    "query-fingerprint"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ordered");
  }

  @Test
  void onlyCurrentSnapshotCanProduceEvidence() {
    RepositorySnapshotId stale =
        new RepositorySnapshotId(
            "project-1",
            "repo-1",
            "abc123",
            null,
            null,
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.STALE);
    RepositorySnapshotId unknown =
        new RepositorySnapshotId(
            "project-1",
            "repo-1",
            null,
            null,
            null,
            "adr-cache",
            1,
            Instant.EPOCH,
            RepositoryFreshness.UNKNOWN);
    RepositorySnapshotId notApplicable =
        new RepositorySnapshotId(
            "project-1",
            "repo-1",
            null,
            null,
            null,
            "adr-cache",
            1,
            Instant.EPOCH,
            RepositoryFreshness.NOT_APPLICABLE);

    assertThatThrownBy(() -> evidence(null, stale, "query-fingerprint", "src/App.java", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CURRENT snapshots");
    assertThatThrownBy(() -> evidence(null, unknown, "query-fingerprint", "adr/0001.md", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CURRENT snapshots");
    assertThatThrownBy(() -> evidence(null, notApplicable, "query-fingerprint", "adr/0001.md", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CURRENT snapshots");
  }

  @Test
  void provenanceDoesNotOverclaimModelOrHeuristicEvidence() {
    assertThat(RepositoryEvidenceProvenance.EXACT.deterministic()).isTrue();
    assertThat(RepositoryEvidenceProvenance.HEURISTIC_RELATIONSHIP.deterministic()).isTrue();
    assertThat(RepositoryEvidenceProvenance.HEURISTIC_RELATIONSHIP.modelDerived()).isFalse();
    assertThat(RepositoryEvidenceProvenance.ARCHITECTURE_MODEL_DERIVED.deterministic()).isFalse();
    assertThat(RepositoryEvidenceProvenance.ARCHITECTURE_MODEL_DERIVED.modelDerived()).isTrue();
    assertThat(RepositoryEvidenceProvenance.ADR_UNVERIFIED.deterministic()).isFalse();
  }

  private static RepositoryEvidence evidence(
      String evidenceId,
      RepositorySnapshotId snapshot,
      String queryFingerprint,
      String path,
      int line) {
    return new RepositoryEvidence(
        evidenceId,
        RepositoryEvidenceScope.REPOSITORY,
        RepositoryCapability.AST_DECLARATION,
        RepositoryEvidenceKind.DECLARATION,
        path,
        line,
        line,
        "Widget",
        null,
        "class Widget {}",
        null,
        RepositoryEvidenceProvenance.AST_EXTRACTED,
        1.0,
        RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
        snapshot,
        queryFingerprint);
  }

  private static RepositorySnapshotId currentSnapshot() {
    return new RepositorySnapshotId(
        "project-1",
        "repo-1",
        "abc123",
        null,
        "inventory",
        "native",
        1,
        Instant.EPOCH,
        RepositoryFreshness.CURRENT);
  }
}
