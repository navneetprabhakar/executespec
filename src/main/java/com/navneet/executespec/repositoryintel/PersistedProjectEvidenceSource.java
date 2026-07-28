package com.navneet.executespec.repositoryintel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository;

import org.springframework.stereotype.Component;

/**
 * Read-only adapter over already-persisted codemaps and per-repository architecture documents. It
 * never generates, refreshes, indexes, embeds, or calls a provider.
 */
@Component
final class PersistedProjectEvidenceSource implements RepositoryArtifactEvidenceSource {

  private static final int MAX_EXCERPT_CHARACTERS = 1_200;
  private static final String PARTIAL_INVENTORY_MARKER = "> **Inventory: PARTIAL**";

  private final ProjectCodeMapRepository codeMapRepository;
  private final RepoArchitectureDocRepository architectureRepository;

  PersistedProjectEvidenceSource(
      ProjectCodeMapRepository codeMapRepository,
      RepoArchitectureDocRepository architectureRepository) {
    if (codeMapRepository == null) {
      throw new IllegalArgumentException("codeMapRepository is required");
    }
    if (architectureRepository == null) {
      throw new IllegalArgumentException("architectureRepository is required");
    }
    this.codeMapRepository = codeMapRepository;
    this.architectureRepository = architectureRepository;
  }

  @Override
  public Contribution query(
      RepositoryCapability capability,
      RepositoryQuery query,
      AuthorizedRepositoryHandle handle,
      int maxHits) {
    if (capability == null || query == null || handle == null) {
      throw new IllegalArgumentException("capability, query, and handle are required");
    }
    if (maxHits <= 0) throw new IllegalArgumentException("maxHits must be positive");
    if (capability != RepositoryCapability.CODEMAP
        && capability != RepositoryCapability.ARCHITECTURE) {
      return Contribution.unavailable("PERSISTED_ARTIFACT_CAPABILITY_UNSUPPORTED");
    }
    if (query.projectId() == null) return Contribution.unavailable("PROJECT_ID_REQUIRED");
    UUID repositoryId = parseRepositoryId(query.repositoryId());
    if (repositoryId == null) return Contribution.unavailable("UUID_REPOSITORY_ID_REQUIRED");

    handle.requireMatches(query);
    try {
      ProjectCodeMap codeMap = latestCodeMap(query, repositoryId).orElse(null);
      if (codeMap == null) return Contribution.unavailable("CODEMAP_NOT_BUILT");
      Contribution codeMapFreshness = validateCodeMap(codeMap, query, handle, repositoryId);
      if (codeMapFreshness != null) return codeMapFreshness;

      return capability == RepositoryCapability.CODEMAP
          ? codemapEvidence(query, codeMap, repositoryId, maxHits)
          : architectureEvidence(query, handle, codeMap, repositoryId, maxHits);
    } finally {
      handle.requireMatches(query);
    }
  }

  private Optional<ProjectCodeMap> latestCodeMap(RepositoryQuery query, UUID repositoryId) {
    return codeMapRepository.findFirstByProjectIdAndRepoIdOrderByVersionDesc(
        query.projectId(), repositoryId);
  }

  private Contribution validateCodeMap(
      ProjectCodeMap codeMap,
      RepositoryQuery query,
      AuthorizedRepositoryHandle handle,
      UUID repositoryId) {
    if (!query.projectId().equals(codeMap.getProjectId())
        || !repositoryId.equals(codeMap.getRepoId())) {
      return Contribution.unavailable("CODEMAP_IDENTITY_MISMATCH");
    }
    if (codeMap.getGeneratedFromSha() == null || codeMap.getGeneratedFromSha().isBlank()) {
      return Contribution.unavailable("CODEMAP_SOURCE_SHA_MISSING");
    }
    if (!codeMap.getGeneratedFromSha().equalsIgnoreCase(handle.sourceRevision())) {
      return Contribution.stale("CODEMAP_SOURCE_SHA_MISMATCH");
    }
    if (query.expectedSnapshot().commitSha() != null
        && !codeMap.getGeneratedFromSha().equalsIgnoreCase(query.expectedSnapshot().commitSha())) {
      return Contribution.stale("CODEMAP_SNAPSHOT_SHA_MISMATCH");
    }
    if (codeMap.getContent() == null || codeMap.getContent().isBlank()) {
      return Contribution.unavailable("CODEMAP_CONTENT_EMPTY");
    }
    return null;
  }

  private Contribution codemapEvidence(
      RepositoryQuery query, ProjectCodeMap codeMap, UUID repositoryId, int maxHits) {
    Extraction extraction =
        extract(
            query,
            RepositoryCapability.CODEMAP,
            RepositoryEvidenceScope.REPOSITORY,
            RepositoryEvidenceKind.CODEMAP_SECTION,
            RepositoryEvidenceProvenance.CODEMAP_PINNED,
            "codemaps/" + repositoryId + ".md",
            codeMap.getContent(),
            1.0,
            RepositoryEvidenceAmbiguity.UNAMBIGUOUS,
            maxHits,
            true);
    boolean partialInventory = codeMap.getContent().contains(PARTIAL_INVENTORY_MARKER);
    return partialInventory
        ? Contribution.partial(
            extraction.evidence(), "CODEMAP_INVENTORY_PARTIAL", extraction.hasMore())
        : extraction.hasMore()
            ? Contribution.partial(extraction.evidence(), "RESULT_LIMIT", true)
            : Contribution.available(extraction.evidence());
  }

  private Contribution architectureEvidence(
      RepositoryQuery query,
      AuthorizedRepositoryHandle handle,
      ProjectCodeMap codeMap,
      UUID repositoryId,
      int maxHits) {
    RepoArchitectureDoc architecture =
        architectureRepository
            .findFirstByProjectIdAndRepoIdOrderByVersionDesc(query.projectId(), repositoryId)
            .orElse(null);
    if (architecture == null) return Contribution.unavailable("REPO_ARCHITECTURE_NOT_BUILT");
    if (!query.projectId().equals(architecture.getProjectId())
        || !repositoryId.equals(architecture.getRepoId())) {
      return Contribution.unavailable("REPO_ARCHITECTURE_IDENTITY_MISMATCH");
    }
    if (architecture.getGeneratedFromSha() == null
        || architecture.getGeneratedFromSha().isBlank()) {
      return Contribution.unavailable("REPO_ARCHITECTURE_SOURCE_SHA_MISSING");
    }
    if (!architecture.getGeneratedFromSha().equalsIgnoreCase(handle.sourceRevision())
        || (query.expectedSnapshot().commitSha() != null
            && !architecture
                .getGeneratedFromSha()
                .equalsIgnoreCase(query.expectedSnapshot().commitSha()))) {
      return Contribution.stale("REPO_ARCHITECTURE_SOURCE_SHA_MISMATCH");
    }
    if (codeMap.getId() == null
        || architecture.getSourceCodeMapId() == null
        || !codeMap.getId().equals(architecture.getSourceCodeMapId())) {
      return Contribution.stale("REPO_ARCHITECTURE_CODEMAP_MISMATCH");
    }
    String content = architecture.getContent();
    String model = architecture.getModelUsed();
    if (content == null
        || content.isBlank()
        || model == null
        || model.isBlank()
        || "fallback".equalsIgnoreCase(model)
        || content.contains("LLM generation failed")) {
      return Contribution.unavailable("REPO_ARCHITECTURE_NOT_VERIFIED");
    }
    Extraction extraction =
        extract(
            query,
            RepositoryCapability.ARCHITECTURE,
            RepositoryEvidenceScope.PROJECT_ARCHITECTURE,
            RepositoryEvidenceKind.ARCHITECTURE_SECTION,
            RepositoryEvidenceProvenance.ARCHITECTURE_MODEL_DERIVED,
            "repos/" + repositoryId + "/architecture.md",
            content,
            0.65,
            RepositoryEvidenceAmbiguity.UNKNOWN,
            maxHits,
            false);
    return codeMap.getContent().contains(PARTIAL_INVENTORY_MARKER)
        ? Contribution.partial(
            extraction.evidence(), "SOURCE_CODEMAP_INVENTORY_PARTIAL", extraction.hasMore())
        : extraction.hasMore()
            ? Contribution.partial(extraction.evidence(), "RESULT_LIMIT", true)
            : Contribution.available(extraction.evidence());
  }

  private static Extraction extract(
      RepositoryQuery query,
      RepositoryCapability capability,
      RepositoryEvidenceScope scope,
      RepositoryEvidenceKind kind,
      RepositoryEvidenceProvenance provenance,
      String path,
      String content,
      double confidence,
      RepositoryEvidenceAmbiguity ambiguity,
      int maxHits,
      boolean excludePurposeSummaries) {
    String needle = query.text().toLowerCase(Locale.ROOT);
    String[] lines = content.split("\\R", -1);
    List<RepositoryEvidence> evidence = new ArrayList<>();
    boolean hasMore = false;
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      if (excludePurposeSummaries && line.stripLeading().startsWith("**Purpose:**")) continue;
      if (!line.toLowerCase(Locale.ROOT).contains(needle)) continue;
      if (evidence.size() >= maxHits) {
        hasMore = true;
        break;
      }
      evidence.add(
          new RepositoryEvidence(
              null,
              scope,
              capability,
              kind,
              path,
              index + 1,
              index + 1,
              null,
              null,
              boundedExcerpt(line),
              null,
              provenance,
              confidence,
              ambiguity,
              query.expectedSnapshot(),
              query.fingerprint()));
    }
    return new Extraction(List.copyOf(evidence), hasMore);
  }

  private static String boundedExcerpt(String line) {
    String normalized = line == null ? "" : line.strip();
    if (normalized.isEmpty()) normalized = "(blank matching line)";
    if (normalized.length() <= MAX_EXCERPT_CHARACTERS) return normalized;
    return normalized.substring(0, MAX_EXCERPT_CHARACTERS - 1) + "…";
  }

  private static UUID parseRepositoryId(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private record Extraction(List<RepositoryEvidence> evidence, boolean hasMore) {}
}
