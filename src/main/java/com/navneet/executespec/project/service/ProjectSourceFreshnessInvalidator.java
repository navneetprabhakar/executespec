package com.navneet.executespec.project.service;

import java.util.LinkedHashSet;
import java.util.UUID;

import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Direct-key cache invalidation for repository topology and observed source-HEAD changes.
 *
 * <p>The workspace-health cache is keyed by repo id, while architecture staleness is keyed by
 * project id. This collaborator resolves only the repository's persisted links and deletes those
 * exact keys; it never scans Redis namespaces.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectSourceFreshnessInvalidator {

  private final ProjectRepoRepository repoRepository;
  private final ProjectRepoLinkRepository linkRepository;
  private final WorkspaceHealthService workspaceHealthService;
  private final StalenessService stalenessService;

  /** Link/unlink already has both exact identities, so no repository lookup is necessary. */
  public void invalidateLink(String projectId, UUID repoId) {
    workspaceHealthService.invalidateOriginSha(repoId);
    invalidateProject(projectId);
  }

  /** Sync changed repo metadata/HEAD evidence; invalidate every project linking that source. */
  public void invalidateRepo(UUID repoId) {
    workspaceHealthService.invalidateOriginSha(repoId);
    if (repoId == null) return;
    try {
      LinkedHashSet<String> projectIds = new LinkedHashSet<>();
      linkRepository.findByRepoId(repoId).stream()
          .map(link -> link.getProjectId())
          .filter(projectId -> projectId != null && !projectId.isBlank())
          .forEach(projectIds::add);
      projectIds.forEach(this::invalidateProject);
    } catch (Exception e) {
      log.warn(
          "Could not resolve project staleness keys for repo {} after source change: {}",
          repoId,
          e.getMessage());
    }
  }

  /**
   * A signed GitHub event identifies the provider repository, not ExecuteSpec's registry UUID.
   * Resolve bounded matching registry rows, then reuse repo-scoped invalidation.
   */
  public void invalidateGitHubRepo(String repoFullName) {
    if (repoFullName == null || repoFullName.isBlank()) return;
    try {
      repoRepository.findByGithubRepoFullNameIgnoreCase(repoFullName.trim()).stream()
          .map(repo -> repo.getId())
          .forEach(this::invalidateRepo);
    } catch (Exception e) {
      log.warn(
          "Could not resolve freshness keys for GitHub repo {}: {}", repoFullName, e.getMessage());
    }
  }

  private void invalidateProject(String projectId) {
    if (projectId == null || projectId.isBlank()) return;
    try {
      stalenessService.invalidate(projectId);
    } catch (Exception e) {
      log.warn("Project staleness invalidation failed for {}: {}", projectId, e.getMessage());
    }
  }
}
