package com.navneet.executespec.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the V143 (+ V165 MAX-drop) tier → product_surface mapping.
 *
 * Pins the decision matrix so a tier-rename or surface-rename has to
 * either (a) update this test or (b) explicitly justify the policy
 * change in code review.
 */
class TierProductSurfaceMapperTest {

    @Test
    void freeAndProDefaultToVibeSurface() {
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("FREE"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_VIBE);
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("PRO"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_VIBE);
    }

    @Test
    void teamAndEnterpriseDefaultToExecutespecSurface() {
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("TEAM"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_EXECUTESPEC);
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("ENTERPRISE"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_EXECUTESPEC);
    }

    @Test
    void caseInsensitiveLookup() {
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("team"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_EXECUTESPEC);
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("Free"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_VIBE);
    }

    @Test
    void unknownAndNullTiersFallBackToExecutespecDefault() {
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor(null))
            .isEqualTo(TierProductSurfaceMapper.DEFAULT_SURFACE);
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("UNKNOWN_TIER"))
            .isEqualTo(TierProductSurfaceMapper.DEFAULT_SURFACE);
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor(""))
            .isEqualTo(TierProductSurfaceMapper.DEFAULT_SURFACE);
    }

    @Test
    void droppedMaxTierFallsBackToDefault() {
        // V165 — MAX was deprecated and dropped from the mapping. Stale
        // references (e.g. unmigrated session caches, telemetry replays)
        // fall through to the default surface rather than throwing.
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("MAX"))
            .isEqualTo(TierProductSurfaceMapper.DEFAULT_SURFACE);
    }

    @Test
    void teamCanAccessBothSurfaces() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("TEAM", "vibe")).isTrue();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("TEAM", "executespec")).isTrue();
    }

    @Test
    void enterpriseCanAccessBothSurfaces() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("ENTERPRISE", "vibe")).isTrue();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("ENTERPRISE", "executespec")).isTrue();
    }

    @Test
    void freeAndProCannotAccessBuildSurface() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("FREE", "executespec")).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("PRO", "executespec")).isFalse();
    }

    @Test
    void freeAndProCanAccessVibeSurface() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("FREE", "vibe")).isTrue();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("PRO", "vibe")).isTrue();
    }

    @Test
    void droppedMaxTierDeniesEverySurface() {
        // V165 — MAX is no longer in ALLOWED_SURFACES_FOR_TIER, so the
        // safe-default-deny path applies. Any caller still passing "MAX"
        // gets a clean false instead of crashing on a missing-key NPE.
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("MAX", "vibe")).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("MAX", "executespec")).isFalse();
    }

    @Test
    void unknownSurfaceOrNullDeniesAccess() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("TEAM", "rogue-surface")).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("TEAM", null)).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface(null, "vibe")).isFalse();
    }
}
