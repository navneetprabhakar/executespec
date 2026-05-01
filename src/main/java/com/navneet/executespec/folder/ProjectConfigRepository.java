package com.navneet.executespec.folder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * E1.7 — Spring Data repository over {@link ProjectConfig}. Used only when the project's config
 * storage is {@link com.navneet.executespec.auth.entity.Project.ConfigStorage#DATABASE}.
 */
@Repository
public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, UUID> {
  Optional<ProjectConfig> findByProjectIdAndConfigKey(String projectId, String configKey);

  List<ProjectConfig> findAllByProjectId(String projectId);
}
