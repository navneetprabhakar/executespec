package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.service.RunTopupService;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the V140 per-tier project + repo cap asserts on {@link TierEnforcementService}.
 * Pure repo-mock tests; no Spring context.
 */
class TierEnforcementServiceProjectLimitsTest {

  private AppUserRepository userRepo;
  private OrgRepository orgRepo;
  private TierConfigRepository tierConfigRepo;
  private ProjectRepository projectRepo;
  private ProjectRepoLinkRepository linkRepo;
  private TierEnforcementService svc;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    orgRepo = mock(OrgRepository.class);
    tierConfigRepo = mock(TierConfigRepository.class);
    projectRepo = mock(ProjectRepository.class);
    linkRepo = mock(ProjectRepoLinkRepository.class);
    svc =
        new TierEnforcementService(
            userRepo,
            orgRepo,
            tierConfigRepo,
            mock(RunTopupService.class),
            mock(BillingSubscriptionRepository.class),
            mock(ApplicationEventPublisher.class),
            projectRepo,
            linkRepo);
  }

  // ── helpers ───────────────────────────────────────────────────────────

  private AppUser user(String id, String role, String tier, String orgId) {
    AppUser u = new AppUser();
    u.setId(id);
    u.setEmail(id + "@test.com");
    u.setFirstName("Test");
    u.setGlobalRole(role);
    u.setTier(tier);
    u.setOrgId(orgId);
    return u;
  }

  private Org org(String id, String tier) {
    Org o = new Org();
    o.setId(id);
    o.setName("Test Org");
    o.setTier(tier);
    return o;
  }

  private TierConfig tierCfg(String tier, int maxProjects, int maxRepos) {
    TierConfig t = new TierConfig();
    t.setTier(tier);
    t.setDisplayName(tier);
    t.setMaxProjectsPerOrg(maxProjects);
    t.setMaxReposPerProject(maxRepos);
    return t;
  }

  private Project project(String id, String orgId) {
    return Project.builder().id(id).orgId(orgId).name("Test").createdBy("u1").build();
  }

  // ── assertCanCreateProject ───────────────────────────────────────────

  @Nested
  class CanCreateProject {

    @Test
    void underLimit_passes() {
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "FREE", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "FREE")));
      when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(tierCfg("FREE", 1, 1)));
      when(projectRepo.countByOrgIdAndStatus("org1", "ACTIVE")).thenReturn(0L);

      svc.assertCanCreateProject("u1", "org1");
      // no throw
    }

    @Test
    void atLimit_throws403() {
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "FREE", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "FREE")));
      when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(tierCfg("FREE", 1, 1)));
      when(projectRepo.countByOrgIdAndStatus("org1", "ACTIVE")).thenReturn(1L);

      assertThatThrownBy(() -> svc.assertCanCreateProject("u1", "org1"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("Tier FREE allows 1 project per org")
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void overLimit_throws403() {
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "PRO", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "PRO")));
      when(tierConfigRepo.findById("PRO")).thenReturn(Optional.of(tierCfg("PRO", 2, 2)));
      when(projectRepo.countByOrgIdAndStatus("org1", "ACTIVE")).thenReturn(5L);

      assertThatThrownBy(() -> svc.assertCanCreateProject("u1", "org1"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("Tier PRO allows 2 projects per org");
    }

    @Test
    void unlimitedTier_passes() {
      when(userRepo.findById("u1"))
          .thenReturn(Optional.of(user("u1", "USER", "ENTERPRISE", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "ENTERPRISE")));
      when(tierConfigRepo.findById("ENTERPRISE"))
          .thenReturn(Optional.of(tierCfg("ENTERPRISE", -1, -1)));

      svc.assertCanCreateProject("u1", "org1");
      verify(projectRepo, never()).countByOrgIdAndStatus(anyString(), anyString());
    }

    @Test
    void superAdmin_bypasses() {
      when(userRepo.findById("admin"))
          .thenReturn(Optional.of(user("admin", "SUPER_ADMIN", "FREE", "org1")));

      svc.assertCanCreateProject("admin", "org1");
      verify(orgRepo, never()).findById(anyString());
      verify(tierConfigRepo, never()).findById(anyString());
    }

    @Test
    void unknownUser_throws401() {
      when(userRepo.findById("ghost")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> svc.assertCanCreateProject("ghost", "org1"))
          .isInstanceOf(ResponseStatusException.class)
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noTierConfigRow_treatsAsUnlimited() {
      // Defensive: a missing tier_config row should not lock the tier
      // out. The existing limit-resolution code uses fallbacks for
      // monthly_run_limit; for the project / repo caps we read -1.
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "FREE", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "FREE")));
      when(tierConfigRepo.findById("FREE")).thenReturn(Optional.empty());

      svc.assertCanCreateProject("u1", "org1");
      // no throw
      verify(projectRepo, never()).countByOrgIdAndStatus(anyString(), anyString());
    }
  }

  // ── assertCanLinkRepoToProject ───────────────────────────────────────

  @Nested
  class CanLinkRepoToProject {

    @Test
    void underLimit_passes() {
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "FREE", "org1")));
      when(projectRepo.findById("p1")).thenReturn(Optional.of(project("p1", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "FREE")));
      when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(tierCfg("FREE", 1, 1)));
      when(linkRepo.countByProjectId("p1")).thenReturn(0L);

      svc.assertCanLinkRepoToProject("u1", "p1");
      // no throw
    }

    @Test
    void atLimit_throws403() {
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "FREE", "org1")));
      when(projectRepo.findById("p1")).thenReturn(Optional.of(project("p1", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "FREE")));
      when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(tierCfg("FREE", 1, 1)));
      when(linkRepo.countByProjectId("p1")).thenReturn(1L);

      assertThatThrownBy(() -> svc.assertCanLinkRepoToProject("u1", "p1"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("Tier FREE allows 1 repo per project")
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void teamTierWithFiveLinks_throws() {
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "TEAM", "org1")));
      when(projectRepo.findById("p1")).thenReturn(Optional.of(project("p1", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "TEAM")));
      when(tierConfigRepo.findById("TEAM")).thenReturn(Optional.of(tierCfg("TEAM", 5, 5)));
      when(linkRepo.countByProjectId("p1")).thenReturn(5L);

      assertThatThrownBy(() -> svc.assertCanLinkRepoToProject("u1", "p1"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("Tier TEAM allows 5 repos per project");
    }

    @Test
    void unlimitedTier_passes() {
      when(userRepo.findById("u1"))
          .thenReturn(Optional.of(user("u1", "USER", "ENTERPRISE", "org1")));
      when(projectRepo.findById("p1")).thenReturn(Optional.of(project("p1", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "ENTERPRISE")));
      when(tierConfigRepo.findById("ENTERPRISE"))
          .thenReturn(Optional.of(tierCfg("ENTERPRISE", -1, -1)));

      svc.assertCanLinkRepoToProject("u1", "p1");
      verify(linkRepo, never()).countByProjectId(anyString());
    }

    @Test
    void superAdmin_bypasses() {
      when(userRepo.findById("admin"))
          .thenReturn(Optional.of(user("admin", "SUPER_ADMIN", "FREE", "org1")));

      svc.assertCanLinkRepoToProject("admin", "p1");
      verify(projectRepo, never()).findById(anyString());
      verify(linkRepo, never()).countByProjectId(anyString());
    }

    @Test
    void unknownProject_throws404() {
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "PRO", "org1")));
      when(projectRepo.findById("ghost")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> svc.assertCanLinkRepoToProject("u1", "ghost"))
          .isInstanceOf(ResponseStatusException.class)
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  // ── tier resolution edge cases ───────────────────────────────────────

  @Nested
  class TierResolution {

    @Test
    void orgTierWinsOverUserTier() {
      // User tier is FREE but org tier is TEAM → TEAM cap applies.
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "FREE", "org1")));
      when(orgRepo.findById("org1")).thenReturn(Optional.of(org("org1", "TEAM")));
      when(tierConfigRepo.findById("TEAM")).thenReturn(Optional.of(tierCfg("TEAM", 10, 10)));
      when(projectRepo.countByOrgIdAndStatus("org1", "ACTIVE")).thenReturn(5L);

      svc.assertCanCreateProject("u1", "org1");
      verify(tierConfigRepo).findById("TEAM");
      verify(tierConfigRepo, never()).findById("FREE");
    }

    @Test
    void nullOrgIdSkipsEnforcement() {
      // Project.org_id is non-nullable in schema; null here is the
      // "shouldn't happen" fallback that we treat as a no-op.
      when(userRepo.findById("u1")).thenReturn(Optional.of(user("u1", "USER", "FREE", null)));
      when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(tierCfg("FREE", 1, 1)));

      svc.assertCanCreateProject("u1", null);
      verify(projectRepo, never()).countByOrgIdAndStatus(anyString(), anyString());
    }
  }
}
