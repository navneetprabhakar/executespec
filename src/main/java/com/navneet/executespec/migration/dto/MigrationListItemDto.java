package com.navneet.executespec.migration.dto;

import com.navneet.executespec.migration.entity.MigrationRecipe;

/**
 * R-338 — gallery card row for the migrations library, with
 * caller-tier-derived lock state.
 */
public record MigrationListItemDto(
        MigrationRecipeDto recipe,
        boolean locked,
        String unlockTier
) {
    public static MigrationListItemDto unlocked(MigrationRecipe r) {
        return new MigrationListItemDto(MigrationRecipeDto.from(r), false, null);
    }

    public static MigrationListItemDto locked(MigrationRecipe r, MigrationRecipe.Tier requiredTier) {
        return new MigrationListItemDto(
            MigrationRecipeDto.from(r), true, requiredTier.name()
        );
    }
}
