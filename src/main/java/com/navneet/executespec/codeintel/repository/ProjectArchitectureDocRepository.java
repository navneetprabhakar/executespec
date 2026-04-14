package com.navneet.executespec.codeintel.repository;

import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectArchitectureDocRepository extends JpaRepository<ProjectArchitectureDoc, UUID> {

    List<ProjectArchitectureDoc> findByProjectIdOrderByVersionDesc(String projectId);

    Optional<ProjectArchitectureDoc> findFirstByProjectIdOrderByVersionDesc(String projectId);
}
