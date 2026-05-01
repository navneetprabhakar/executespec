package com.navneet.executespec.auth.repository;

import java.util.List;

import com.navneet.executespec.auth.entity.Project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, String> {

  List<Project> findByOrgId(String orgId);

  List<Project> findByOrgIdAndStatus(String orgId, String status);

  /** Wave 2.2 — used by {@code SandboxCleanupJob}. */
  List<Project> findByIsSandboxTrueAndStatus(String status);

  /**
   * V140 — used by {@code TierEnforcementService.assertCanCreateProject} to enforce per-tier
   * project caps. Counts only ACTIVE projects so an archived project doesn't keep blocking new
   * ones.
   */
  long countByOrgIdAndStatus(String orgId, String status);
}
