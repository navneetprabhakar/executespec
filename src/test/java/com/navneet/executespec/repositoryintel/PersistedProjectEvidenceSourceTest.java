package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistedProjectEvidenceSourceTest {

  @TempDir Path repositoryRoot;

  private final ProjectCodeMapRepository codeMapRepository = mock(ProjectCodeMapRepository.class);
  private final RepoArchitectureDocRepository architectureRepository =
      mock(RepoArchitectureDocRepository.class);
  private final UUID repositoryId = UUID.randomUUID();
  private final RepositorySnapshotId snapshot =
      new RepositorySnapshotId(
          "project-1",
          repositoryId.toString(),
          "abc123",
          null,
          "inventory",
          "native",
          1,
          Instant.EPOCH,
          RepositoryFreshness.CURRENT);

  private PersistedProjectEvidenceSource source;

  @BeforeEach
  void setUp() {
    source = new PersistedProjectEvidenceSource(codeMapRepository, architectureRepository);
  }

  @Test
  void returnsOnlyPinnedDeterministicCodemapLinesAndReportsSourcePartiality() {
    ProjectCodeMap codeMap =
        codeMap(
            "# repo\n> **Inventory: PARTIAL** — truncated\n- `src/Widget.java`\n"
                + "**Purpose:** Widget model-generated purpose\n- `Widget`\n");
    when(codeMapRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(codeMap));

    RepositoryArtifactEvidenceSource.Contribution result =
        source.query(RepositoryCapability.CODEMAP, query("Widget"), handle(), 10);

    assertThat(result.state()).isEqualTo(RepositoryCapabilityState.PARTIAL);
    assertThat(result.reason()).isEqualTo("CODEMAP_INVENTORY_PARTIAL");
    assertThat(result.evidence()).hasSize(2);
    assertThat(result.evidence())
        .allMatch(hit -> hit.provenance() == RepositoryEvidenceProvenance.CODEMAP_PINNED)
        .allMatch(hit -> hit.scope() == RepositoryEvidenceScope.REPOSITORY)
        .noneMatch(hit -> hit.excerpt().startsWith("**Purpose:**"));
  }

  @Test
  void rejectsCodemapWhosePersistedSourceRevisionDoesNotMatchTheAuthorizedHandle() {
    ProjectCodeMap stale = codeMap("- `src/Widget.java`\n");
    stale.setGeneratedFromSha("old-sha");
    when(codeMapRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(stale));

    RepositoryArtifactEvidenceSource.Contribution result =
        source.query(RepositoryCapability.CODEMAP, query("Widget"), handle(), 10);

    assertThat(result.evidence()).isEmpty();
    assertThat(result.state()).isEqualTo(RepositoryCapabilityState.STALE_REJECTED);
    assertThat(result.reason()).isEqualTo("CODEMAP_SOURCE_SHA_MISMATCH");
  }

  @Test
  void returnsModelDerivedArchitectureOnlyWhenItsExactCodemapAndShaAreCurrent() {
    ProjectCodeMap codeMap = codeMap("- `src/Widget.java`\n");
    RepoArchitectureDoc architecture = architecture(codeMap, "# Architecture\nWidget service\n");
    when(codeMapRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(codeMap));
    when(architectureRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(architecture));

    RepositoryArtifactEvidenceSource.Contribution result =
        source.query(RepositoryCapability.ARCHITECTURE, query("Widget"), handle(), 10);

    assertThat(result.state()).isEqualTo(RepositoryCapabilityState.AVAILABLE_HIT);
    assertThat(result.evidence())
        .singleElement()
        .satisfies(
            hit -> {
              assertThat(hit.scope()).isEqualTo(RepositoryEvidenceScope.PROJECT_ARCHITECTURE);
              assertThat(hit.provenance())
                  .isEqualTo(RepositoryEvidenceProvenance.ARCHITECTURE_MODEL_DERIVED);
              assertThat(hit.confidence()).isEqualTo(0.65);
              assertThat(hit.ambiguity()).isEqualTo(RepositoryEvidenceAmbiguity.UNKNOWN);
            });
  }

  @Test
  void rejectsWrongCodemapLinkAndFallbackArchitecture() {
    ProjectCodeMap codeMap = codeMap("- `src/Widget.java`\n");
    RepoArchitectureDoc wrongLink = architecture(codeMap, "Widget service");
    wrongLink.setSourceCodeMapId(UUID.randomUUID());
    when(codeMapRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(codeMap));
    when(architectureRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(wrongLink));

    assertThat(
            source.query(RepositoryCapability.ARCHITECTURE, query("Widget"), handle(), 10).reason())
        .isEqualTo("REPO_ARCHITECTURE_CODEMAP_MISMATCH");

    RepoArchitectureDoc fallback = architecture(codeMap, "Widget service");
    fallback.setModelUsed("fallback");
    when(architectureRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(fallback));
    assertThat(
            source.query(RepositoryCapability.ARCHITECTURE, query("Widget"), handle(), 10).reason())
        .isEqualTo("REPO_ARCHITECTURE_NOT_VERIFIED");
  }

  @Test
  void boundedExtractionReportsMoreResultsAndRevalidatesHandleAfterTheRead() throws Exception {
    ProjectCodeMap codeMap = codeMap("Widget one\nWidget two\nWidget three\n");
    when(codeMapRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenAnswer(
            ignored -> {
              Files.delete(repositoryRoot);
              return Optional.of(codeMap);
            });

    assertThatThrownBy(
            () -> source.query(RepositoryCapability.CODEMAP, query("Widget"), handle(), 1))
        .isInstanceOf(RepositoryAuthorityException.class);
  }

  @Test
  void boundedExtractionDoesNotPresentTheFirstPageAsComplete() {
    ProjectCodeMap codeMap = codeMap("Widget one\nWidget two\nWidget three\n");
    when(codeMapRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            "project-1", repositoryId))
        .thenReturn(Optional.of(codeMap));

    RepositoryArtifactEvidenceSource.Contribution result =
        source.query(RepositoryCapability.CODEMAP, query("Widget"), handle(), 1);

    assertThat(result.evidence()).hasSize(1);
    assertThat(result.state()).isEqualTo(RepositoryCapabilityState.PARTIAL);
    assertThat(result.reason()).isEqualTo("RESULT_LIMIT");
    assertThat(result.hasMore()).isTrue();
  }

  private ProjectCodeMap codeMap(String content) {
    return ProjectCodeMap.builder()
        .id(UUID.randomUUID())
        .projectId("project-1")
        .repoId(repositoryId)
        .version(3)
        .generatedFromSha("abc123")
        .content(content)
        .build();
  }

  private RepoArchitectureDoc architecture(ProjectCodeMap codeMap, String content) {
    return RepoArchitectureDoc.builder()
        .id(UUID.randomUUID())
        .projectId("project-1")
        .repoId(repositoryId)
        .version(2)
        .sourceCodeMapId(codeMap.getId())
        .generatedFromSha("abc123")
        .modelUsed("model")
        .content(content)
        .build();
  }

  private RepositoryQuery query(String text) {
    return new RepositoryQuery(
        "project-1",
        repositoryId.toString(),
        text,
        Set.of(RepositoryCapability.CODEMAP, RepositoryCapability.ARCHITECTURE),
        null,
        null,
        snapshot,
        QueryBudget.DEFAULT,
        false,
        new RepositoryAccessContext("actor-1", "org-1"));
  }

  private AuthorizedRepositoryHandle handle() {
    try {
      return new AuthorizedRepositoryHandle(
          "actor-1",
          "org-1",
          "project-1",
          repositoryId.toString(),
          snapshot.stableId(),
          "abc123",
          repositoryRoot.toRealPath());
    } catch (java.io.IOException unavailable) {
      throw new AssertionError("test repository is unavailable", unavailable);
    }
  }
}
