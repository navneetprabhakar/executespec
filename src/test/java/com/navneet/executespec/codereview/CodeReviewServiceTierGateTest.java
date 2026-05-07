package com.navneet.executespec.codereview;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.codereview.repository.CodeReviewIssueRepository;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.cost.RunCostTracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the tier-gate semantics for the v2.6.4 Code Review Agent. FREE → blocked. PRO with remaining
 * quota → pass. PRO at quota cap → blocked. TEAM with quota=-1 → unlimited pass.
 *
 * <p>Mocks every external dep — the assertion is purely about the if-tree in {@link
 * CodeReviewService#assertTierAndQuota(AppUser)}. No DB, no LLM, no network.
 */
class CodeReviewServiceTierGateTest {

  private TierConfigRepository tierConfigRepo;
  private CodeReviewScanRepository scanRepo;
  private CodeReviewService service;

  @BeforeEach
  void setUp() {
    tierConfigRepo = mock(TierConfigRepository.class);
    scanRepo = mock(CodeReviewScanRepository.class);
    service =
        new CodeReviewService(
            scanRepo,
            mock(CodeReviewIssueRepository.class),
            mock(SemgrepRunner.class),
            mock(CodeReviewAgent.class),
            mock(WorkspaceInitService.class),
            mock(RunCostTracker.class),
            mock(SseBroadcaster.class),
            mock(ProjectRepoRepository.class),
            mock(GitHubRepoService.class),
            tierConfigRepo);

    // Default: per-user count of 0 unless a test overrides.
    lenient()
        .when(
            scanRepo.countByUserSince(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(0L);
  }

  @Test
  void anonymous_user_blocked() {
    assertThrows(
        CodeReviewService.TierBlockedException.class, () -> service.assertTierAndQuota(null));
  }

  @Test
  void free_user_blocked() {
    AppUser user = userOnTier("u1", "FREE");
    TierConfig free = tierConfig("FREE", false, false, 0);
    lenient().when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(free));
    assertThrows(
        CodeReviewService.TierBlockedException.class, () -> service.assertTierAndQuota(user));
  }

  @Test
  void pro_user_with_remaining_quota_passes() {
    AppUser user = userOnTier("u-pro", "PRO");
    TierConfig pro = tierConfig("PRO", true, true, 4);
    lenient().when(tierConfigRepo.findById("PRO")).thenReturn(Optional.of(pro));
    when(scanRepo.countByUserSince(
            org.mockito.ArgumentMatchers.eq("u-pro"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(2L);
    assertDoesNotThrow(() -> service.assertTierAndQuota(user));
  }

  @Test
  void pro_user_at_quota_cap_blocked_with_429_signal() {
    AppUser user = userOnTier("u-pro-cap", "PRO");
    TierConfig pro = tierConfig("PRO", true, true, 4);
    lenient().when(tierConfigRepo.findById("PRO")).thenReturn(Optional.of(pro));
    when(scanRepo.countByUserSince(
            org.mockito.ArgumentMatchers.eq("u-pro-cap"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(4L);
    assertThrows(
        CodeReviewService.QuotaExhaustedException.class, () -> service.assertTierAndQuota(user));
  }

  @Test
  void team_user_unlimited_quota_passes_at_high_count() {
    AppUser user = userOnTier("u-team", "TEAM");
    TierConfig team = tierConfig("TEAM", true, true, -1);
    lenient().when(tierConfigRepo.findById("TEAM")).thenReturn(Optional.of(team));
    // Quota = -1 means "skip the count check entirely" — even a million scans should pass.
    when(scanRepo.countByUserSince(
            org.mockito.ArgumentMatchers.eq("u-team"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(1_000_000L);
    assertDoesNotThrow(() -> service.assertTierAndQuota(user));
  }

  @Test
  void enterprise_user_with_disabled_flag_blocked() {
    // Defensive: even on ENTERPRISE, if an operator flips the feature flag off via the
    // tier_config column, the gate must respect that.
    AppUser user = userOnTier("u-ent", "ENTERPRISE");
    TierConfig ent = tierConfig("ENTERPRISE", true, false, -1);
    lenient().when(tierConfigRepo.findById("ENTERPRISE")).thenReturn(Optional.of(ent));
    assertThrows(
        CodeReviewService.TierBlockedException.class, () -> service.assertTierAndQuota(user));
  }

  @Test
  void unknown_tier_blocked() {
    // tier_config row missing entirely — fail closed, never crash.
    AppUser user = userOnTier("u-mystery", "GHOST");
    lenient().when(tierConfigRepo.findById("GHOST")).thenReturn(Optional.empty());
    assertThrows(
        CodeReviewService.TierBlockedException.class, () -> service.assertTierAndQuota(user));
  }

  private static AppUser userOnTier(String userId, String tier) {
    AppUser u = new AppUser();
    u.setId(userId);
    u.setTier(tier);
    u.setCreatedAt(Instant.now());
    return u;
  }

  private static TierConfig tierConfig(
      String tier, boolean prReview, boolean codeReview, int codeQuota) {
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
    t.setPrReviewEnabled(prReview);
    t.setCodeReviewEnabled(codeReview);
    t.setPrReviewMonthlyQuota(prReview ? 10 : 0);
    t.setCodeReviewMonthlyQuota(codeQuota);
    return t;
  }
}
