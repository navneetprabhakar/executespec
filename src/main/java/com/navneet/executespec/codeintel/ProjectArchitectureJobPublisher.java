package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The only V353 visibility boundary. Stage validation, lease/source fencing, canonical row writes,
 * and SUCCEEDED publication commit atomically in one PostgreSQL transaction.
 */
@Service
@RequiredArgsConstructor
public class ProjectArchitectureJobPublisher {

  public record Published(
      ProjectArchitectureDoc architecture,
      List<ProjectCodeMap> codeMaps,
      List<RepoArchitectureDoc> repoArchitectures,
      List<String> repoNames,
      String summary) {}

  private final ProjectArchitectureJobStore store;
  private final ProjectRepository projects;
  private final ProjectArchitectureJobService jobs;
  private final ProjectCodeMapRepository codeMaps;
  private final RepoArchitectureDocRepository repoArchitectures;
  private final ProjectArchitectureDocRepository architectures;
  private final ObjectMapper objectMapper;
  private final ProjectWorkspaceService workspace;
  private final VersionRetentionService retention;

  @Transactional
  public Published promote(ProjectArchitectureJobStore.Lease lease, String expectedSourceDigest) {
    projects
        .findByIdForQuotaUpdate(lease.job().projectId())
        .orElseThrow(() -> new IllegalStateException("Architecture project no longer exists"));
    ProjectArchitectureJobStore.Job live =
        store
            .lockLiveForPromotion(lease)
            .orElseThrow(ProjectArchitectureJobService.LeaseLostException::new);
    ProjectArchitectureJobService.SourceSnapshot sourceSnapshot =
        jobs.parseSnapshot(live.sourceSnapshotJson());
    String recomputedSourceDigest =
        ProjectArchitectureJobService.sha256(jobs.canonicalJson(sourceSnapshot));
    if (!expectedSourceDigest.equals(live.sourceSnapshotDigest())
        || !recomputedSourceDigest.equals(live.sourceSnapshotDigest())) {
      throw new IllegalStateException("Architecture source changed before promotion");
    }
    if (live.stagedArtifactsJson() == null) {
      throw new IllegalStateException("Staged architecture is missing");
    }
    ProjectArchitectureService.StagedArtifacts staged = parse(live.stagedArtifactsJson());
    if (!ProjectArchitectureJobService.sha256(jobs.canonicalJson(staged))
        .equals(live.stagedArtifactsDigest())) {
      throw new IllegalStateException("Staged architecture digest is invalid");
    }
    if (!live.projectId().equals(staged.projectId())
        || staged.schemaVersion() != 1
        || !java.util.Objects.equals(live.qualityStatus(), staged.qualityStatus())
        || (!"QUALIFIED".equals(staged.qualityStatus())
            && !"WARNING".equals(staged.qualityStatus()))) {
      throw new IllegalStateException("Staged architecture contract is invalid");
    }
    validateExactStage(live, staged, sourceSnapshot);
    String sourceEvidence =
        staged.codeMaps().stream()
            .map(ProjectArchitectureService.StagedCodeMap::content)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.joining("\n"));
    Map<UUID, String> groundedRepoArchitectures = new LinkedHashMap<>();
    for (ProjectArchitectureService.StagedRepoArchitecture repoArchitecture :
        staged.repoArchitectures()) {
      groundedRepoArchitectures.put(
          repoArchitecture.repoId(),
          GeneratedSignatureGrounding.sanitize(repoArchitecture.content(), sourceEvidence));
    }
    String groundedProjectArchitecture =
        GeneratedSignatureGrounding.sanitize(
            staged.projectArchitecture().content(), sourceEvidence);
    String groundedSummary = GeneratedSignatureGrounding.sanitize(staged.summary(), sourceEvidence);

    List<ProjectCodeMap> savedCodeMaps = new ArrayList<>();
    Map<UUID, ProjectCodeMap> codeMapByRepo = new LinkedHashMap<>();
    for (ProjectArchitectureService.StagedCodeMap stagedCodeMap : staged.codeMaps()) {
      int nextVersion =
          codeMaps
              .findFirstByProjectIdAndRepoIdOrderByVersionDesc(
                  live.projectId(), stagedCodeMap.repoId())
              .map(existing -> existing.getVersion() + 1)
              .orElse(1);
      ProjectCodeMap saved =
          codeMaps.saveAndFlush(
              ProjectCodeMap.builder()
                  .projectId(live.projectId())
                  .repoId(stagedCodeMap.repoId())
                  .version(nextVersion)
                  .content(stagedCodeMap.content())
                  .generatedFromSha(stagedCodeMap.revision())
                  .build());
      savedCodeMaps.add(saved);
      codeMapByRepo.put(stagedCodeMap.repoId(), saved);
    }

    List<RepoArchitectureDoc> savedRepoArchitectures = new ArrayList<>();
    List<String> repoNames = new ArrayList<>();
    for (ProjectArchitectureService.StagedRepoArchitecture stagedRepo :
        staged.repoArchitectures()) {
      ProjectCodeMap source = codeMapByRepo.get(stagedRepo.repoId());
      if (source == null) throw new IllegalStateException("Repository architecture has no codemap");
      int nextVersion =
          repoArchitectures
              .findFirstByProjectIdAndRepoIdOrderByVersionDesc(
                  live.projectId(), stagedRepo.repoId())
              .map(existing -> existing.getVersion() + 1)
              .orElse(1);
      RepoArchitectureDoc saved =
          repoArchitectures.saveAndFlush(
              RepoArchitectureDoc.builder()
                  .projectId(live.projectId())
                  .repoId(stagedRepo.repoId())
                  .version(nextVersion)
                  .content(groundedRepoArchitectures.get(stagedRepo.repoId()))
                  .sourceCodeMapId(source.getId())
                  .generatedFromSha(stagedRepo.revision())
                  .generatedBy(live.requestedByUserId())
                  .modelUsed(stagedRepo.modelUsed())
                  .inputTokens(stagedRepo.inputTokens())
                  .outputTokens(stagedRepo.outputTokens())
                  .costUsd(stagedRepo.costUsd())
                  .build());
      savedRepoArchitectures.add(saved);
      repoNames.add(stagedRepo.repoFullName());
    }

    ProjectArchitectureService.StagedProjectArchitecture stagedProject =
        staged.projectArchitecture();
    int projectVersion =
        architectures
            .findFirstByProjectIdOrderByVersionDesc(live.projectId())
            .map(existing -> existing.getVersion() + 1)
            .orElse(1);
    ProjectArchitectureDoc project =
        architectures.saveAndFlush(
            ProjectArchitectureDoc.builder()
                .projectId(live.projectId())
                .version(projectVersion)
                .content(groundedProjectArchitecture)
                .generatedFromShas(stagedProject.generatedFromShas())
                .generatedFromBranches(stagedProject.generatedFromBranches())
                .costUsd(stagedProject.costUsd())
                .build());
    if (!store.completePromoted(lease, project.getId(), projectVersion, expectedSourceDigest)) {
      throw new ProjectArchitectureJobService.LeaseLostException();
    }
    return new Published(
        project,
        List.copyOf(savedCodeMaps),
        List.copyOf(savedRepoArchitectures),
        List.copyOf(repoNames),
        groundedSummary);
  }

  /**
   * DB is authoritative. Workspace files become visible only after the fenced DB promotion has
   * committed; a later warmer can reconstruct them if this best-effort mirror is interrupted.
   */
  public void publishWorkspace(String projectId, Published published) {
    for (int i = 0; i < published.codeMaps().size(); i++) {
      ProjectCodeMap map = published.codeMaps().get(i);
      String repo = published.repoNames().get(i);
      workspace.writeAndCommit(
          projectId,
          "codemaps/" + shortName(repo) + ".md",
          map.getContent(),
          "codemap: " + repo + " v" + map.getVersion());
    }
    for (int i = 0; i < published.repoArchitectures().size(); i++) {
      RepoArchitectureDoc architecture = published.repoArchitectures().get(i);
      String repo = published.repoNames().get(i);
      workspace.writeAndCommit(
          projectId,
          "repos/" + shortName(repo) + "/architecture.md",
          architecture.getContent(),
          "repo-arch: " + repo + " v" + architecture.getVersion());
    }
    workspace.writeAndCommit(
        projectId,
        "architecture.md",
        published.architecture().getContent(),
        "architecture: combined v" + published.architecture().getVersion());
    if (published.summary() != null && !published.summary().isBlank()) {
      workspace.writeAndCommit(
          projectId, "summary.md", published.summary(), "summary: regenerated");
    }
  }

  public void prune(Published published) {
    for (ProjectCodeMap map : published.codeMaps()) {
      retention.pruneCodeMap(map.getProjectId(), map.getRepoId());
    }
    for (RepoArchitectureDoc doc : published.repoArchitectures()) {
      retention.pruneRepoArch(doc.getProjectId(), doc.getRepoId());
    }
    retention.pruneProjectArch(published.architecture().getProjectId());
  }

  private ProjectArchitectureService.StagedArtifacts parse(String json) {
    try {
      return objectMapper.readValue(json, ProjectArchitectureService.StagedArtifacts.class);
    } catch (Exception malformed) {
      throw new IllegalStateException("Staged architecture JSON is invalid", malformed);
    }
  }

  private void validateExactStage(
      ProjectArchitectureJobStore.Job live,
      ProjectArchitectureService.StagedArtifacts staged,
      ProjectArchitectureJobService.SourceSnapshot snapshot) {
    if (!live.projectId().equals(snapshot.projectId())
        || staged.codeMaps() == null
        || staged.repoArchitectures() == null
        || staged.projectArchitecture() == null
        || staged.codeMaps().size() != snapshot.repositories().size()
        || staged.repoArchitectures().size() != snapshot.repositories().size()) {
      throw new IllegalStateException(
          "Staged architecture does not cover the immutable source set");
    }
    Map<UUID, ProjectArchitectureService.StagedCodeMap> maps = new LinkedHashMap<>();
    for (ProjectArchitectureService.StagedCodeMap map : staged.codeMaps()) {
      if (map == null || maps.put(map.repoId(), map) != null) {
        throw new IllegalStateException("Staged codemap repository set is not one-to-one");
      }
    }
    Map<UUID, ProjectArchitectureService.StagedRepoArchitecture> repoDocs = new LinkedHashMap<>();
    for (ProjectArchitectureService.StagedRepoArchitecture doc : staged.repoArchitectures()) {
      if (doc == null || repoDocs.put(doc.repoId(), doc) != null) {
        throw new IllegalStateException("Staged repository-architecture set is not one-to-one");
      }
    }
    Map<String, String> expectedShas = new LinkedHashMap<>();
    Map<String, String> expectedBranches = new LinkedHashMap<>();
    for (ProjectArchitectureJobService.SourceRepo source : snapshot.repositories()) {
      ProjectArchitectureService.StagedCodeMap map = maps.get(source.repoId());
      ProjectArchitectureService.StagedRepoArchitecture doc = repoDocs.get(source.repoId());
      if (map == null
          || doc == null
          || !source.repoFullName().equals(map.repoFullName())
          || !source.repoFullName().equals(doc.repoFullName())
          || !source.branch().equals(map.branch())
          || !source.branch().equals(doc.branch())
          || !source.revision().equals(map.revision())
          || !source.revision().equals(doc.revision())
          || map.content() == null
          || map.content().isBlank()
          || doc.content() == null
          || doc.content().isBlank()
          || (!doc.fallback() && !live.repoArchitectureModel().equals(doc.modelUsed()))) {
        throw new IllegalStateException(
            "Staged repository evidence does not match the immutable source snapshot");
      }
      expectedShas.put(source.repoFullName(), source.revision());
      expectedBranches.put(source.repoFullName(), source.branch());
    }
    ProjectArchitectureService.StagedProjectArchitecture project = staged.projectArchitecture();
    if (project.content() == null
        || project.content().isBlank()
        || (!project.fallback() && !live.projectArchitectureModel().equals(project.modelUsed()))
        || !expectedShas.equals(readStringMap(project.generatedFromShas()))
        || !expectedBranches.equals(readStringMap(project.generatedFromBranches()))) {
      throw new IllegalStateException(
          "Staged combined architecture does not match the immutable source snapshot");
    }
  }

  private Map<String, String> readStringMap(String json) {
    try {
      return objectMapper.readValue(
          json,
          objectMapper
              .getTypeFactory()
              .constructMapType(LinkedHashMap.class, String.class, String.class));
    } catch (Exception malformed) {
      throw new IllegalStateException("Staged source map JSON is invalid", malformed);
    }
  }

  private static String shortName(String fullName) {
    int slash = fullName == null ? -1 : fullName.lastIndexOf('/');
    return slash >= 0 ? fullName.substring(slash + 1) : fullName;
  }
}
