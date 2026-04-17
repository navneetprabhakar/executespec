package com.navneet.executespec.codeintel.repository;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectGeneratedDocRepository extends JpaRepository<ProjectGeneratedDoc, UUID> {

    Optional<ProjectGeneratedDoc> findFirstByProjectIdAndDocTypeOrderByVersionDesc(
            String projectId, DocType docType);

    List<ProjectGeneratedDoc> findByProjectIdOrderByGeneratedAtDesc(String projectId);
}
