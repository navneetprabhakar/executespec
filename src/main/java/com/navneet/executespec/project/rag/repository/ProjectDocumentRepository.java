package com.navneet.executespec.project.rag.repository;

import java.util.List;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.project.rag.entity.ProjectDocument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectDocumentRepository extends JpaRepository<ProjectDocument, String> {

  /**
   * Terminal states that no longer consume the project's retrieval surface. Their source bytes are
   * purged when the terminal state is written, and they can never be indexed again, so charging
   * them against the document/byte/page/text/token quotas would let dead rows permanently exhaust a
   * project.
   */
  List<ProjectDocument.Status> QUOTA_EXEMPT_STATUSES =
      List.of(ProjectDocument.Status.FAILED, ProjectDocument.Status.CANCELLED);

  List<ProjectDocument> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(String projectId);

  long countByProjectIdAndStatusAndDeletedAtIsNull(String projectId, ProjectDocument.Status status);

  default long countByProjectIdAndDeletedAtIsNull(String projectId) {
    return countActive(projectId, QUOTA_EXEMPT_STATUSES);
  }

  default long sumActiveBytes(String projectId) {
    return sumActiveBytes(projectId, QUOTA_EXEMPT_STATUSES);
  }

  default long sumActivePages(String projectId) {
    return sumActivePages(projectId, QUOTA_EXEMPT_STATUSES);
  }

  default long sumActiveExtractedChars(String projectId) {
    return sumActiveExtractedChars(projectId, QUOTA_EXEMPT_STATUSES);
  }

  default long sumActiveTokens(String projectId) {
    return sumActiveTokens(projectId, QUOTA_EXEMPT_STATUSES);
  }

  @Query(
      "select count(d) from ProjectDocument d where d.projectId=:projectId and d.deletedAt is null"
          + " and d.status not in :excluded")
  long countActive(
      @Param("projectId") String projectId,
      @Param("excluded") List<ProjectDocument.Status> excluded);

  @Query(
      "select coalesce(sum(d.sizeBytes),0) from ProjectDocument d where d.projectId=:projectId and"
          + " d.deletedAt is null and d.status not in :excluded")
  long sumActiveBytes(
      @Param("projectId") String projectId,
      @Param("excluded") List<ProjectDocument.Status> excluded);

  @Query(
      "select coalesce(sum(d.pageCount),0) from ProjectDocument d where d.projectId=:projectId and"
          + " d.deletedAt is null and d.status not in :excluded")
  long sumActivePages(
      @Param("projectId") String projectId,
      @Param("excluded") List<ProjectDocument.Status> excluded);

  @Query(
      "select coalesce(sum(d.extractedChars),0) from ProjectDocument d where d.projectId=:projectId"
          + " and d.deletedAt is null and d.status not in :excluded")
  long sumActiveExtractedChars(
      @Param("projectId") String projectId,
      @Param("excluded") List<ProjectDocument.Status> excluded);

  @Query(
      "select coalesce(sum(d.estimatedTokens),0) from ProjectDocument d where"
          + " d.projectId=:projectId and d.deletedAt is null and d.status not in :excluded")
  long sumActiveTokens(
      @Param("projectId") String projectId,
      @Param("excluded") List<ProjectDocument.Status> excluded);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from ProjectDocument d where d.id=:id")
  java.util.Optional<ProjectDocument> findByIdForUpdate(@Param("id") String id);

  @Query(
      "select d from ProjectDocument d where d.deletedAt is null and d.status in :statuses and"
          + " d.updatedAt < :cutoff order by d.updatedAt")
  List<ProjectDocument> findRecoverable(
      @Param("statuses") List<ProjectDocument.Status> statuses,
      @Param("cutoff") java.time.OffsetDateTime cutoff,
      org.springframework.data.domain.Pageable pageable);
}
