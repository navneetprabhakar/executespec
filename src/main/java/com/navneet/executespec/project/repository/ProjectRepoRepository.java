package com.navneet.executespec.project.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.project.entity.ProjectRepo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepoRepository extends JpaRepository<ProjectRepo, UUID> {

  List<ProjectRepo> findByOwnerOrgId(String ownerOrgId);

  List<ProjectRepo> findByOwnerUserId(String ownerUserId);

  Optional<ProjectRepo> findByOwnerOrgIdAndGithubRepoFullName(String orgId, String fullName);

  Optional<ProjectRepo> findByOwnerUserIdAndGithubRepoFullName(String userId, String fullName);

  Optional<ProjectRepo> findByOwnerOrgIdAndScmProviderAndScmRepositoryId(
      String orgId, String scmProvider, String scmRepositoryId);

  Optional<ProjectRepo> findByOwnerUserIdAndScmProviderAndScmRepositoryId(
      String userId, String scmProvider, String scmRepositoryId);

  Optional<ProjectRepo> findByOwnerUserIdAndLocalFolderId(String userId, UUID localFolderId);

  List<ProjectRepo> findByGithubRepoFullNameIgnoreCase(String fullName);

  /** Bounded scheduler input; excludes local and non-GitHub sources before entity hydration. */
  @Query(
      "SELECT r FROM ProjectRepo r WHERE (r.updatedAt IS NULL OR r.updatedAt < :cutoff) "
          + "AND (r.sourceType IS NULL OR r.sourceType <> 'LOCAL') "
          + "AND (r.scmProvider IS NULL OR UPPER(r.scmProvider) = 'GITHUB') "
          + "ORDER BY r.updatedAt ASC, r.id ASC")
  List<ProjectRepo> findStackRefreshCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);
}
