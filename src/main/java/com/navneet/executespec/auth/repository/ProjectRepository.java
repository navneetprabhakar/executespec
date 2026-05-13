package com.navneet.executespec.auth.repository;

import java.util.List;

import com.navneet.executespec.auth.entity.Project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, String> {

  /**
   * Raw all-rows-by-org lookup including archived AND soft-deleted entries. Most call sites should
   * prefer {@link #findByOrgIdAndDeletedAtIsNull(String)} — this one is reserved for {@code
   * UserDataExportService} (GDPR export needs to see tombstones too) and the cleanup sweeper.
   */
  List<Project> findByOrgId(String orgId);

  /**
   * V191 (PR-B-part-2) — listing path used by the dashboard. Excludes soft-deleted rows (deletedAt
   * IS NOT NULL); archived rows are still returned because the UI filters them client- side via a
   * toggle. Soft-deleted rows are user-tombstones that should be completely invisible until the
   * 30-day restore window expires.
   */
  List<Project> findByOrgIdAndDeletedAtIsNull(String orgId);

  List<Project> findByOrgIdAndStatus(String orgId, String status);

  /** Wave 2.2 — used by {@code SandboxCleanupJob}. */
  List<Project> findByIsSandboxTrueAndStatus(String status);

  /**
   * V140 — used by {@code TierEnforcementService.assertCanCreateProject} to enforce per-tier
   * project caps. Counts only ACTIVE projects so an archived project doesn't keep blocking new
   * ones.
   */
  long countByOrgIdAndStatus(String orgId, String status);

  /**
   * V191 (PR-B-part-2) — counts only live, undeleted ACTIVE projects toward the tier cap. Distinct
   * from {@link #countByOrgIdAndStatus(String, String)} which still counts soft-deleted tombstones
   * during their 30-day restore window. Use this for cap enforcement so a customer isn't
   * double-charged for a project they've deleted but the sweeper hasn't reaped yet.
   */
  long countByOrgIdAndStatusAndDeletedAtIsNull(String orgId, String status);
}
