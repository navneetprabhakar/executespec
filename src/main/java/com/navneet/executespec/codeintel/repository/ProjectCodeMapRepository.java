package com.navneet.executespec.codeintel.repository;

import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectCodeMapRepository extends JpaRepository<ProjectCodeMap, UUID> {

    List<ProjectCodeMap> findByProjectId(String projectId);

    Optional<ProjectCodeMap> findFirstByProjectIdAndRepoIdOrderByVersionDesc(String projectId, UUID repoId);
}
