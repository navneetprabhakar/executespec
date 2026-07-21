package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.navneet.executespec.analytics.EventName;
import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.dto.CreateProjectRequest;
import com.navneet.executespec.auth.dto.ProjectDto;
import com.navneet.executespec.auth.dto.ProjectMemberCapabilitiesDto;
import com.navneet.executespec.auth.dto.ProjectMemberDto;
import com.navneet.executespec.auth.dto.UpdateProjectSettingsRequest;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ProviderReadinessService;

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
  private final OrgMemberRepository orgMemberRepo;
  private final ActivityAuditService auditService;
  private final EventTrackingService eventTracking;
  private final TierEnforcementService tierEnforcement;
  private final ProviderReadinessService providerReadinessService;
  private final OrgRepository orgRepo;

  @Transactional
  public ProjectDto createProject(String orgId, String createdBy, CreateProjectRequest req) {
    // V140 — block before save when the org is at its tier project cap.
    tierEnforcement.assertCanCreateProject(createdBy, orgId);

    LlmProvider provider = resolveDispatchableProjectProvider(createdBy, req.aiProvider());
    rememberPreferredProvider(createdBy, provider);

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
    // RL-2026-05-056 — orgId is null for a user with no home org (free-tier "no org" state, or a
    // user removed from their only org). They own no org projects, but the cross-org ProjectMember
    // union below still applies — so guard the org lookup and fall through to the union rather than
    // letting the caller short-circuit on null (which hid the accepted project from invitees).
    if (orgId != null) {
      for (Project p : projectRepo.findByOrgIdAndDeletedAtIsNull(orgId)) {
        visible.put(p.getId(), ProjectDto.from(p));
      }
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
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if (userId == null || userId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
    }
    AppUser target =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    // Direct member administration is intentionally host-org only. Cross-org collaborators must
    // use the expiring, email-bound invite/accept flow so external-sharing policy, consent and the
    // is_external audit marker cannot be bypassed by posting a guessed user id here.
    if (target.getOrgId() == null || !target.getOrgId().equals(project.getOrgId())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Cross-organization members must be added through a project invitation");
    }
    String effectiveRole = role == null || role.isBlank() ? "PROJECT_DEVELOPER" : role.trim();
    if (!java.util.Set.of("PROJECT_ADMIN", "PROJECT_DEVELOPER", "PROJECT_VIEWER")
        .contains(effectiveRole)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project role");
    }
    // RR-1513 — this used to silently no-op on an existing membership while still returning 201,
    // so any caller reusing addMember for a role change reported success and changed nothing.
    // Changing an existing member's role goes through updateMemberRole.
    if (projectMemberRepo.existsByProjectIdAndUserId(projectId, userId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "User is already a member of this project — change their role instead of re-adding"
              + " them.");
    }
    projectMemberRepo.save(
        ProjectMember.builder()
            .id(UUID.randomUUID().toString())
            .projectId(projectId)
            .userId(userId)
            .projectRole(effectiveRole)
            .build());

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

  /**
   * RR-1513 — change an existing member's project role. Before this, promoting a PROJECT_VIEWER or
   * demoting a PROJECT_ADMIN was impossible in the product: there was no route, and the
   * remove-then-re-add workaround is blocked for external collaborators because cross-org direct
   * adds are rejected. Guarded exactly like add/remove, with the same last-admin guard applied on
   * demotion that {@link #removeMember} applies on removal.
   */
  @Transactional
  public void updateMemberRole(String projectId, String userId, String role, String actorId) {
    requireProjectAdmin(projectId, actorId);
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    String newRole = role == null ? "" : role.trim();
    if (!java.util.Set.of("PROJECT_ADMIN", "PROJECT_DEVELOPER", "PROJECT_VIEWER")
        .contains(newRole)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project role");
    }
    ProjectMember member =
        projectMemberRepo
            .findByProjectIdAndUserId(projectId, userId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Member not found in project"));
    String previousRole = member.getProjectRole();
    if (newRole.equals(previousRole)) {
      return;
    }
    // Last-admin guard on demotion — mirrors removeMember so the project cannot be locked out.
    if ("PROJECT_ADMIN".equals(previousRole)) {
      int adminCount = projectMemberRepo.countByProjectIdAndProjectRole(projectId, "PROJECT_ADMIN");
      if (adminCount <= 1) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Cannot demote the only project admin — promote another admin first.");
      }
    }
    member.setProjectRole(newRole);
    projectMemberRepo.save(member);

    auditService.record(
        ActivityAudit.builder()
            .eventType("PROJECT_MEMBER_ROLE_CHANGED")
            .entityType("PROJECT")
            .entityId(projectId)
            .orgId(project.getOrgId())
            .actorId(actorId)
            .oldValue("{\"userId\":\"" + userId + "\",\"role\":\"" + previousRole + "\"}")
            .newValue("{\"userId\":\"" + userId + "\",\"role\":\"" + newRole + "\"}")
            .build());
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

  /** Throws 403 unless the actor can manage membership for this project. */
  private void requireProjectAdmin(String projectId, String actorId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    AppUser actor = userRepo.findById(actorId).orElse(null);
    if (actor != null && "SUPER_ADMIN".equals(actor.getGlobalRole())) {
      return;
    }
    if (actor != null
        && actor.getOrgId() != null
        && actor.getOrgId().equals(project.getOrgId())
        && orgMemberRepo
            .findByOrgIdAndUserId(project.getOrgId(), actorId)
            .map(m -> "ORG_ADMIN".equals(m.getOrgRole()))
            .orElse(false)) {
      return;
    }
    ProjectMember m = projectMemberRepo.findByProjectIdAndUserId(projectId, actorId).orElse(null);
    if (m == null || !"PROJECT_ADMIN".equals(m.getProjectRole())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Only project admins or org admins can add or remove project members.");
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
        ModelAccessService.assertProviderEnabled(provider.name());
        assertProviderDispatchable(actorId, provider);
        project.setAiProvider(provider);
        rememberPreferredProvider(actorId, provider);
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid AI provider: "
                + req.aiProvider()
                + ". Valid: ANTHROPIC, GEMINI, OPENAI, DEEPSEEK, OPENROUTER, FIREWORKS");
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

  private void rememberPreferredProvider(String userId, LlmProvider provider) {
    if (userId == null || provider == null) return;
    userRepo
        .findById(userId)
        .ifPresent(
            user -> {
              user.setPreferredAiProvider(provider.name());
              user.setUpdatedAt(Instant.now());
              userRepo.save(user);
            });
  }

  /**
   * Resolve a new project's route from the effective, secret-free readiness contract. An explicit
   * selection must be policy-allowed and credentialed. With no explicit selection, the account
   * preference is used only when it is eligible; otherwise a single eligible provider is selected
   * without ambiguity. Multiple eligible routes require the caller to choose, and zero eligible
   * routes fail before a project row is written.
   *
   * <p>This deliberately has no Anthropic fallback. Under {@code OWN_KEYS}, platform credentials
   * are absent from readiness, so a DeepSeek-only Enterprise org cannot accidentally create an
   * Anthropic-routed project that later fails or spends outside policy.
   */
  private LlmProvider resolveDispatchableProjectProvider(String userId, String requestedProvider) {
    if (requestedProvider != null && !requestedProvider.isBlank()) {
      LlmProvider explicit = parseProvider(requestedProvider);
      assertProviderDispatchable(userId, explicit);
      return explicit;
    }

    List<LlmProvider> eligible =
        providerReadinessService.resolve(userId).stream()
            .filter(ProjectService::isProjectSelectable)
            .map(readiness -> LlmProvider.valueOf(readiness.provider()))
            .toList();

    String preference = userRepo.findById(userId).map(AppUser::getPreferredAiProvider).orElse(null);
    if (preference != null && !preference.isBlank()) {
      LlmProvider preferred = parseProvider(preference);
      if (eligible.contains(preferred)) return preferred;
    }
    if (eligible.size() == 1) return eligible.get(0);
    if (eligible.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "No AI provider is permitted and credentialed for this account. Configure an org or"
              + " user key before creating the project.");
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "aiProvider is required because more than one provider is available for this account.");
  }

  private void assertProviderDispatchable(String userId, LlmProvider provider) {
    boolean eligible =
        providerReadinessService.resolve(userId).stream()
            .anyMatch(
                readiness ->
                    isProjectSelectable(readiness)
                        && readiness.provider().equalsIgnoreCase(provider.name()));
    if (!eligible) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "AI provider "
              + provider.name()
              + " is not permitted and credentialed for this account.");
    }
  }

  private static boolean isProjectSelectable(ProviderReadinessService.ProviderReadiness readiness) {
    // A circuit-open provider is still policy-allowed and credentialed; a transient outage must
    // not rewrite durable project policy. Dispatch itself remains blocked by the health tracker.
    return readiness.dispatchable()
        || readiness.state() == ProviderReadinessService.State.CIRCUIT_OPEN;
  }

  private static LlmProvider parseProvider(String rawProvider) {
    String raw = rawProvider == null ? "" : rawProvider.trim().toUpperCase();
    if ("GOOGLE".equals(raw)) raw = "GEMINI";
    try {
      LlmProvider provider = LlmProvider.valueOf(raw);
      ModelAccessService.assertProviderEnabled(provider.name());
      return provider;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid AI provider: "
              + rawProvider
              + ". Valid: ANTHROPIC, GEMINI, OPENAI, DEEPSEEK, OPENROUTER, FIREWORKS");
    }
  }

  /**
   * RR-1510 — the projection now carries the persisted {@code external} marker and the
   * collaborator's home-org name, so a cross-org collaborator is distinguishable from an employee.
   */
  public List<ProjectMemberDto> listMembers(String projectId) {
    java.util.Map<String, String> orgNames = new java.util.HashMap<>();
    return projectMemberRepo.findByProjectId(projectId).stream()
        .map(
            m -> {
              AppUser user = userRepo.findById(m.getUserId()).orElse(null);
              if (user == null) return null;
              String homeOrgName =
                  user.getOrgId() == null
                      ? null
                      : orgNames.computeIfAbsent(
                          user.getOrgId(),
                          orgId -> orgRepo.findById(orgId).map(Org::getName).orElse(null));
              return ProjectMemberDto.from(user, m, homeOrgName);
            })
        .filter(m -> m != null)
        .collect(Collectors.toList());
  }

  /**
   * RR-1507 / RR-1509 — the effective per-action authority for this caller on this project, so
   * clients stop re-deriving membership authority from a single role guess. Membership
   * administration and share-link administration are genuinely different server guards, and the
   * invite gate has three independent causes; each is reported separately with its own cause code.
   */
  public ProjectMemberCapabilitiesDto memberCapabilities(String projectId, String actorId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    AppUser actor = userRepo.findById(actorId).orElse(null);
    boolean isSuperAdmin = actor != null && "SUPER_ADMIN".equals(actor.getGlobalRole());
    boolean isHostOrgAdmin =
        actor != null
            && actor.getOrgId() != null
            && actor.getOrgId().equals(project.getOrgId())
            && orgMemberRepo
                .findByOrgIdAndUserId(project.getOrgId(), actorId)
                .map(m -> "ORG_ADMIN".equals(m.getOrgRole()))
                .orElse(false);
    ProjectMember membership =
        projectMemberRepo.findByProjectIdAndUserId(projectId, actorId).orElse(null);
    String projectRole = membership == null ? null : membership.getProjectRole();

    boolean canManageMembership =
        isSuperAdmin || isHostOrgAdmin || "PROJECT_ADMIN".equals(projectRole);
    boolean canManageShareLinks = canManageMembership || "PROJECT_DEVELOPER".equals(projectRole);

    // Invite authority mirrors ProjectInviteService.assertCanInvite, cause by cause.
    String inviteBlockedReason = null;
    if (!isSuperAdmin) {
      String tier = tierEnforcement.resolveUserTier(actorId);
      boolean orgAdminOfOwnOrg =
          actor != null
              && actor.getOrgId() != null
              && orgMemberRepo
                  .findByOrgIdAndUserId(actor.getOrgId(), actorId)
                  .map(m -> "ORG_ADMIN".equals(m.getOrgRole()))
                  .orElse(false);
      boolean orgFlagEnabled =
          actor != null
              && actor.getOrgId() != null
              && orgRepo
                  .findById(actor.getOrgId())
                  .map(Org::isExternalSharingEnabled)
                  .orElse(false);
      if (!TierEnforcementService.isPaidTier(tier)) {
        inviteBlockedReason = "TIER";
      } else if (!orgAdminOfOwnOrg) {
        inviteBlockedReason = "ROLE";
      } else if (!orgFlagEnabled) {
        inviteBlockedReason = "ORG_EXTERNAL_SHARING_DISABLED";
      }
    }

    return new ProjectMemberCapabilitiesDto(
        projectRole,
        canManageMembership,
        canManageShareLinks,
        inviteBlockedReason == null,
        inviteBlockedReason);
  }
}
