package com.navneet.executespec.codeintel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectGeneratedDocRepository extends JpaRepository<ProjectGeneratedDoc, UUID> {

  Optional<ProjectGeneratedDoc> findFirstByProjectIdAndDocTypeOrderByVersionDesc(
      String projectId, DocType docType);

  List<ProjectGeneratedDoc> findByProjectIdOrderByGeneratedAtDesc(String projectId);

  /**
   * Sum of {@code cost_usd} across every generated doc (EXPLANATION + SETUP + future kinds) for a
   * project. Used by the project dashboard to roll up doc-generation spend.
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT COALESCE(SUM(d.costUsd), 0.0) FROM ProjectGeneratedDoc d "
          + "WHERE d.projectId = :projectId")
  double sumCostUsdByProject(
      @org.springframework.data.repository.query.Param("projectId") String projectId);
}
