package com.navneet.executespec.codeintel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectCodeMap;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectCodeMapRepository extends JpaRepository<ProjectCodeMap, UUID> {

  List<ProjectCodeMap> findByProjectId(String projectId);

  Optional<ProjectCodeMap> findFirstByProjectIdAndRepoIdOrderByVersionDesc(
      String projectId, UUID repoId);

  /** 2026-05-13 — used by {@code VersionRetentionService} to prune all but the latest N. */
  List<ProjectCodeMap> findByProjectIdAndRepoIdOrderByVersionDesc(String projectId, UUID repoId);
}
