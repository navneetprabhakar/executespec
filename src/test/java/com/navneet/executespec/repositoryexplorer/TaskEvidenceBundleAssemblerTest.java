package com.navneet.executespec.repositoryexplorer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

import com.navneet.executespec.repositoryintel.CapabilityOutcome;
import com.navneet.executespec.repositoryintel.QueryBudget;
import com.navneet.executespec.repositoryintel.RepositoryAccessContext;
import com.navneet.executespec.repositoryintel.RepositoryCapability;
import com.navneet.executespec.repositoryintel.RepositoryCapabilityState;
import com.navneet.executespec.repositoryintel.RepositoryEvidence;
import com.navneet.executespec.repositoryintel.RepositoryEvidenceAmbiguity;
import com.navneet.executespec.repositoryintel.RepositoryEvidenceBatch;
import com.navneet.executespec.repositoryintel.RepositoryEvidenceKind;
import com.navneet.executespec.repositoryintel.RepositoryEvidenceProvenance;
import com.navneet.executespec.repositoryintel.RepositoryEvidenceScope;
import com.navneet.executespec.repositoryintel.RepositoryFreshness;
import com.navneet.executespec.repositoryintel.RepositoryQuery;
import com.navneet.executespec.repositoryintel.RepositorySnapshotId;

import org.junit.jupiter.api.Test;

class TaskEvidenceBundleAssemblerTest {

  private final TaskEvidenceBundleAssembler assembler = new TaskEvidenceBundleAssembler();

  @Test
  void verifiedSelectionIsSnapshotBoundOrderedAndStable() {
    BatchFixture fixture =
        batch(
            List.of(
                new EvidenceSeed("src/Z.java", 20, RepositoryEvidenceProvenance.HEURISTIC_SYMBOL),
                new EvidenceSeed("src/A.java", 2, RepositoryEvidenceProvenance.EXACT)));
    RepositoryEvidence first = fixture.evidence().get(1);
    RepositoryEvidence second = fixture.evidence().get(0);
    RepositoryEvidenceBatch batch = fixture.batch();

    TaskEvidenceBundle one =
        assemble(
            batch,
            new ExplorerSelection(
                ExplorerSelection.Status.VERIFIED,
                ExplorerExperimentArm.SELECTIVE,
                List.of(second.evidenceId(), first.evidenceId()),
                List.of("candidate z", "candidate a"),
                List.of("symbol", "exact"),
                List.of("native"),
                null));
    TaskEvidenceBundle two =
        assemble(
            batch,
            new ExplorerSelection(
                ExplorerSelection.Status.VERIFIED,
                ExplorerExperimentArm.SELECTIVE,
                List.of(first.evidenceId(), second.evidenceId()),
                List.of("candidate a", "candidate z"),
                List.of("exact", "symbol"),
                List.of("native"),
                null));

    assertThat(one.citations()).containsExactly(first, second);
    assertThat(one.source()).isEqualTo(TaskEvidenceBundle.Source.EXPLORER_VERIFIED);
    assertThat(one.stableFingerprint()).isEqualTo(two.stableFingerprint()).hasSize(64);
    assertThat(one.totalCharacters())
        .isLessThanOrEqualTo(TaskEvidenceBudget.DEFAULT.maxCharacters());
    assertThat(one.estimatedTokens())
        .isLessThanOrEqualTo(TaskEvidenceBudget.DEFAULT.maxEstimatedTokens());
  }

  @Test
  void inventedCitationFallsBackToDeterministicEvidenceAndDropsModelDerivedRows() {
    BatchFixture fixture =
        batch(
            List.of(
                new EvidenceSeed(
                    "architecture.md", 1, RepositoryEvidenceProvenance.ARCHITECTURE_MODEL_DERIVED),
                new EvidenceSeed("src/App.java", 3, RepositoryEvidenceProvenance.EXACT)));
    RepositoryEvidence deterministic = fixture.evidence().get(1);
    RepositoryEvidenceBatch batch = fixture.batch();

    TaskEvidenceBundle bundle =
        assemble(
            batch,
            new ExplorerSelection(
                ExplorerSelection.Status.VERIFIED,
                ExplorerExperimentArm.SELECTIVE,
                List.of("invented-evidence-id"),
                List.of(),
                List.of("model scout"),
                List.of("native"),
                null));

    assertThat(bundle.source()).isEqualTo(TaskEvidenceBundle.Source.DETERMINISTIC_FALLBACK);
    assertThat(bundle.fallbackReason()).isEqualTo("INVALID_OR_STALE_CITATION");
    assertThat(bundle.citations()).containsExactly(deterministic);
  }

  @Test
  void timeoutProviderFailureAndBudgetFailureNeverEraseDeterministicFallback() {
    BatchFixture fixture =
        batch(List.of(new EvidenceSeed("src/App.java", 3, RepositoryEvidenceProvenance.EXACT)));
    RepositoryEvidence deterministic = fixture.evidence().get(0);
    RepositoryEvidenceBatch batch = fixture.batch();

    for (ExplorerSelection.Status status :
        List.of(
            ExplorerSelection.Status.FAILED,
            ExplorerSelection.Status.TIMED_OUT,
            ExplorerSelection.Status.BUDGET_EXHAUSTED,
            ExplorerSelection.Status.EMPTY)) {
      TaskEvidenceBundle bundle =
          assemble(
              batch,
              new ExplorerSelection(
                  status,
                  ExplorerExperimentArm.SELECTIVE,
                  List.of(),
                  List.of("untrusted rejected candidate"),
                  List.of("untrusted search strategy"),
                  List.of("untrusted engine"),
                  "optional explorer unavailable"));
      assertThat(bundle.citations()).containsExactly(deterministic);
      assertThat(bundle.source()).isEqualTo(TaskEvidenceBundle.Source.DETERMINISTIC_FALLBACK);
      assertThat(bundle.insufficientEvidence()).isFalse();
      assertThat(bundle.rejectedCandidateSummaries()).isEmpty();
      assertThat(bundle.searchStrategy()).isEmpty();
      assertThat(bundle.enginesConsulted()).isEmpty();
    }
  }

  @Test
  void enforcesIndependentCitationAndSizeCaps() {
    BatchFixture fixture =
        batch(
            List.of(
                new EvidenceSeed("src/A.java", 1, RepositoryEvidenceProvenance.EXACT),
                new EvidenceSeed("src/B.java", 1, RepositoryEvidenceProvenance.EXACT)));
    RepositoryEvidence first = fixture.evidence().get(0);
    RepositoryEvidence second = fixture.evidence().get(1);
    RepositoryEvidenceBatch batch = fixture.batch();

    TaskEvidenceBundle bundle =
        assembler.assemble(
            "task-1",
            List.of("REQ-1"),
            "Fix the request",
            "Where is the request handled?",
            batch,
            new ExplorerSelection(
                ExplorerSelection.Status.VERIFIED,
                ExplorerExperimentArm.SELECTIVE,
                List.of(first.evidenceId(), second.evidenceId()),
                List.of(),
                List.of("exact"),
                List.of("native"),
                null),
            new TaskEvidenceBudget(1, 12_000, 3_000, 4));

    assertThat(bundle.citations()).hasSize(1);
    assertThat(bundle.truncated()).isTrue();
  }

  @Test
  void bundleSchemaCannotCarryRawExplorerHistory() {
    assertThat(
            Arrays.stream(TaskEvidenceBundle.class.getRecordComponents())
                .map(RecordComponent::getName))
        .noneMatch(
            name ->
                name.toLowerCase().contains("transcript")
                    || name.toLowerCase().contains("message")
                    || name.toLowerCase().contains("history")
                    || name.toLowerCase().contains("reasoning"));
    assertThat(
            Arrays.stream(ExplorerSelection.class.getRecordComponents())
                .map(RecordComponent::getName))
        .noneMatch(
            name ->
                name.toLowerCase().contains("transcript")
                    || name.toLowerCase().contains("message")
                    || name.toLowerCase().contains("history")
                    || name.toLowerCase().contains("reasoning"));
  }

  private TaskEvidenceBundle assemble(RepositoryEvidenceBatch batch, ExplorerSelection selection) {
    return assembler.assemble(
        "task-1",
        List.of("REQ-2", "REQ-1"),
        "Fix the request",
        "Where is the request handled?",
        batch,
        selection,
        TaskEvidenceBudget.DEFAULT);
  }

  private static BatchFixture batch(List<EvidenceSeed> seeds) {
    try {
      RepositorySnapshotId snapshot = snapshot();
      var constructor =
          RepositoryAccessContext.class.getDeclaredConstructor(String.class, String.class);
      constructor.setAccessible(true);
      RepositoryAccessContext accessContext = constructor.newInstance("actor-1", "org-1");
      Set<RepositoryCapability> capabilities =
          seeds.stream()
              .map(TaskEvidenceBundleAssemblerTest::capability)
              .collect(java.util.stream.Collectors.toSet());
      RepositoryQuery query =
          new RepositoryQuery(
              "project-1",
              "repo-1",
              "localize request",
              capabilities,
              null,
              null,
              snapshot,
              QueryBudget.DEFAULT,
              false,
              accessContext);
      List<RepositoryEvidence> evidence =
          seeds.stream()
              .map(
                  seed ->
                      evidence(
                          snapshot,
                          query.fingerprint(),
                          seed.path(),
                          seed.line(),
                          seed.provenance()))
              .toList();
      EnumMap<RepositoryCapability, CapabilityOutcome> outcomes =
          new EnumMap<>(RepositoryCapability.class);
      for (RepositoryCapability capability : capabilities) {
        int hits = (int) evidence.stream().filter(item -> item.capability() == capability).count();
        outcomes.put(
            capability,
            new CapabilityOutcome(
                capability, RepositoryCapabilityState.AVAILABLE_HIT, null, hits, false));
      }
      return new BatchFixture(
          RepositoryEvidenceBatch.of(query, evidence, outcomes, false), evidence);
    } catch (ReflectiveOperationException reflectionFailure) {
      throw new AssertionError(
          "could not construct server-derived access test fixture", reflectionFailure);
    }
  }

  private static RepositoryEvidence evidence(
      RepositorySnapshotId snapshot,
      String queryFingerprint,
      String path,
      int line,
      RepositoryEvidenceProvenance provenance) {
    RepositoryCapability capability = capability(new EvidenceSeed(path, line, provenance));
    RepositoryEvidenceKind kind =
        capability == RepositoryCapability.ARCHITECTURE
            ? RepositoryEvidenceKind.ARCHITECTURE_SECTION
            : RepositoryEvidenceKind.TEXT_MATCH;
    return new RepositoryEvidence(
        null,
        RepositoryEvidenceScope.REPOSITORY,
        capability,
        kind,
        path,
        line,
        line,
        null,
        null,
        "relevant source line",
        null,
        provenance,
        provenance.deterministic() ? 1.0 : 0.7,
        RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
        snapshot,
        queryFingerprint);
  }

  private static RepositorySnapshotId snapshot() {
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

  private static RepositoryCapability capability(EvidenceSeed seed) {
    return seed.provenance() == RepositoryEvidenceProvenance.ARCHITECTURE_MODEL_DERIVED
        ? RepositoryCapability.ARCHITECTURE
        : RepositoryCapability.EXACT_TEXT;
  }

  private record EvidenceSeed(String path, int line, RepositoryEvidenceProvenance provenance) {}

  private record BatchFixture(RepositoryEvidenceBatch batch, List<RepositoryEvidence> evidence) {}
}
