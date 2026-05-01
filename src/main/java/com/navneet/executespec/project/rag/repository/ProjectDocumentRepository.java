package com.navneet.executespec.project.rag.repository;

import java.util.List;

import com.navneet.executespec.project.rag.entity.ProjectDocument;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectDocumentRepository extends JpaRepository<ProjectDocument, String> {
  List<ProjectDocument> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(String projectId);

  long countByProjectIdAndStatusAndDeletedAtIsNull(String projectId, ProjectDocument.Status status);
}
