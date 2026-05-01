package com.navneet.executespec.migration.dto;

import java.util.List;

/** R-338 — detail-page payload for a single migration recipe. */
public record MigrationDetailDto(
    MigrationRecipeDto recipe,
    List<MigrationVariantDto> variants,
    List<MigrationSlotDto> slots,
    List<MigrationDependencyDto> dependencies,
    boolean locked,
    String unlockTier) {}
