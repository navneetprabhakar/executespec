package com.navneet.executespec.readymade.dto;

import java.util.List;

/**
 * R-329 — detail-page payload for a single solution. The caller- tier-dependent {@code locked}
 * state is included here too so a direct deep-link to a locked solution renders the upgrade CTA
 * without an additional tier-check round-trip.
 */
public record ReadymadeDetailDto(
    ReadymadeSolutionDto solution,
    List<ReadymadeVariantDto> variants,
    List<ReadymadeSlotDto> slots,
    List<ReadymadeDependencyDto> dependencies,
    boolean locked,
    String unlockTier) {}
