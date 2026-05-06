package com.navneet.executespec.codeintel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoArchitectureDocRepository extends JpaRepository<RepoArchitectureDoc, UUID> {

  Optional<RepoArchitectureDoc> findFirstByProjectIdAndRepoIdOrderByVersionDesc(
      String projectId, UUID repoId);

  List<RepoArchitectureDoc> findByProjectIdOrderByGeneratedAtDesc(String projectId);

  /**
   * Sum of {@code cost_usd} across every per-repo architecture doc generated for a project. Used by
   * the project dashboard to roll up arch-generation spend.
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT COALESCE(SUM(d.costUsd), 0.0) FROM RepoArchitectureDoc d "
          + "WHERE d.projectId = :projectId")
  double sumCostUsdByProject(
      @org.springframework.data.repository.query.Param("projectId") String projectId);
}
