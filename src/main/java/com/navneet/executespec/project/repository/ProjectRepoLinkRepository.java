package com.navneet.executespec.project.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.project.entity.ProjectRepoLink;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepoLinkRepository extends JpaRepository<ProjectRepoLink, UUID> {

  List<ProjectRepoLink> findByProjectId(String projectId);

  List<ProjectRepoLink> findByRepoId(UUID repoId);

  Optional<ProjectRepoLink> findByProjectIdAndRepoId(String projectId, UUID repoId);

  boolean existsByRepoId(UUID repoId);

  void deleteByProjectIdAndRepoId(String projectId, UUID repoId);

  /**
   * V140 — used by {@code TierEnforcementService.assertCanLinkRepoToProject} to enforce per-tier
   * repos-per-project caps.
   */
  long countByProjectId(String projectId);
}
