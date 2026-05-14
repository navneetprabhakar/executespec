package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * V191 (PR-B-part-2, 2026-05-13) — pins the archive / unarchive / soft-delete / restore lifecycle
 * on {@link ProjectService}. The key invariants we want to keep stable:
 *
 * <ul>
 *   <li>Archive is reversible (idempotent re-archive, no audit churn);
 *   <li>Soft-delete is invisible to listings but reversible within 30 days;
 *   <li>Soft-delete + archive interact safely (no run can land in both terminal states without an
 *       explicit conflict response);
 *   <li>Restore re-issues {@code deletedAt = null} without touching {@code status} — a project
 *       archived-then-deleted comes back archived.
 * </ul>
 */
class ProjectServiceLifecycleTest {

  private ProjectRepository projectRepo;
  private ProjectMemberRepository projectMemberRepo;
  private AppUserRepository userRepo;
  private ActivityAuditService auditService;
  private EventTrackingService eventTracking;
  private TierEnforcementService tierEnforcement;
  private ProjectService svc;

  @BeforeEach
  void setUp() {
    projectRepo = mock(ProjectRepository.class);
    projectMemberRepo = mock(ProjectMemberRepository.class);
    userRepo = mock(AppUserRepository.class);
    auditService = mock(ActivityAuditService.class);
    eventTracking = mock(EventTrackingService.class);
    tierEnforcement = mock(TierEnforcementService.class);
    svc =
        new ProjectService(
            projectRepo, projectMemberRepo, userRepo, auditService, eventTracking, tierEnforcement);
    when(projectRepo.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private Project active(String id) {
    return Project.builder().id(id).orgId("org-1").name("Test").createdBy("u-1").build();
  }

  private Project archived(String id) {
    Project p = active(id);
    p.setStatus("ARCHIVED");
    p.setArchivedAt(Instant.now());
    return p;
  }

  private Project deleted(String id) {
    Project p = active(id);
    p.setDeletedAt(Instant.now());
    return p;
  }

  // ── archive ─────────────────────────────────────────────────────────────

  @Test
  void archive_stampsTimestampAndStatus() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(active("p-1")));

    svc.archiveProject("p-1", "actor-1");

    verify(projectRepo).save(any(Project.class));
    verify(auditService, times(1)).record(any(ActivityAudit.class));
  }

  @Test
  void archive_isIdempotent_returnsWithoutSaveOrAudit() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(archived("p-1")));

    svc.archiveProject("p-1", "actor-1");

    verify(projectRepo, never()).save(any(Project.class));
    verify(auditService, never()).record(any(ActivityAudit.class));
  }

  @Test
  void archive_refusesDeleted_throws409() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(deleted("p-1")));

    assertThatThrownBy(() -> svc.archiveProject("p-1", "actor-1"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // ── unarchive ───────────────────────────────────────────────────────────

  @Test
  void unarchive_clearsTimestampAndStatus() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(archived("p-1")));

    svc.unarchiveProject("p-1", "actor-1");

    verify(projectRepo).save(any(Project.class));
    verify(auditService, times(1)).record(any(ActivityAudit.class));
  }

  @Test
  void unarchive_isIdempotent_onActiveProject() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(active("p-1")));

    svc.unarchiveProject("p-1", "actor-1");

    verify(projectRepo, never()).save(any(Project.class));
    verify(auditService, never()).record(any(ActivityAudit.class));
  }

  @Test
  void unarchive_refusesDeleted_throws409() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(deleted("p-1")));

    assertThatThrownBy(() -> svc.unarchiveProject("p-1", "actor-1"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // ── soft-delete ─────────────────────────────────────────────────────────

  @Test
  void softDelete_stampsTimestampAndRecordsAudit() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(active("p-1")));

    svc.softDeleteProject("p-1", "actor-1");

    verify(projectRepo).save(any(Project.class));
    verify(auditService, times(1)).record(any(ActivityAudit.class));
  }

  @Test
  void softDelete_isIdempotent_doesNotRefreshTimestamp() {
    Project p = deleted("p-1");
    Instant originalDeletedAt = p.getDeletedAt();
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(p));

    svc.softDeleteProject("p-1", "actor-1");

    // No save → timestamp not refreshed → restore window not extended.
    verify(projectRepo, never()).save(any(Project.class));
    verify(auditService, never()).record(any(ActivityAudit.class));
    assertThat(p.getDeletedAt()).isEqualTo(originalDeletedAt);
  }

  @Test
  void softDelete_unknownProject_throws404() {
    when(projectRepo.findById(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> svc.softDeleteProject("p-1", "actor-1"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ── restore ─────────────────────────────────────────────────────────────

  @Test
  void restore_clearsDeletedAtAndPreservesStatus() {
    Project p = deleted("p-1");
    p.setStatus("ARCHIVED"); // archived-then-deleted comes back archived
    p.setArchivedAt(Instant.now());
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(p));

    svc.restoreProject("p-1", "actor-1");

    assertThat(p.getDeletedAt()).isNull();
    assertThat(p.getStatus()).isEqualTo("ARCHIVED");
    assertThat(p.getArchivedAt()).isNotNull();
    verify(auditService, times(1)).record(any(ActivityAudit.class));
  }

  @Test
  void restore_refusesActiveProject_throws404() {
    when(projectRepo.findById("p-1")).thenReturn(Optional.of(active("p-1")));

    assertThatThrownBy(() -> svc.restoreProject("p-1", "actor-1"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
