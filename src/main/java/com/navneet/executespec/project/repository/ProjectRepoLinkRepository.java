package com.navneet.executespec.project.repository;

import com.navneet.executespec.project.entity.ProjectRepoLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepoLinkRepository extends JpaRepository<ProjectRepoLink, UUID> {

    List<ProjectRepoLink> findByProjectId(String projectId);

    List<ProjectRepoLink> findByRepoId(UUID repoId);

    Optional<ProjectRepoLink> findByProjectIdAndRepoId(String projectId, UUID repoId);

    boolean existsByRepoId(UUID repoId);

    void deleteByProjectIdAndRepoId(String projectId, UUID repoId);
}
