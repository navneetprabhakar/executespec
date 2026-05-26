package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.navneet.executespec.analytics.EventName;
import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.dto.CreateProjectRequest;
import com.navneet.executespec.auth.dto.ProjectDto;
import com.navneet.executespec.auth.dto.ProjectMemberDto;
import com.navneet.executespec.auth.dto.UpdateProjectSettingsRequest;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository projectMemberRepo;
  private final AppUserRepository userRepo;
  private final ActivityAuditService auditService;
  private final EventTrackingService eventTracking;
  private final TierEnforcementService tierEnforcement;

  @Transactional
  public ProjectDto createProject(String orgId, String createdBy, CreateProjectRequest req) {
    // V140 — block before save when the org is at its tier project cap.
    tierEnforcement.assertCanCreateProject(createdBy, orgId);

    LlmProvider provider = LlmProvider.ANTHROPIC;
    if (req.aiProvider() != null) {
      try {
        provider = LlmProvider.valueOf(req.aiProvider().toUpperCase());
      } catch (IllegalArgumentException ignored) {
        /* default ANTHROPIC */
      }
    }

    Project project =
        Project.builder()
            .id(UUID.randomUUID().toString())
            .orgId(orgId)
            .name(req.name())
            .description(req.description())
            .aiProvider(provider)
            .githubOwner(req.githubOwner())
            .status("ACTIVE")
            .createdBy(createdBy)
            .build();
    projectRepo.save(project);

    // Auto-add creator as PROJECT_ADMIN
    projectMemberRepo.save(
        ProjectMember.builder()
            .id(UUID.randomUUID().toString())
            .projectId(project.getId())
            .userId(createdBy)
            .projectRole("PROJECT_ADMIN")
            .build());

    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_CREATED")
            .entityType("PROJECT")
            .entityId(project.getId())
            .orgId(orgId)
            .actorId(createdBy)
            .newValue("{\"name\":\"" + req.name() + "\",\"orgId\":\"" + orgId + "\"}")
            .build());

    // Wave 2.1 — activation funnel step 2 (fires once per user — the
    // EventTrackingService composite PK guarantees idempotency across
    // the user's second+ project).
    eventTracking.track(
        createdBy,
        EventName.PROJECT_CREATED,
        java.util.Map.of(
            "aiProvider",
            provider.name(),
            "hasGithubOwner",
            req.githubOwner() != null && !req.githubOwner().isBlank(),
            "orgId",
            orgId == null ? "" : orgId));

    log.info("Project created: {} ({}) in org {}", req.name(), project.getId(), orgId);
    return ProjectDto.from(project);
  }

  public List<ProjectDto> listProjectsForUser(String orgId, String userId, boolean isOrgAdmin) {
    // All org members can see all org projects — projects are a shared grouping tool.
    // Write operations (create/archive/manage members) are still restricted to ORG_ADMIN.
    // V191 (PR-B-part-2) — exclude soft-deleted tombstones from this dashboard listing.
    // Archived projects ARE returned (UI toggles them in/out via the "Show archived" switch);
    // only deletedAt-stamped rows are hidden entirely until the 30-day undo window lapses.
    // Org projects (shared grouping for all org members), keyed for dedupe.
    java.util.LinkedHashMap<String, ProjectDto> visible = new java.util.LinkedHashMap<>();
    for (Project p : projectRepo.findByOrgIdAndDeletedAtIsNull(orgId)) {
      visible.put(p.getId(), ProjectDto.from(p));
    }
    // RL-2026-05-044 — V175 cross-org visibility fix. Also include projects this user explicitly
    // belongs to via an accepted invite (a ProjectMember row, typically in another org). Without
    // this, an accepted invitee could OPEN the project by URL (ProjectAccessGuard.assertCanRead
    // honours ProjectMember) but it NEVER appeared in their project list — the reported bug.
    // Soft-deleted projects stay hidden; dedupe keeps an org project from listing twice.
    for (String pid : projectMemberRepo.findProjectIdsByUserId(userId)) {
      if (pid == null || visible.containsKey(pid)) continue;
      projectRepo
          .findById(pid)
          .filter(p -> p.getDeletedAt() == null)
          .ifPresent(p -> visible.put(pid, ProjectDto.from(p)));
    }
    return new java.util.ArrayList<>(visible.values());
  }

  public ProjectDto getProject(
      String projectId, String userId, boolean isSuperAdmin, boolean isOrgAdmin) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    // V191 (PR-B-part-2) — soft-deleted projects 404 from the normal GET endpoint. The restore
    // flow is the only legitimate path to bring one back; allowing direct GET would let a user
    // bookmark a tombstone and stumble into confusing "dead project" UI states. SUPER_ADMIN
    // bypasses this so support can inspect a deleted project before restore.
    if (project.getDeletedAt() != null && !isSuperAdmin) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    }
    // All org members can view any project in their org (projects are a shared grouping tool).
    // Org membership is already validated by the controller's auth check.
    if (!isSuperAdmin) {
      AppUser user = userRepo.findById(userId).orElse(null);
      boolean orgMatch =
          user != null && user.getOrgId() != null && user.getOrgId().equals(project.getOrgId());
      // RL-2026-05-044 — honour V175 cross-org ProjectMember access (consistent with
      // ProjectAccessGuard.assertCanRead, which the controller already applied at the call site).
      // Without this an accepted cross-org invitee passed the guard then got 403 from this inline
      // org-only check — they couldn't open the project even though they're a member.
      boolean isMember = projectMemberRepo.existsByProjectIdAndUserId(projectId, userId);
      if (!orgMatch && !isMember) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Not a member of this organization");
      }
    }
    return ProjectDto.from(project);
  }

  /**
   * V191 (PR-B-part-2) — admin-only fetch that includes soft-deleted rows. Used by the restore
   * endpoint so an ORG_ADMIN can see what they're about to bring back before clicking. Bypasses the
   * deletedAt 404 in {@link #getProject}.
   */
  public ProjectDto getProjectIncludingDeleted(String projectId) {
    return projectRepo
        .findById(projectId)
        .map(ProjectDto::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
  }

  @Transactional
  public ProjectDto archiveProject(String projectId, String actorId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    // V191 (PR-B-part-2) — idempotency. Re-archiving an already-archived project is a no-op,
    // not a duplicate audit row. The frontend confirm-then-archive flow can double-tap on a slow
    // network and we don't want two PROJECT_ARCHIVED events for one user intent.
    if (project.getArchivedAt() != null) {
      return ProjectDto.from(project);
    }
    // V191 (PR-B-part-2) — refuse to archive a soft-deleted project. Caller must restore first
    // then archive; otherwise the state machine would have a row in two terminal states (deleted +
    // archived) which the cleanup sweeper can't reason about.
    if (project.getDeletedAt() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Project is deleted — restore before archiving.");
    }
    String oldStatus = project.getStatus();
    Instant now = Instant.now();
    project.setStatus("ARCHIVED");
    project.setArchivedAt(now);
    project.setUpdatedAt(now);
    ProjectDto result = ProjectDto.from(projectRepo.save(project));

    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_ARCHIVED")
            .entityType("PROJECT")
            .entityId(projectId)
            .orgId(project.getOrgId())
            .actorId(actorId)
            .oldValue("{\"status\":\"" + oldStatus + "\"}")
            .newValue("{\"status\":\"ARCHIVED\",\"archivedAt\":\"" + now + "\"}")
            .build());

    return result;
  }

  /**
   * V191 (PR-B-part-2) — reverse of {@link #archiveProject(String, String)}. Flips status back to
   * ACTIVE and clears the archive timestamp; the cleanup sweeper will leave the row alone after
   * this. Idempotent — re-unarchiving a project that's already ACTIVE is a no-op.
   */
  @Transactional
  public ProjectDto unarchiveProject(String projectId, String actorId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if (project.getDeletedAt() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Project is deleted — restore (not unarchive) to bring it back.");
    }
    if (project.getArchivedAt() == null) {
      // Already active — idempotent return.
      return ProjectDto.from(project);
    }
    Instant now = Instant.now();
    project.setStatus("ACTIVE");
    project.setArchivedAt(null);
    project.setUpdatedAt(now);
    ProjectDto result = ProjectDto.from(projectRepo.save(project));

    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_UNARCHIVED")
            .entityType("PROJECT")
            .entityId(projectId)
            .orgId(project.getOrgId())
            .actorId(actorId)
            .oldValue("{\"status\":\"ARCHIVED\"}")
            .newValue("{\"status\":\"ACTIVE\"}")
            .build());

    return result;
  }

  /**
   * V191 (PR-B-part-2) — soft-delete with 30-day undo window. The row stays in the DB so {@link
   * #restoreProject(String, String)} can bring it back; after 30 days the cleanup sweeper (PR-H)
   * hard-deletes it along with its dependent ledger + run-summary rows. Idempotent on re-call.
   */
  @Transactional
  public void softDeleteProject(String projectId, String actorId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if (project.getDeletedAt() != null) {
      // Already deleted — idempotent no-op. We deliberately do NOT update the timestamp on
      // re-delete; a user clicking "delete" twice should not extend their restore window.
      return;
    }
    Instant now = Instant.now();
    project.setDeletedAt(now);
    project.setUpdatedAt(now);
    projectRepo.save(project);

    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_DELETED")
            .entityType("PROJECT")
            .entityId(projectId)
            .orgId(project.getOrgId())
            .actorId(actorId)
            .oldValue("{\"status\":\"" + project.getStatus() + "\"}")
            .newValue("{\"deletedAt\":\"" + now + "\"}")
            .build());

    log.info("Project soft-deleted: {} (actor={}, undoWindow=30d)", projectId, actorId);
  }

  /**
   * V191 (PR-B-part-2) — reverse of {@link #softDeleteProject(String, String)}. Only succeeds if
   * the project is currently soft-deleted; refuses (404) otherwise so the frontend can tell
   * "already restored" from "doesn't exist". Resets {@code deletedAt} to NULL; the status field is
   * not touched (a project that was archived-then-deleted comes back archived, not active).
   */
  @Transactional
  public ProjectDto restoreProject(String projectId, String actorId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if (project.getDeletedAt() == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Project is not deleted — nothing to restore.");
    }
    Instant now = Instant.now();
    project.setDeletedAt(null);
    project.setUpdatedAt(now);
    ProjectDto result = ProjectDto.from(projectRepo.save(project));

    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_RESTORED")
            .entityType("PROJECT")
            .entityId(projectId)
            .orgId(project.getOrgId())
            .actorId(actorId)
            .oldValue("{\"deletedAt\":\"present\"}")
            .newValue("{\"deletedAt\":null,\"status\":\"" + project.getStatus() + "\"}")
            .build());

    log.info("Project restored: {} (actor={})", projectId, actorId);
    return result;
  }

  @Transactional
  public void addMember(String projectId, String userId, String role, String actorId) {
    requireProjectAdmin(projectId, actorId);
    if (!projectMemberRepo.existsByProjectIdAndUserId(projectId, userId)) {
      String effectiveRole = role != null ? role : "PROJECT_DEVELOPER";
      projectMemberRepo.save(
          ProjectMember.builder()
              .id(UUID.randomUUID().toString())
              .projectId(projectId)
              .userId(userId)
              .projectRole(effectiveRole)
              .build());

      Project project = projectRepo.findById(projectId).orElse(null);
      auditService.record(
          ActivityAudit.builder()
              .eventType("PROJECT_MEMBER_ADDED")
              .entityType("PROJECT")
              .entityId(projectId)
              .orgId(project != null ? project.getOrgId() : null)
              .actorId(actorId)
              .newValue("{\"userId\":\"" + userId + "\",\"role\":\"" + effectiveRole + "\"}")
              .build());
    }
  }

  @Transactional
  public void removeMember(String projectId, String userId, String actorId) {
    requireProjectAdmin(projectId, actorId);
    ProjectMember target =
        projectMemberRepo
            .findByProjectIdAndUserId(projectId, userId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Member not found in project"));

    // Last-admin guard — prevent locking the project out of management.
    if ("PROJECT_ADMIN".equals(target.getProjectRole())) {
      int adminCount = projectMemberRepo.countByProjectIdAndProjectRole(projectId, "PROJECT_ADMIN");
      if (adminCount <= 1) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Cannot remove the only project admin — add another admin first.");
      }
    }

    projectMemberRepo.deleteByProjectIdAndUserId(projectId, userId);

    Project project = projectRepo.findById(projectId).orElse(null);
    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_MEMBER_REMOVED")
            .entityType("PROJECT")
            .entityId(projectId)
            .orgId(project != null ? project.getOrgId() : null)
            .actorId(actorId)
            .oldValue("{\"userId\":\"" + userId + "\"}")
            .build());
  }

  /** Throws 403 unless the actor is a PROJECT_ADMIN on this project. */
  private void requireProjectAdmin(String projectId, String actorId) {
    ProjectMember m = projectMemberRepo.findByProjectIdAndUserId(projectId, actorId).orElse(null);
    if (m == null || !"PROJECT_ADMIN".equals(m.getProjectRole())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only project admins can add or remove project members.");
    }
  }

  @Transactional
  public ProjectDto updateSettings(
      String projectId, UpdateProjectSettingsRequest req, String actorId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

    String oldProvider =
        project.getAiProvider() != null ? project.getAiProvider().name() : "ANTHROPIC";
    String oldGithubOwner = project.getGithubOwner();

    if (req.aiProvider() != null) {
      // v2.5.13 — GOOGLE → GEMINI rename; accept either legacy "GOOGLE"
      // or canonical "GEMINI" input and always store GEMINI enum.
      String raw = req.aiProvider().trim().toUpperCase();
      if ("GOOGLE".equals(raw)) raw = "GEMINI";
      try {
        LlmProvider provider = LlmProvider.valueOf(raw);
        project.setAiProvider(provider);
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid AI provider: " + req.aiProvider() + ". Valid: ANTHROPIC, GEMINI, OPENAI");
      }
    }

    if (req.githubOwner() != null) {
      project.setGithubOwner(req.githubOwner().isBlank() ? null : req.githubOwner().trim());
    }

    // RL-2026-05-038 — the settings form also sends name/description/targetBranch/
    // branchNamingPattern; these were silently dropped (the DTO only had aiProvider +
    // githubOwner). null = field not provided (the form sends `|| undefined`) → leave unchanged.
    if (req.name() != null && !req.name().isBlank()) {
      project.setName(req.name().trim());
    }
    if (req.description() != null) {
      project.setDescription(req.description().isBlank() ? null : req.description().trim());
    }
    if (req.targetBranch() != null && !req.targetBranch().isBlank()) {
      project.setTargetBranch(req.targetBranch().trim());
    }
    if (req.branchNamingPattern() != null && !req.branchNamingPattern().isBlank()) {
      project.setBranchNamingPattern(req.branchNamingPattern().trim());
    }

    project.setUpdatedAt(Instant.now());
    ProjectDto result = ProjectDto.from(projectRepo.save(project));

    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_SETTINGS_UPDATED")
            .entityType("PROJECT")
            .entityId(projectId)
            .orgId(project.getOrgId())
            .actorId(actorId)
            .oldValue(
                "{\"aiProvider\":\""
                    + oldProvider
                    + "\",\"githubOwner\":\""
                    + (oldGithubOwner != null ? oldGithubOwner : "")
                    + "\"}")
            .newValue(
                "{\"aiProvider\":\""
                    + project.getAiProvider().name()
                    + "\",\"githubOwner\":\""
                    + (project.getGithubOwner() != null ? project.getGithubOwner() : "")
                    + "\"}")
            .build());

    log.info(
        "Project settings updated: {} — aiProvider: {} → {}, githubOwner: {} → {}",
        projectId,
        oldProvider,
        project.getAiProvider(),
        oldGithubOwner,
        project.getGithubOwner());
    return result;
  }

  public List<ProjectMemberDto> listMembers(String projectId) {
    return projectMemberRepo.findByProjectId(projectId).stream()
        .map(
            m -> {
              AppUser user = userRepo.findById(m.getUserId()).orElse(null);
              if (user == null) return null;
              return ProjectMemberDto.from(user, m);
            })
        .filter(m -> m != null)
        .collect(Collectors.toList());
  }
}
