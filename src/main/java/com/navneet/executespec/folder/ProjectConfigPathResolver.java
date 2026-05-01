package com.navneet.executespec.folder;

import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E1.7 — single source of truth for "where does this project's config live for this repo?".
 * Encapsulates the multi-project namespacing semantics + the confidential-project DATABASE opt-out
 * so callers don't have to re-implement the decision tree.
 *
 * <h2>Decision tree</h2>
 *
 * <ol>
 *   <li>If the project's {@link Project#configStorageEnum()} is {@link
 *       Project.ConfigStorage#DATABASE}, the config doesn't go to the customer repo at all →
 *       returns {@link Strategy#DATABASE}.
 *   <li>If the repo is linked to <b>only this one project</b>, the config goes to {@code
 *       .executespec/config/...} (no project-namespacing) → returns {@link Strategy#FOLDER_FLAT}.
 *   <li>If the repo is linked to <b>multiple projects</b>, the config goes to {@code
 *       .executespec/config/<project-slug>/...} → returns {@link Strategy#FOLDER_NAMESPACED}.
 * </ol>
 *
 * <p>Project slug = {@link BootstrapService#slugify(String)} of the project's display name. Single
 * source of truth for slugification.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectConfigPathResolver {

  private final ProjectRepository projectRepository;
  private final ProjectRepoLinkRepository linkRepo;

  public Resolution resolve(String projectId, UUID repoId) {
    Project project = projectRepository.findById(projectId).orElse(null);
    if (project == null) {
      // Defensive default — if we somehow lose the project, fall back to
      // DATABASE so we don't accidentally write secrets to a repo that
      // shouldn't exist.
      log.warn(
          "[ProjectConfigPathResolver] project {} not found; falling back to DATABASE", projectId);
      return new Resolution(Strategy.DATABASE, null, null);
    }
    if (project.configStorageEnum() == Project.ConfigStorage.DATABASE) {
      return new Resolution(Strategy.DATABASE, null, null);
    }
    String slug = BootstrapService.slugify(project.getName());
    long linkedProjects =
        linkRepo.findByRepoId(repoId).stream().map(l -> l.getProjectId()).distinct().count();
    if (linkedProjects <= 1) {
      return new Resolution(Strategy.FOLDER_FLAT, slug, ".executespec/config/");
    }
    return new Resolution(Strategy.FOLDER_NAMESPACED, slug, ".executespec/config/" + slug + "/");
  }

  /**
   * Build the on-repo path for a config file under {@code .executespec/config/}. Convenience
   * wrapper over {@link #resolve} for callers that just need a string path.
   *
   * @return the path to write to, or {@code null} when storage is DATABASE (caller should write to
   *     {@link ProjectConfig} instead)
   */
  public String resolveConfigFilePath(String projectId, UUID repoId, String filename) {
    Resolution res = resolve(projectId, repoId);
    if (res.strategy() == Strategy.DATABASE) return null;
    return res.basePath() + filename;
  }

  public enum Strategy {
    /** Config goes to {@code project_config} table; never written to repo. */
    DATABASE,
    /** Repo linked to one project; config at {@code .executespec/config/<file>}. */
    FOLDER_FLAT,
    /** Repo linked to multiple projects; config at {@code .executespec/config/<slug>/<file>}. */
    FOLDER_NAMESPACED
  }

  /**
   * @param strategy which storage location applies
   * @param projectSlug derived from project name; null when strategy=DATABASE
   * @param basePath the {@code .executespec/config/...} prefix; null when DATABASE
   */
  public record Resolution(Strategy strategy, String projectSlug, String basePath) {}
}
