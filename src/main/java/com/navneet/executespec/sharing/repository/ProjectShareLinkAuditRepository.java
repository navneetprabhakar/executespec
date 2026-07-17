package com.navneet.executespec.sharing.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.sharing.entity.ProjectShareLinkAudit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectShareLinkAuditRepository
    extends JpaRepository<ProjectShareLinkAudit, UUID> {

  List<ProjectShareLinkAudit> findTop100ByLinkIdOrderByOccurredAtDesc(UUID linkId);
}
