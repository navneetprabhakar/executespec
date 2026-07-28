package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RepositoryQueryContractTest {

  @Test
  void fingerprintIsStableAcrossCapabilityOrderAndObservationTime() {
    RepositorySnapshotId first = snapshot(Instant.parse("2026-07-28T00:00:00Z"));
    RepositorySnapshotId later = snapshot(Instant.parse("2026-07-28T01:00:00Z"));

    RepositoryQuery left =
        query(
            new LinkedHashSet<>(
                List.of(RepositoryCapability.SYMBOL, RepositoryCapability.EXACT_TEXT)),
            first);
    RepositoryQuery right =
        query(
            new LinkedHashSet<>(
                List.of(RepositoryCapability.EXACT_TEXT, RepositoryCapability.SYMBOL)),
            later);

    assertThat(first.stableId()).isEqualTo(later.stableId());
    assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
  }

  @Test
  void fingerprintChangesWhenBudgetOrProviderAuthorityChanges() {
    RepositorySnapshotId snapshot = snapshot(Instant.EPOCH);
    RepositoryQuery baseline = query(Set.of(RepositoryCapability.EXACT_TEXT), snapshot);
    RepositoryQuery differentBudget =
        new RepositoryQuery(
            "project-1",
            "repo-1",
            "find Widget",
            Set.of(RepositoryCapability.EXACT_TEXT),
            "src",
            "*.java",
            snapshot,
            new QueryBudget(2, 10, 20, 2_000, 500, 1_000),
            false);
    RepositoryQuery providerAllowed =
        new RepositoryQuery(
            "project-1",
            "repo-1",
            "find Widget",
            Set.of(RepositoryCapability.EXACT_TEXT),
            "src",
            "*.java",
            snapshot,
            QueryBudget.DEFAULT,
            true);

    assertThat(differentBudget.fingerprint()).isNotEqualTo(baseline.fingerprint());
    assertThat(providerAllowed.fingerprint()).isNotEqualTo(baseline.fingerprint());
  }

  @Test
  void rejectsRepositoryEscapeAndMismatchedSnapshot() {
    RepositorySnapshotId snapshot = snapshot(Instant.EPOCH);

    assertThatThrownBy(
            () ->
                new RepositoryQuery(
                    "project-1",
                    "repo-1",
                    "query",
                    Set.of(RepositoryCapability.EXACT_TEXT),
                    "../outside",
                    null,
                    snapshot,
                    QueryBudget.DEFAULT,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("within the repository");

    RepositorySnapshotId other =
        new RepositorySnapshotId(
            "project-1",
            "repo-2",
            "abc123",
            null,
            null,
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    assertThatThrownBy(
            () ->
                new RepositoryQuery(
                    "project-1",
                    "repo-1",
                    "query",
                    Set.of(RepositoryCapability.EXACT_TEXT),
                    null,
                    null,
                    other,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must match");
  }

  @Test
  void rejectsMissingOrUnexpectedProjectBinding() {
    RepositorySnapshotId projectSnapshot = snapshot(Instant.EPOCH);
    RepositorySnapshotId unscopedSnapshot =
        new RepositorySnapshotId(
            null,
            "repo-1",
            "abc123",
            null,
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);

    assertThatThrownBy(
            () ->
                new RepositoryQuery(
                    null,
                    "repo-1",
                    "query",
                    Set.of(RepositoryCapability.EXACT_TEXT),
                    null,
                    null,
                    projectSnapshot,
                    QueryBudget.DEFAULT,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("projectId must match");
    assertThatThrownBy(
            () ->
                new RepositoryQuery(
                    "project-1",
                    "repo-1",
                    "query",
                    Set.of(RepositoryCapability.EXACT_TEXT),
                    null,
                    null,
                    unscopedSnapshot,
                    QueryBudget.DEFAULT,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("projectId must match");
  }

  @Test
  void currentSnapshotRequiresConcreteSourceIdentity() {
    assertThatThrownBy(
            () ->
                new RepositorySnapshotId(
                    null,
                    "repo-1",
                    null,
                    null,
                    null,
                    "native",
                    1,
                    Instant.EPOCH,
                    RepositoryFreshness.CURRENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commitSha or inventoryFingerprint");
  }

  @Test
  void budgetRejectsUnboundedOrContradictoryValues() {
    assertThatThrownBy(() -> new QueryBudget(1, 0, 10, 100, 25, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxHitsPerCapability");
    assertThatThrownBy(() -> new QueryBudget(1, 11, 10, 100, 25, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot exceed");
  }

  private static RepositoryQuery query(
      Set<RepositoryCapability> capabilities, RepositorySnapshotId snapshot) {
    return new RepositoryQuery(
        "project-1",
        "repo-1",
        "find Widget",
        capabilities,
        "src",
        "*.java",
        snapshot,
        QueryBudget.DEFAULT,
        false);
  }

  private static RepositorySnapshotId snapshot(Instant capturedAt) {
    return new RepositorySnapshotId(
        "project-1",
        "repo-1",
        "ABC123",
        "DIRTY456",
        "INVENTORY789",
        "Native",
        1,
        capturedAt,
        RepositoryFreshness.CURRENT);
  }
}
