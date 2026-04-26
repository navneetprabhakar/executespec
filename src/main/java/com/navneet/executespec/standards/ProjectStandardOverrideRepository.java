package com.navneet.executespec.standards;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectStandardOverrideRepository extends JpaRepository<ProjectStandardOverride, UUID> {
    List<ProjectStandardOverride> findByProjectId(String projectId);
    Optional<ProjectStandardOverride> findByProjectIdAndStandardId(String projectId, String standardId);
}
