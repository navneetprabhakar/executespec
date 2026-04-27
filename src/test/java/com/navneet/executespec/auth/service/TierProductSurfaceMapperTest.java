package com.navneet.executespec.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the V143 + V144 tier → product_surface mapping.
 *
 * Pins the decision matrix so a tier-rename or surface-rename has to
 * either (a) update this test or (b) explicitly justify the policy
 * change in code review.
 */
class TierProductSurfaceMapperTest {

    @Test
    void freeProAndMaxDefaultToVibeSurface() {
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("FREE"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_VIBE);
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("PRO"))
            .isEqualTo(TierProductSurfaceMapper.SURFACE_VIBE);
        assertThat(TierProductSurfaceMapper.defaultSurfaceFor("MAX"))
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
    void freeProAndMaxCannotAccessBuildSurface() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("FREE", "executespec")).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("PRO", "executespec")).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("MAX", "executespec")).isFalse();
    }

    @Test
    void freeProAndMaxCanAccessVibeSurface() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("FREE", "vibe")).isTrue();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("PRO", "vibe")).isTrue();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("MAX", "vibe")).isTrue();
    }

    @Test
    void unknownSurfaceOrNullDeniesAccess() {
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("TEAM", "rogue-surface")).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface("TEAM", null)).isFalse();
        assertThat(TierProductSurfaceMapper.tierCanAccessSurface(null, "vibe")).isFalse();
    }
}
