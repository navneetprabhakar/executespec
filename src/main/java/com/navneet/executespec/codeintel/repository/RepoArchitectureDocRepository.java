package com.navneet.executespec.codeintel.repository;

import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoArchitectureDocRepository extends JpaRepository<RepoArchitectureDoc, UUID> {

    Optional<RepoArchitectureDoc> findFirstByProjectIdAndRepoIdOrderByVersionDesc(
            String projectId, UUID repoId);

    List<RepoArchitectureDoc> findByProjectIdOrderByGeneratedAtDesc(String projectId);
}
