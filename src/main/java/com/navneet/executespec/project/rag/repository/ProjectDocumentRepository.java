package com.navneet.executespec.project.rag.repository;

import java.util.List;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.project.rag.entity.ProjectDocument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectDocumentRepository extends JpaRepository<ProjectDocument, String> {
  List<ProjectDocument> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(String projectId);

  long countByProjectIdAndStatusAndDeletedAtIsNull(String projectId, ProjectDocument.Status status);

  long countByProjectIdAndDeletedAtIsNull(String projectId);

  @Query(
      "select coalesce(sum(d.sizeBytes),0) from ProjectDocument d where d.projectId=:projectId and"
          + " d.deletedAt is null")
  long sumActiveBytes(@Param("projectId") String projectId);

  @Query(
      "select coalesce(sum(d.pageCount),0) from ProjectDocument d where d.projectId=:projectId and"
          + " d.deletedAt is null")
  long sumActivePages(@Param("projectId") String projectId);

  @Query(
      "select coalesce(sum(d.extractedChars),0) from ProjectDocument d where d.projectId=:projectId"
          + " and d.deletedAt is null")
  long sumActiveExtractedChars(@Param("projectId") String projectId);

  @Query(
      "select coalesce(sum(d.estimatedTokens),0) from ProjectDocument d where"
          + " d.projectId=:projectId and d.deletedAt is null")
  long sumActiveTokens(@Param("projectId") String projectId);

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
