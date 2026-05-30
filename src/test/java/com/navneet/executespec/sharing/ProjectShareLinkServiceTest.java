package com.navneet.executespec.sharing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.repository.ProjectShareLinkRepository;
import com.navneet.executespec.sharing.service.ProjectShareLinkService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * V175 — pins the ProjectShareLinkService tier-gate, surface-scope validation, and revoke/expire
 * lifecycle. Mocks-only; no DB.
 */
class ProjectShareLinkServiceTest {

  private ProjectShareLinkRepository linkRepo;
  private TierConfigRepository tierRepo;
  private com.navneet.executespec.state.repository.PipelineRunRepository runRepo;
  private com.navneet.executespec.codereview.repository.CodeReviewScanRepository scanRepo;
  private com.navneet.executespec.prreview.repository.PrReviewRepository prReviewRepo;
  private ProjectShareLinkService service;

  @BeforeEach
  void setUp() {
    linkRepo = mock(ProjectShareLinkRepository.class);
    tierRepo = mock(TierConfigRepository.class);
    runRepo = mock(com.navneet.executespec.state.repository.PipelineRunRepository.class);
    scanRepo = mock(com.navneet.executespec.codereview.repository.CodeReviewScanRepository.class);
    prReviewRepo = mock(com.navneet.executespec.prreview.repository.PrReviewRepository.class);
    service = new ProjectShareLinkService(linkRepo, tierRepo, runRepo, scanRepo, prReviewRepo);
    lenient().when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(linkRepo.countActiveByProject(anyString(), any())).thenReturn(0L);
  }

  @Test
  void free_tier_at_cap_blocks_create() {
    AppUser free = userOnTier("u-free", "FREE");
    lenient().when(tierRepo.findById("FREE")).thenReturn(Optional.of(tier("FREE", 3)));
    when(linkRepo.countActiveByProject(anyString(), any())).thenReturn(3L);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createLink(
                    "p1", ProjectShareLink.Surface.PROJECT_OVERVIEW, null, "demo", null, free));
    assertEquals(429, ex.getStatusCode().value());
  }

  @Test
  void enterprise_tier_with_unlimited_cap_passes_at_high_count() {
    AppUser ent = userOnTier("u-ent", "ENTERPRISE");
    lenient().when(tierRepo.findById("ENTERPRISE")).thenReturn(Optional.of(tier("ENTERPRISE", -1)));
    when(linkRepo.countActiveByProject(anyString(), any())).thenReturn(10_000L);
    ProjectShareLink link =
        service.createLink(
            "p1", ProjectShareLink.Surface.PROJECT_OVERVIEW, null, "demo", null, ent);
    assertNotNull(link);
    assertEquals(ProjectShareLink.Surface.PROJECT_OVERVIEW, link.getSurface());
    assertNotNull(link.getToken());
  }

  @Test
  void surface_requiring_scope_id_rejects_when_blank() {
    AppUser pro = userOnTier("u-pro", "PRO");
    lenient().when(tierRepo.findById("PRO")).thenReturn(Optional.of(tier("PRO", 25)));
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createLink(
                    "p1", ProjectShareLink.Surface.RUN_DETAIL, null, "x", null, pro));
    assertEquals(400, ex.getStatusCode().value());
    assertTrue(ex.getReason().contains("scopeId"));
  }

  @Test
  void surface_requiring_scope_id_passes_with_scope() {
    AppUser pro = userOnTier("u-pro", "PRO");
    lenient().when(tierRepo.findById("PRO")).thenReturn(Optional.of(tier("PRO", 25)));
    // IDOR fix (RL-2026-05-036): createLink now verifies the scope belongs to the project.
    // Run "run-abc" must resolve to project "p1" for this same-project link to be allowed.
    when(runRepo.findById("run-abc"))
        .thenReturn(
            Optional.of(
                com.navneet.executespec.state.entity.PipelineRun.builder()
                    .projectId("p1")
                    .build()));
    ProjectShareLink link =
        service.createLink(
            "p1", ProjectShareLink.Surface.RUN_DETAIL, "run-abc", "run share", null, pro);
    assertEquals("run-abc", link.getScopeId());
  }

  @Test
  void resolve_active_returns_410_for_revoked() {
    ProjectShareLink revoked = newLink();
    revoked.setRevokedAt(Instant.now());
    when(linkRepo.findByToken("rev-tok")).thenReturn(Optional.of(revoked));
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.resolveActive("rev-tok"));
    assertEquals(410, ex.getStatusCode().value());
  }

  @Test
  void resolve_active_returns_410_for_expired() {
    ProjectShareLink expired = newLink();
    expired.setExpiresAt(Instant.now().minusSeconds(60));
    when(linkRepo.findByToken("exp-tok")).thenReturn(Optional.of(expired));
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.resolveActive("exp-tok"));
    assertEquals(410, ex.getStatusCode().value());
  }

  @Test
  void revoke_is_idempotent() {
    ProjectShareLink already = newLink();
    already.setRevokedAt(Instant.now().minusSeconds(60));
    when(linkRepo.findById(already.getId())).thenReturn(Optional.of(already));
    AppUser actor = userOnTier("admin", "ENTERPRISE");
    assertDoesNotThrow(() -> service.revoke(already.getId(), actor));
  }

  private static AppUser userOnTier(String id, String tier) {
    AppUser u = new AppUser();
    u.setId(id);
    u.setTier(tier);
    return u;
  }

  private static TierConfig tier(String tier, int cap) {
    TierConfig t = new TierConfig();
    t.setTier(tier);
    t.setDisplayName(tier);
    t.setMonthlyRunLimit(-1);
    t.setMaxOrgMembers(-1);
    t.setMaxChatMessages(10);
    t.setConcurrentRunLimit(1);
    t.setMaxApplySuggestions(2);
    t.setMaxPreviewStarts(2);
    t.setMaxRunAutoRetries(0);
    t.setCanDeploy(false);
    t.setMaxDeployments(0);
    t.setExecutionViewEnabled(false);
    t.setMaxProjectsPerOrg(-1);
    t.setMaxReposPerProject(-1);
    t.setLifecycleState("LISTED");
    t.setShareLinkActiveCap(cap);
    return t;
  }

  private static ProjectShareLink newLink() {
    return ProjectShareLink.builder()
        .id(UUID.randomUUID())
        .projectId("p1")
        .token("token-" + UUID.randomUUID())
        .surface(ProjectShareLink.Surface.PROJECT_OVERVIEW)
        .createdByUserId("u")
        .createdAt(Instant.now())
        .build();
  }
}
