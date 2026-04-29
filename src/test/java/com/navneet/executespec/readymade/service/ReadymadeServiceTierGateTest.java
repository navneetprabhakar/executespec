package com.navneet.executespec.readymade.service;

import com.navneet.executespec.readymade.entity.ReadymadeSolution.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-329 — pins the FREE &lt; PRO &lt; TEAM &lt; ENTERPRISE tier
 * ladder used for lock-state derivation in the gallery.
 *
 * <p>Treats {@code null} caller-tier as FREE (anonymous).
 */
class ReadymadeServiceTierGateTest {

    @Test
    void freeUserSeesFreeUnlocked() {
        assertFalse(ReadymadeService.isLocked(Tier.FREE, Tier.FREE));
    }

    @Test
    void freeUserSeesProLocked() {
        assertTrue(ReadymadeService.isLocked(Tier.PRO, Tier.FREE));
    }

    @Test
    void freeUserSeesTeamLocked() {
        assertTrue(ReadymadeService.isLocked(Tier.TEAM, Tier.FREE));
    }

    @Test
    void freeUserSeesEnterpriseLocked() {
        assertTrue(ReadymadeService.isLocked(Tier.ENTERPRISE, Tier.FREE));
    }

    @Test
    void proUserSeesFreeAndProUnlocked() {
        assertFalse(ReadymadeService.isLocked(Tier.FREE, Tier.PRO));
        assertFalse(ReadymadeService.isLocked(Tier.PRO,  Tier.PRO));
        assertTrue(ReadymadeService.isLocked(Tier.TEAM,  Tier.PRO));
        assertTrue(ReadymadeService.isLocked(Tier.ENTERPRISE, Tier.PRO));
    }

    @Test
    void teamUserSeesEnterpriseLocked() {
        assertFalse(ReadymadeService.isLocked(Tier.FREE,       Tier.TEAM));
        assertFalse(ReadymadeService.isLocked(Tier.PRO,        Tier.TEAM));
        assertFalse(ReadymadeService.isLocked(Tier.TEAM,       Tier.TEAM));
        assertTrue(ReadymadeService.isLocked(Tier.ENTERPRISE,  Tier.TEAM));
    }

    @Test
    void enterpriseUserSeesEverythingUnlocked() {
        assertFalse(ReadymadeService.isLocked(Tier.FREE,        Tier.ENTERPRISE));
        assertFalse(ReadymadeService.isLocked(Tier.PRO,         Tier.ENTERPRISE));
        assertFalse(ReadymadeService.isLocked(Tier.TEAM,        Tier.ENTERPRISE));
        assertFalse(ReadymadeService.isLocked(Tier.ENTERPRISE,  Tier.ENTERPRISE));
    }

    @Test
    void nullCallerTierTreatedAsFree() {
        assertFalse(ReadymadeService.isLocked(Tier.FREE, null));
        assertTrue(ReadymadeService.isLocked(Tier.PRO, null));
    }

    @Test
    void nullRequiredTierTreatedAsUnlocked() {
        // Defensive: a row with no tier shouldn't lock anyone out.
        assertFalse(ReadymadeService.isLocked(null, Tier.FREE));
    }
}
