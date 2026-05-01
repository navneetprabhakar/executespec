package com.navneet.executespec.project.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.project.entity.ProjectRepo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepoRepository extends JpaRepository<ProjectRepo, UUID> {

  List<ProjectRepo> findByOwnerOrgId(String ownerOrgId);

  List<ProjectRepo> findByOwnerUserId(String ownerUserId);

  Optional<ProjectRepo> findByOwnerOrgIdAndGithubRepoFullName(String orgId, String fullName);

  Optional<ProjectRepo> findByOwnerUserIdAndGithubRepoFullName(String userId, String fullName);
}
