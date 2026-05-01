package com.navneet.executespec.auth.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the tier → product_surface mapping introduced
 * by V143 (product-split discriminator). V165 dropped MAX from the ladder.
 *
 * <p>Decision (audit Sprint 4, 2026-05-01):
 * <table>
 *   <tr><th>Tier</th><th>Product surface</th><th>Why</th></tr>
 *   <tr><td>FREE</td>      <td>vibe</td>        <td>Top-of-funnel consumer signup default.</td></tr>
 *   <tr><td>PRO</td>       <td>vibe</td>        <td>Grandfathered individual tier — Vibe Pro post-split.</td></tr>
 *   <tr><td>TEAM</td>      <td>executespec</td> <td>Multi-seat business product ($39/seat, BYOK or managed) — Build surface.</td></tr>
 *   <tr><td>ENTERPRISE</td><td>executespec</td> <td>B2B sales-led — Build surface.</td></tr>
 * </table>
 *
 * <p>Phase 1 (today): the mapping is consultative only — call-site enforcement
 * lands when the BillingService subscribe / upgrade paths start writing
 * {@code org.product_id}. The Plan-1 product-split work introduced the column
 * (V143) but kept the writes deferred to Phase 2 once both surfaces are live.
 *
 * <p>Phase 2 (next): wire {@link #defaultSurfaceFor} into
 * {@code BillingService.handleStripeCheckoutCompleted} (and Razorpay
 * equivalent) so a successful subscription stamps the org's product_id.
 * The {@link #ALLOWED_SURFACES_FOR_TIER} map then guards multi-surface
 * upgrades — e.g. an ENTERPRISE customer can extend access to the Vibe
 * surface via {@code app_user_product_access}, but the org's primary
 * product_id stays {@code executespec}.
 */
public final class TierProductSurfaceMapper {

    public static final String SURFACE_VIBE = "vibe";
    public static final String SURFACE_EXECUTESPEC = "executespec";
    public static final String DEFAULT_SURFACE = SURFACE_EXECUTESPEC;

    private static final Map<String, String> TIER_TO_DEFAULT_SURFACE = Map.of(
        "FREE",       SURFACE_VIBE,
        "PRO",        SURFACE_VIBE,
        "TEAM",       SURFACE_EXECUTESPEC,
        "ENTERPRISE", SURFACE_EXECUTESPEC
    );

    /**
     * Surfaces a tier may be granted access to (org-level + per-user
     * extensions). Used to validate {@code app_user_product_access} grants
     * — e.g. a TEAM org can extend a member's access to the Vibe surface
     * for personal projects, but a FREE user can't be granted Build access
     * without a subscription change.
     */
    private static final Map<String, Set<String>> ALLOWED_SURFACES_FOR_TIER = Map.of(
        "FREE",       Set.of(SURFACE_VIBE),
        "PRO",        Set.of(SURFACE_VIBE),
        "TEAM",       Set.of(SURFACE_VIBE, SURFACE_EXECUTESPEC),
        "ENTERPRISE", Set.of(SURFACE_VIBE, SURFACE_EXECUTESPEC)
    );

    private TierProductSurfaceMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * Returns the canonical product surface for a tier — the value that
     * should be stamped on {@code org.product_id} when an org first
     * subscribes to the tier.
     *
     * <p>Unknown / null tiers fall through to {@link #DEFAULT_SURFACE}
     * ({@code executespec}) so legacy rows stay on the historical surface.
     */
    public static String defaultSurfaceFor(String tier) {
        if (tier == null) return DEFAULT_SURFACE;
        return TIER_TO_DEFAULT_SURFACE.getOrDefault(tier.toUpperCase(Locale.ROOT), DEFAULT_SURFACE);
    }

    /**
     * Returns true when a user/org on {@code tier} is allowed to access
     * the named {@code surface}. Used to gate cross-surface entitlement
     * grants ({@code app_user_product_access}).
     */
    public static boolean tierCanAccessSurface(String tier, String surface) {
        if (tier == null || surface == null) return false;
        Set<String> allowed = ALLOWED_SURFACES_FOR_TIER.get(tier.toUpperCase(Locale.ROOT));
        return allowed != null && allowed.contains(surface.toLowerCase(Locale.ROOT));
    }
}
