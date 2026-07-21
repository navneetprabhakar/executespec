package com.navneet.executespec.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.notification.InAppNotificationService;
import com.navneet.executespec.sharing.entity.ProjectInvite;
import com.navneet.executespec.sharing.repository.ProjectInviteRepository;
import com.navneet.executespec.sharing.service.ProjectInviteService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProjectInviteServiceTest {

  private static final String HOST_ORG = "org-host";
  private static final String OUTSIDER_ORG = "org-outsider";
  private static final String PROJECT_ID = "project-a";

  private final ProjectInviteRepository inviteRepo = mock(ProjectInviteRepository.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final ProjectMemberRepository memberRepo = mock(ProjectMemberRepository.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final TierConfigRepository tierConfigRepo = mock(TierConfigRepository.class);
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private final OrgMemberRepository orgMemberRepo = mock(OrgMemberRepository.class);

  private final ProjectInviteService service =
      new ProjectInviteService(
          inviteRepo,
          projectRepo,
          memberRepo,
          userRepo,
          tierConfigRepo,
          orgRepo,
          orgMemberRepo,
          mock(MailService.class),
          mock(TierEnforcementService.class),
          mock(ActivityEventEmitter.class),
          mock(InAppNotificationService.class));

  @Test
  void revokeRejectsInviteFromAnotherProject() {
    ProjectInvite invite = invite("project-a");
    when(inviteRepo.findById(invite.getId())).thenReturn(Optional.of(invite));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.revoke("project-b", invite.getId(), actor()));

    assertEquals(404, ex.getStatusCode().value());
    verify(inviteRepo, never()).save(invite);
  }

  @Test
  void revokeMarksSameProjectInviteRevoked() {
    ProjectInvite invite = invite("project-a");
    when(inviteRepo.findById(invite.getId())).thenReturn(Optional.of(invite));

    service.revoke("project-a", invite.getId(), actor());

    assertEquals("REVOKED", invite.getStatus());
    assertEquals("actor-1", invite.getRevokedByUserId());
    verify(inviteRepo).save(invite);
  }

  // RR-973 — authority for an external invite belongs to the org that OWNS the project.

  /**
   * The reported vulnerability: an external collaborator who administers their own paid,
   * sharing-enabled org could invite outsiders into someone else's project, because assertCanInvite
   * only ever read the inviter's own org.
   */
  @Test
  void externalWriterWhoAdminsTheirOwnSharingEnabledOrgCannotInviteIntoAnotherTenantsProject() {
    hostProjectExists();
    // The host org is fully entitled and sharing-enabled, so the refusal can only come from the
    // inviter having no admin standing IN THE HOST ORG.
    hostOrgAllowsSharing();
    AppUser outsider = user("outsider-1", OUTSIDER_ORG, "PRO");
    // Outsider is ORG_ADMIN of their own sharing-enabled org, but holds nothing in the host org.
    when(orgMemberRepo.findByOrgIdAndUserId(OUTSIDER_ORG, "outsider-1"))
        .thenReturn(Optional.of(orgAdmin(OUTSIDER_ORG, "outsider-1")));
    when(orgRepo.findById(OUTSIDER_ORG)).thenReturn(Optional.of(org(OUTSIDER_ORG, "PRO", true)));
    when(orgMemberRepo.findByOrgIdAndUserId(HOST_ORG, "outsider-1")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createInvite(PROJECT_ID, "target@example.com", "PROJECT_ADMIN", outsider));

    assertEquals(403, ex.getStatusCode().value());
    assertTrue(
        ex.getReason() != null && ex.getReason().contains("owns this project"),
        "refusal must name the host org as the authority: " + ex.getReason());
    verify(inviteRepo, never()).save(any());
  }

  /** The host org's own flag governs, even for an admin of that host org. */
  @Test
  void hostOrgAdminIsRefusedWhenTheHostOrgHasExternalSharingDisabled() {
    hostProjectExists();
    when(orgRepo.findById(HOST_ORG)).thenReturn(Optional.of(org(HOST_ORG, "PRO", false)));
    AppUser admin = user("admin-1", HOST_ORG, "PRO");
    when(orgMemberRepo.findByOrgIdAndUserId(HOST_ORG, "admin-1"))
        .thenReturn(Optional.of(orgAdmin(HOST_ORG, "admin-1")));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.createInvite(PROJECT_ID, "target@example.com", "PROJECT_VIEWER", admin));

    assertEquals(403, ex.getStatusCode().value());
    assertTrue(ex.getReason() != null && ex.getReason().contains("disabled"));
  }

  /** A host-org ORG_ADMIN of a paid, sharing-enabled host org still succeeds. */
  @Test
  void hostOrgAdminOfASharingEnabledPaidOrgCanInvite() {
    hostProjectExists();
    hostOrgAllowsSharing();
    AppUser admin = user("admin-1", HOST_ORG, "FREE"); // the host org carries the entitlement
    when(orgMemberRepo.findByOrgIdAndUserId(HOST_ORG, "admin-1"))
        .thenReturn(Optional.of(orgAdmin(HOST_ORG, "admin-1")));
    when(userRepo.findByEmail("target@example.com")).thenReturn(Optional.empty());
    when(inviteRepo.findFirstByProjectIdAndEmailIgnoreCaseAndStatusOrderByCreatedAtDesc(
            PROJECT_ID, "target@example.com", "PENDING"))
        .thenReturn(Optional.empty());
    when(inviteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ProjectInvite created =
        service.createInvite(PROJECT_ID, "target@example.com", "PROJECT_DEVELOPER", admin);

    assertEquals(PROJECT_ID, created.getProjectId());
    assertEquals("PROJECT_DEVELOPER", created.getProjectRole());
    // The host org is recorded, not the inviter's home org — acceptance revalidates against it.
    assertEquals(HOST_ORG, created.getInvitedOrgId());
  }

  /**
   * A solo customer whose auto-provisioned personal org still carries its signup-time FREE tier is
   * not locked out: their own paid tier acts as a floor, but only after host-admin standing is
   * proven.
   */
  @Test
  void personalOrgStillOnItsSignupTierUsesTheProvenAdminsOwnPaidTier() {
    hostProjectExists();
    when(orgRepo.findById(HOST_ORG)).thenReturn(Optional.of(org(HOST_ORG, "FREE", true)));
    when(tierConfigRepo.findById("PRO")).thenReturn(Optional.of(new TierConfig()));
    AppUser admin = user("admin-1", HOST_ORG, "PRO");
    when(orgMemberRepo.findByOrgIdAndUserId(HOST_ORG, "admin-1"))
        .thenReturn(Optional.of(orgAdmin(HOST_ORG, "admin-1")));
    when(userRepo.findByEmail("target@example.com")).thenReturn(Optional.empty());
    when(inviteRepo.findFirstByProjectIdAndEmailIgnoreCaseAndStatusOrderByCreatedAtDesc(
            PROJECT_ID, "target@example.com", "PENDING"))
        .thenReturn(Optional.empty());
    when(inviteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    assertEquals(
        PROJECT_ID,
        service
            .createInvite(PROJECT_ID, "target@example.com", "PROJECT_VIEWER", admin)
            .getProjectId());
  }

  /** Entitlement is never borrowed from a foreign org: an unpaid host cannot be topped up. */
  @Test
  void freeHostOrgWithAFreeAdminIsRefusedAsUnpaid() {
    hostProjectExists();
    when(orgRepo.findById(HOST_ORG)).thenReturn(Optional.of(org(HOST_ORG, "FREE", true)));
    AppUser admin = user("admin-1", HOST_ORG, "FREE");
    when(orgMemberRepo.findByOrgIdAndUserId(HOST_ORG, "admin-1"))
        .thenReturn(Optional.of(orgAdmin(HOST_ORG, "admin-1")));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.createInvite(PROJECT_ID, "target@example.com", "PROJECT_VIEWER", admin));

    assertEquals(403, ex.getStatusCode().value());
    assertTrue(ex.getReason() != null && ex.getReason().contains("paid"));
  }

  /** Acceptance re-consults the host org, so a grant minted under the old rule cannot complete. */
  @Test
  void acceptIsRefusedWhenTheHostOrgNoLongerAllowsExternalCollaborators() {
    ProjectInvite invite = invite(PROJECT_ID);
    when(inviteRepo.findByToken("tok")).thenReturn(Optional.of(invite));
    hostProjectExists();
    when(orgRepo.findById(HOST_ORG)).thenReturn(Optional.of(org(HOST_ORG, "PRO", false)));

    AppUser recipient =
        AppUser.builder().id("u-invitee").email("invitee@example.com").status("ACTIVE").build();

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.accept("tok", recipient));

    assertEquals(403, ex.getStatusCode().value());
    verify(memberRepo, never()).save(any());
  }

  // helpers

  private void hostProjectExists() {
    Project project = new Project();
    project.setId(PROJECT_ID);
    project.setName("Host Project");
    project.setOrgId(HOST_ORG);
    when(projectRepo.findById(PROJECT_ID)).thenReturn(Optional.of(project));
  }

  private void hostOrgAllowsSharing() {
    when(orgRepo.findById(HOST_ORG)).thenReturn(Optional.of(org(HOST_ORG, "PRO", true)));
    when(tierConfigRepo.findById("PRO")).thenReturn(Optional.of(new TierConfig()));
  }

  private static Org org(String id, String tier, boolean sharingEnabled) {
    Org org = new Org();
    org.setId(id);
    org.setTier(tier);
    org.setExternalSharingEnabled(sharingEnabled);
    return org;
  }

  private static OrgMember orgAdmin(String orgId, String userId) {
    OrgMember m = new OrgMember();
    m.setOrgId(orgId);
    m.setUserId(userId);
    m.setOrgRole("ORG_ADMIN");
    return m;
  }

  private static AppUser user(String id, String orgId, String tier) {
    return AppUser.builder()
        .id(id)
        .email(id + "@example.com")
        .orgId(orgId)
        .tier(tier)
        .status("ACTIVE")
        .build();
  }

  private static ProjectInvite invite(String projectId) {
    return ProjectInvite.builder()
        .id(UUID.randomUUID())
        .projectId(projectId)
        .email("invitee@example.com")
        .projectRole("PROJECT_VIEWER")
        .token("tok")
        .invitedByUserId("inviter")
        .status("PENDING")
        .expiresAt(Instant.now().plusSeconds(3600))
        .build();
  }

  private static AppUser actor() {
    return AppUser.builder()
        .id("actor-1")
        .email("actor@example.com")
        .firstName("Actor")
        .status("ACTIVE")
        .build();
  }
}
