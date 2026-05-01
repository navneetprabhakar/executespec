package com.navneet.executespec.project.template;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectSpecTemplateRepository extends JpaRepository<ProjectSpecTemplate, UUID> {

  List<ProjectSpecTemplate> findByOwnerTypeAndOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
      ProjectSpecTemplate.OwnerType ownerType, String ownerId);
}
