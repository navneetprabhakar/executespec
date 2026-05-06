package com.navneet.executespec.codeintel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectArchitectureDocRepository
    extends JpaRepository<ProjectArchitectureDoc, UUID> {

  List<ProjectArchitectureDoc> findByProjectIdOrderByVersionDesc(String projectId);

  Optional<ProjectArchitectureDoc> findFirstByProjectIdOrderByVersionDesc(String projectId);

  Optional<ProjectArchitectureDoc> findByProjectIdAndVersion(String projectId, Integer version);

  /**
   * Sum of {@code cost_usd} across every architecture-doc version for a project. Used by the
   * project dashboard to roll up arch-generation spend alongside run + review spend.
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT COALESCE(SUM(d.costUsd), 0.0) FROM ProjectArchitectureDoc d "
          + "WHERE d.projectId = :projectId")
  double sumCostUsdByProject(
      @org.springframework.data.repository.query.Param("projectId") String projectId);
}
