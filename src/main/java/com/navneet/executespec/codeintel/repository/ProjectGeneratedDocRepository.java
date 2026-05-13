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

  /** 2026-05-13 — used by {@code VersionRetentionService} to prune all but the latest N. */
  List<ProjectGeneratedDoc> findByProjectIdAndDocTypeOrderByVersionDesc(
      String projectId, DocType docType);

  /**
   * V195 (PR-Q, 2026-05-13) — short-circuit cache lookup. Returns the most recent doc of this type
   * for the project whose (sourceArchVersion, sourceShasFingerprint) tuple matches the caller's
   * freshly-computed values. Spring Data derives the query from the method name; equivalent to
   * {@code WHERE project_id = ? AND doc_type = ? AND source_arch_version = ? AND
   * source_shas_fingerprint = ? ORDER BY version DESC LIMIT 1}.
   */
  Optional<ProjectGeneratedDoc>
      findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
          String projectId,
          DocType docType,
          Integer sourceArchVersion,
          String sourceShasFingerprint);

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
