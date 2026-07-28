package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.project.rag.CodeIndexStateStore;
import com.navneet.executespec.tool.RepositorySearchService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeRepositoryIntelligencePersistedEvidenceTest {

  @TempDir Path repository;

  @Test
  void routesPersistedCapabilitiesThroughTheSameSnapshotBudgetAndOutcomeBoundary()
      throws Exception {
    Files.writeString(repository.resolve("Widget.java"), "class Widget {}\n");
    RepositorySearchService searchService = new RepositorySearchService();
    RepositorySearchService.SearchResult observed =
        searchService.search(
            repository,
            new RepositorySearchService.SearchRequest(
                "Widget", RepositorySearchService.SearchMode.EXACT, true, null, null, 1, 0));
    UUID repositoryId = UUID.randomUUID();
    RepositorySnapshotId snapshot =
        new RepositorySnapshotId(
            "project-1",
            repositoryId.toString(),
            observed.repositorySha(),
            null,
            observed.inventoryFingerprint(),
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    RepositoryQuery query =
        new RepositoryQuery(
            "project-1",
            repositoryId.toString(),
            "Widget",
            Set.of(RepositoryCapability.CODEMAP, RepositoryCapability.ARCHITECTURE),
            null,
            null,
            snapshot,
            new QueryBudget(1, 10, 10, 20_000, 5_000, 5_000),
            false,
            new RepositoryAccessContext("actor-1", "org-1"));
    AuthorizedRepositoryHandle handle =
        new AuthorizedRepositoryHandle(
            "actor-1",
            "org-1",
            "project-1",
            repositoryId.toString(),
            snapshot.stableId(),
            snapshot.commitSha() == null ? "UNPINNED" : snapshot.commitSha(),
            repository.toRealPath());
    RepositoryArtifactEvidenceSource artifactSource = mock(RepositoryArtifactEvidenceSource.class);
    RepositoryEvidence evidence =
        new RepositoryEvidence(
            null,
            RepositoryEvidenceScope.REPOSITORY,
            RepositoryCapability.CODEMAP,
            RepositoryEvidenceKind.CODEMAP_SECTION,
            "codemaps/" + repositoryId + ".md",
            1,
            1,
            null,
            null,
            "Widget",
            null,
            RepositoryEvidenceProvenance.CODEMAP_PINNED,
            1.0,
            RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
            snapshot,
            query.fingerprint());
    when(artifactSource.query(eq(RepositoryCapability.CODEMAP), eq(query), eq(handle), eq(10)))
        .thenReturn(
            RepositoryArtifactEvidenceSource.Contribution.partial(
                List.of(evidence), "CODEMAP_INVENTORY_PARTIAL", true));
    NativeRepositoryIntelligenceAdapter adapter =
        new NativeRepositoryIntelligenceAdapter(
            searchService, mock(CodeIndexStateStore.class), ignored -> handle, artifactSource);

    RepositoryEvidenceBatch result = adapter.query(query);

    assertThat(result.evidence()).containsExactly(evidence);
    assertThat(result.outcomes().get(RepositoryCapability.CODEMAP))
        .extracting(
            CapabilityOutcome::state, CapabilityOutcome::reason, CapabilityOutcome::truncated)
        .containsExactly(RepositoryCapabilityState.PARTIAL, "CODEMAP_INVENTORY_PARTIAL", true);
    assertThat(result.outcomes().get(RepositoryCapability.ARCHITECTURE))
        .extracting(CapabilityOutcome::state, CapabilityOutcome::reason)
        .containsExactly(RepositoryCapabilityState.PARTIAL, "ADAPTER_CALL_BUDGET");
    verify(artifactSource).query(eq(RepositoryCapability.CODEMAP), eq(query), eq(handle), eq(10));
    verifyNoMoreInteractions(artifactSource);
  }
}
