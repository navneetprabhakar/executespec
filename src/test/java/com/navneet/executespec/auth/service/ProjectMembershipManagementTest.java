package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.dto.ProjectMemberCapabilitiesDto;
import com.navneet.executespec.auth.dto.ProjectMemberDto;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** RR-1507 / RR-1508 / RR-1510 / RR-1513 — project membership administration. */
class ProjectMembershipManagementTest {

  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final ProjectMemberRepository memberRepo = mock(ProjectMemberRepository.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final OrgMemberRepository orgMemberRepo = mock(OrgMemberRepository.class);
  private final ActivityAuditService auditService = mock(ActivityAuditService.class);
  private final TierEnforcementService tierEnforcement = mock(TierEnforcementService.class);
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private ProjectService service;

  @BeforeEach
  void setUp() {
    service =
        new ProjectService(
            projectRepo,
            memberRepo,
            userRepo,
            orgMemberRepo,
            auditService,
            mock(EventTrackingService.class),
            tierEnforcement,
            mock(com.navneet.executespec.router.ProviderReadinessService.class),
            orgRepo);

    Project project = new Project();
    project.setId("p1");
    project.setOrgId("host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(project));

    // "admin" is a PROJECT_ADMIN member of p1 and can administer membership.
    when(userRepo.findById("admin")).thenReturn(Optional.of(user("admin", "host-org")));
    when(memberRepo.findByProjectIdAndUserId("p1", "admin"))
        .thenReturn(Optional.of(member("admin", "PROJECT_ADMIN", false)));
  }

  @Test
  void promotesAndDemotesAnExistingMember() {
    when(memberRepo.findByProjectIdAndUserId("p1", "viewer"))
        .thenReturn(Optional.of(member("viewer", "PROJECT_VIEWER", true)));

    service.updateMemberRole("p1", "viewer", "PROJECT_ADMIN", "admin");

    verify(memberRepo)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                m -> "PROJECT_ADMIN".equals(m.getProjectRole()) && "viewer".equals(m.getUserId())));
  }

  @Test
  void refusesToDemoteTheOnlyProjectAdmin() {
    when(memberRepo.findByProjectIdAndUserId("p1", "solo"))
        .thenReturn(Optional.of(member("solo", "PROJECT_ADMIN", false)));
    when(memberRepo.countByProjectIdAndProjectRole("p1", "PROJECT_ADMIN")).thenReturn(1);

    assertThatThrownBy(() -> service.updateMemberRole("p1", "solo", "PROJECT_VIEWER", "admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("only project admin");
    verify(memberRepo, never()).save(any(ProjectMember.class));
  }

  @Test
  void rejectsAnInvalidRole() {
    assertThatThrownBy(() -> service.updateMemberRole("p1", "viewer", "OWNER", "admin"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void addMemberConflictsInsteadOfSilentlyNoOpping() {
    when(userRepo.findById("existing")).thenReturn(Optional.of(user("existing", "host-org")));
    when(memberRepo.existsByProjectIdAndUserId("p1", "existing")).thenReturn(true);

    assertThatThrownBy(() -> service.addMember("p1", "existing", "PROJECT_ADMIN", "admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("already a member");
    verify(memberRepo, never()).save(any(ProjectMember.class));
  }

  @Test
  void listMembersDistinguishesExternalCollaborators() {
    when(memberRepo.findByProjectId("p1"))
        .thenReturn(
            List.of(
                member("employee", "PROJECT_DEVELOPER", false),
                member("outsider", "PROJECT_VIEWER", true)));
    when(userRepo.findById("employee")).thenReturn(Optional.of(user("employee", "host-org")));
    when(userRepo.findById("outsider")).thenReturn(Optional.of(user("outsider", "other-org")));
    Org other = new Org();
    other.setId("other-org");
    other.setName("Vendor Ltd");
    when(orgRepo.findById("other-org")).thenReturn(Optional.of(other));
    Org host = new Org();
    host.setId("host-org");
    host.setName("Host Inc");
    when(orgRepo.findById("host-org")).thenReturn(Optional.of(host));

    List<ProjectMemberDto> members = service.listMembers("p1");

    assertThat(members).hasSize(2);
    assertThat(members.get(0).external()).isFalse();
    assertThat(members.get(0).homeOrgName()).isEqualTo("Host Inc");
    assertThat(members.get(1).external()).isTrue();
    assertThat(members.get(1).homeOrgName()).isEqualTo("Vendor Ltd");
  }

  @Test
  void capabilitiesSeparateMembershipAdminFromShareLinkAdmin() {
    when(userRepo.findById("dev")).thenReturn(Optional.of(user("dev", "host-org")));
    when(memberRepo.findByProjectIdAndUserId("p1", "dev"))
        .thenReturn(Optional.of(member("dev", "PROJECT_DEVELOPER", false)));
    when(tierEnforcement.resolveUserTier("dev")).thenReturn("PRO");

    ProjectMemberCapabilitiesDto caps = service.memberCapabilities("p1", "dev");

    assertThat(caps.canManageMembership()).isFalse();
    assertThat(caps.canManageShareLinks()).isTrue();
    // Not an ORG_ADMIN of their own org → the invite blocker is ROLE, not the org flag or tier.
    assertThat(caps.canInviteExternal()).isFalse();
    assertThat(caps.inviteBlockedReason()).isEqualTo("ROLE");
  }

  @Test
  void capabilitiesReportTierAsTheInviteBlockerOnFree() {
    when(userRepo.findById("dev")).thenReturn(Optional.of(user("dev", "host-org")));
    when(memberRepo.findByProjectIdAndUserId("p1", "dev"))
        .thenReturn(Optional.of(member("dev", "PROJECT_ADMIN", false)));
    when(tierEnforcement.resolveUserTier("dev")).thenReturn("FREE");

    ProjectMemberCapabilitiesDto caps = service.memberCapabilities("p1", "dev");

    assertThat(caps.canManageMembership()).isTrue();
    assertThat(caps.inviteBlockedReason()).isEqualTo("TIER");
  }

  @Test
  void capabilitiesReportOrgFlagAsTheInviteBlockerForAPaidOrgAdmin() {
    when(userRepo.findById("owner")).thenReturn(Optional.of(user("owner", "host-org")));
    when(memberRepo.findByProjectIdAndUserId("p1", "owner"))
        .thenReturn(Optional.of(member("owner", "PROJECT_ADMIN", false)));
    when(tierEnforcement.resolveUserTier("owner")).thenReturn("PRO_PLUS");
    when(orgMemberRepo.findByOrgIdAndUserId("host-org", "owner"))
        .thenReturn(
            Optional.of(
                OrgMember.builder()
                    .id("om")
                    .orgId("host-org")
                    .userId("owner")
                    .orgRole("ORG_ADMIN")
                    .build()));
    Org host = new Org();
    host.setId("host-org");
    host.setExternalSharingEnabled(false);
    when(orgRepo.findById("host-org")).thenReturn(Optional.of(host));

    ProjectMemberCapabilitiesDto caps = service.memberCapabilities("p1", "owner");

    assertThat(caps.inviteBlockedReason()).isEqualTo("ORG_EXTERNAL_SHARING_DISABLED");
  }

  /** RR-1508 — paid-tier truth is derived from the tier ladder, not a hand-copied name set. */
  @Test
  void paidTierPredicateCoversTheWholeV253Ladder() {
    assertThat(TierEnforcementService.isPaidTier("PRO")).isTrue();
    assertThat(TierEnforcementService.isPaidTier("PRO_PLUS")).isTrue();
    assertThat(TierEnforcementService.isPaidTier("BYOK")).isTrue();
    assertThat(TierEnforcementService.isPaidTier("TEAM")).isTrue();
    assertThat(TierEnforcementService.isPaidTier("ENTERPRISE")).isTrue();
    // Retired alias still normalizes onto a paid tier rather than silently failing open elsewhere.
    assertThat(TierEnforcementService.isPaidTier("MAX")).isTrue();
    assertThat(TierEnforcementService.isPaidTier("FREE")).isFalse();
    assertThat(TierEnforcementService.isPaidTier(null)).isFalse();
    // A tier added to the ladder without a capability decision must fail closed here.
    assertThat(TierEnforcementService.isPaidTier("SOME_FUTURE_TIER")).isFalse();
  }

  private static AppUser user(String id, String orgId) {
    AppUser u = new AppUser();
    u.setId(id);
    u.setEmail(id + "@example.com");
    u.setOrgId(orgId);
    return u;
  }

  private static ProjectMember member(String userId, String role, boolean external) {
    return ProjectMember.builder()
        .id("pm-" + userId)
        .projectId("p1")
        .userId(userId)
        .projectRole(role)
        .external(external)
        .build();
  }
}
