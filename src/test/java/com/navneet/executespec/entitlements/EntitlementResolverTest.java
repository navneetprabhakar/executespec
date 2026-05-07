package com.navneet.executespec.entitlements;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.credit.LifetimeCreditService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntitlementResolverTest {

  private AppUserRepository userRepo;
  private OrgRepository orgRepo;
  private TierConfigRepository tierRepo;
  private LifetimeCreditService credit;
  private EntitlementResolver resolver;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    orgRepo = mock(OrgRepository.class);
    tierRepo = mock(TierConfigRepository.class);
    credit = mock(LifetimeCreditService.class);
    resolver = new EntitlementResolver(userRepo, orgRepo, tierRepo, credit);
  }

  private TierConfig tierCfg(String key, String display, int monthlyLimit) {
    TierConfig c = new TierConfig();
    c.setTier(key);
    c.setDisplayName(display);
    c.setMonthlyRunLimit(monthlyLimit);
    c.setMaxProjectsPerOrg(-1);
    c.setMaxReposPerProject(-1);
    return c;
  }

  @Test
  void enterprise_org_user_resolves_unlimited() {
    AppUser u =
        AppUser.builder()
            .id("u1")
            .email("u@e")
            .firstName("E")
            .tier("FREE") // personal tier
            .orgId("org1")
            .build();
    when(userRepo.findById("u1")).thenReturn(Optional.of(u));
    Org org = new Org();
    org.setId("org1");
    org.setTier("ENTERPRISE");
    when(orgRepo.findById("org1")).thenReturn(Optional.of(org));
    when(tierRepo.findById("ENTERPRISE"))
        .thenReturn(Optional.of(tierCfg("ENTERPRISE", "Enterprise", -1)));

    EntitlementsResponse e = resolver.resolve("u1");

    assertEquals(
        "ENTERPRISE",
        e.tierKey(),
        "ENTERPRISE org outranks FREE personal — higher of the two wins");
    assertEquals("Enterprise", e.displayName());
    assertTrue(e.isUnlimited(), "ENTERPRISE must be unlimited");
    assertEquals(-1, e.runLimitMonthly());
    assertNull(e.softCostBudgetUsd(), "no soft budget for unlimited tiers");
    assertNull(e.hardCostCapUsd());
    assertEquals(-1, e.lifetimeRunCreditBalance(), "lifetime credits N/A on paid tiers");
  }

  @Test
  void free_user_resolves_lifetime_balance_from_credit_service() {
    AppUser u =
        AppUser.builder()
            .id("u2")
            .email("u@f")
            .firstName("F")
            .tier("FREE")
            .orgId(null)
            .lifetimeRunCreditCap(25)
            .build();
    when(userRepo.findById("u2")).thenReturn(Optional.of(u));
    when(tierRepo.findById("FREE")).thenReturn(Optional.of(tierCfg("FREE", "Free", 5)));
    when(credit.currentBalance("u2")).thenReturn(3);

    EntitlementsResponse e = resolver.resolve("u2");

    assertEquals("FREE", e.tierKey());
    assertEquals("Free", e.displayName());
    assertFalse(e.isUnlimited());
    assertEquals(5, e.runLimitMonthly());
    assertEquals(10.0, e.softCostBudgetUsd(), 0.001, "5 runs × $2 baseline");
    assertEquals(15.0, e.hardCostCapUsd(), 0.001, "soft × 1.5");
    assertEquals(3, e.lifetimeRunCreditBalance(), "balance comes from LifetimeCreditService");
    assertEquals(25, e.lifetimeRunCreditCap());
  }

  @Test
  void max_legacy_collapses_to_team() {
    // V141 rename path: lingering MAX rows must surface as Team.
    AppUser u =
        AppUser.builder().id("u3").email("u@m").firstName("M").tier("MAX").orgId(null).build();
    when(userRepo.findById("u3")).thenReturn(Optional.of(u));
    when(tierRepo.findById("TEAM")).thenReturn(Optional.empty()); // simulate missing row

    EntitlementsResponse e = resolver.resolve("u3");

    assertEquals("TEAM", e.tierKey(), "MAX must normalise to TEAM");
    assertEquals("Team", e.displayName(), "fallback display name");
    assertTrue(e.isUnlimited(), "TEAM with no row defaults to unlimited");
  }

  @Test
  void unknown_user_throws() {
    when(userRepo.findById("ghost")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> resolver.resolve("ghost"));
  }

  /**
   * R-2026-05-01 / Task 1 + F-14: a PRO user whose backing org row still says tier=FREE (a
   * bootstrapped personal org from V162 that was never upgraded, an operator-promoted account, or
   * an expired-but-not-demoted subscription) must surface as PRO — not FREE — at the v3 surface.
   * This pins the higher-rank semantic plus the existing MAX_VALUE clamp.
   */
  @Test
  void pro_user_with_free_org_resolves_to_pro_not_free() {
    AppUser u =
        AppUser.builder()
            .id("u4")
            .email("u@p")
            .firstName("P")
            .tier("PRO") // user is paid …
            .orgId("org4")
            .lifetimeRunCreditCap(25)
            .build();
    when(userRepo.findById("u4")).thenReturn(Optional.of(u));
    Org freeOrg = new Org();
    freeOrg.setId("org4");
    freeOrg.setTier("FREE"); // … but backing org is FREE
    when(orgRepo.findById("org4")).thenReturn(Optional.of(freeOrg));
    when(tierRepo.findById("PRO")).thenReturn(Optional.of(tierCfg("PRO", "Pro", 20)));
    when(credit.currentBalance("u4")).thenReturn(Integer.MAX_VALUE);

    EntitlementsResponse e = resolver.resolve("u4");

    assertEquals("PRO", e.tierKey(), "PRO outranks FREE — user.tier wins when org.tier is lower");
    assertEquals("Pro", e.displayName());
    assertEquals(20, e.runLimitMonthly(), "PRO tier_config monthlyRunLimit");
    assertEquals(
        -1, e.lifetimeRunCreditBalance(), "paid user must surface as -1, not Integer.MAX_VALUE");
    assertEquals(-1, e.lifetimeRunCreditCap(), "paid user must surface no lifetime cap");
    verify(credit, never()).currentBalance("u4");
  }

  /**
   * F-14 regression — TEAM user whose personal org is still FREE. This is the exact scenario the
   * 2026-05-01 audit found: /api/v3/entitlements returned `tierKey: FREE` for revalidate-team@
   * while /api/auth/me said TEAM, and the V3 billing page footer leaked "Tier: FREE · run limit:
   * 10". After the higher-rank fix, the resolver returns TEAM end-to-end.
   */
  @Test
  void team_user_with_free_org_resolves_to_team() {
    AppUser u =
        AppUser.builder()
            .id("u-team")
            .email("revalidate-team@test.local")
            .firstName("R")
            .tier("TEAM")
            .orgId("org-team-personal")
            .build();
    when(userRepo.findById("u-team")).thenReturn(Optional.of(u));
    Org personalOrg = new Org();
    personalOrg.setId("org-team-personal");
    personalOrg.setTier("FREE");
    when(orgRepo.findById("org-team-personal")).thenReturn(Optional.of(personalOrg));
    when(tierRepo.findById("TEAM")).thenReturn(Optional.of(tierCfg("TEAM", "Team", -1)));

    EntitlementsResponse e = resolver.resolve("u-team");

    assertEquals("TEAM", e.tierKey(), "TEAM personal tier outranks FREE org");
    assertEquals("Team", e.displayName());
    assertTrue(e.isUnlimited(), "TEAM is unlimited");
  }

  /** F-14 regression — ENTERPRISE personal user with no org. */
  @Test
  void enterprise_user_with_no_org_resolves_to_enterprise() {
    AppUser u =
        AppUser.builder()
            .id("u-ent-solo")
            .email("solo@e")
            .firstName("E")
            .tier("ENTERPRISE")
            .orgId(null)
            .build();
    when(userRepo.findById("u-ent-solo")).thenReturn(Optional.of(u));
    when(tierRepo.findById("ENTERPRISE"))
        .thenReturn(Optional.of(tierCfg("ENTERPRISE", "Enterprise", -1)));

    EntitlementsResponse e = resolver.resolve("u-ent-solo");

    assertEquals("ENTERPRISE", e.tierKey());
    assertTrue(e.isUnlimited());
  }

  /** F-14 — when org tier is HIGHER than user tier, org still wins. */
  @Test
  void free_user_with_enterprise_org_resolves_to_enterprise() {
    AppUser u =
        AppUser.builder()
            .id("u-free-in-ent")
            .email("u@org-ent")
            .firstName("F")
            .tier("FREE")
            .orgId("ent-org")
            .build();
    when(userRepo.findById("u-free-in-ent")).thenReturn(Optional.of(u));
    Org entOrg = new Org();
    entOrg.setId("ent-org");
    entOrg.setTier("ENTERPRISE");
    when(orgRepo.findById("ent-org")).thenReturn(Optional.of(entOrg));
    when(tierRepo.findById("ENTERPRISE"))
        .thenReturn(Optional.of(tierCfg("ENTERPRISE", "Enterprise", -1)));

    EntitlementsResponse e = resolver.resolve("u-free-in-ent");

    assertEquals(
        "ENTERPRISE",
        e.tierKey(),
        "ENTERPRISE org outranks FREE personal — org-side promotion still works");
    assertTrue(e.isUnlimited());
  }

  /**
   * Defence-in-depth: if a future regression makes the resolver call currentBalance() on a
   * FREE-marked user and the credit service returns the sentinel, the clamp must still pin the
   * response to -1.
   */
  @Test
  void clamp_catches_sentinel_balance_from_credit_service() {
    AppUser u =
        AppUser.builder()
            .id("u5")
            .email("u@s")
            .firstName("S")
            .tier("FREE")
            .orgId(null)
            .lifetimeRunCreditCap(25)
            .build();
    when(userRepo.findById("u5")).thenReturn(Optional.of(u));
    when(tierRepo.findById("FREE")).thenReturn(Optional.of(tierCfg("FREE", "Free", 10)));
    when(credit.currentBalance("u5")).thenReturn(Integer.MAX_VALUE);

    EntitlementsResponse e = resolver.resolve("u5");

    assertEquals(
        -1, e.lifetimeRunCreditBalance(), "MAX_VALUE from credit service must clamp to -1");
  }

  @Test
  void normalize_handles_null_and_blank() {
    assertEquals("FREE", EntitlementResolver.normalize(null));
    assertEquals("FREE", EntitlementResolver.normalize(""));
    assertEquals("PRO", EntitlementResolver.normalize("pro"));
    assertEquals("TEAM", EntitlementResolver.normalize("max"));
  }
}
