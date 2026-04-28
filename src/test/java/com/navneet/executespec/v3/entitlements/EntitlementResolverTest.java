package com.navneet.executespec.v3.entitlements;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.v3.credit.LifetimeCreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        AppUser u = AppUser.builder()
                .id("u1").email("u@e").firstName("E")
                .tier("FREE")             // personal tier — should be ignored
                .orgId("org1")
                .build();
        when(userRepo.findById("u1")).thenReturn(Optional.of(u));
        Org org = new Org();
        org.setId("org1");
        org.setTier("ENTERPRISE");
        when(orgRepo.findById("org1")).thenReturn(Optional.of(org));
        when(tierRepo.findById("ENTERPRISE")).thenReturn(Optional.of(tierCfg("ENTERPRISE", "Enterprise", -1)));

        EntitlementsResponse e = resolver.resolve("u1");

        assertEquals("ENTERPRISE", e.tierKey(), "org tier wins over personal tier");
        assertEquals("Enterprise", e.displayName());
        assertTrue(e.isUnlimited(), "ENTERPRISE must be unlimited");
        assertEquals(-1, e.runLimitMonthly());
        assertNull(e.softCostBudgetUsd(), "no soft budget for unlimited tiers");
        assertNull(e.hardCostCapUsd());
        assertEquals(-1, e.lifetimeRunCreditBalance(), "lifetime credits N/A on paid tiers");
    }

    @Test
    void free_user_resolves_lifetime_balance_from_credit_service() {
        AppUser u = AppUser.builder()
                .id("u2").email("u@f").firstName("F")
                .tier("FREE").orgId(null)
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
        AppUser u = AppUser.builder()
                .id("u3").email("u@m").firstName("M")
                .tier("MAX").orgId(null)
                .build();
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

    @Test
    void normalize_handles_null_and_blank() {
        assertEquals("FREE", EntitlementResolver.normalize(null));
        assertEquals("FREE", EntitlementResolver.normalize(""));
        assertEquals("PRO", EntitlementResolver.normalize("pro"));
        assertEquals("TEAM", EntitlementResolver.normalize("max"));
    }
}
