package com.navneet.executespec.project.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectSpecTemplateRepository extends JpaRepository<ProjectSpecTemplate, UUID> {

    List<ProjectSpecTemplate> findByOwnerTypeAndOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            ProjectSpecTemplate.OwnerType ownerType, String ownerId);
}
