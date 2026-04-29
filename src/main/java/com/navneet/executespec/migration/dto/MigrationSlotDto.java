package com.navneet.executespec.migration.dto;

import com.navneet.executespec.migration.entity.MigrationSlot;

import java.util.UUID;

public record MigrationSlotDto(
        UUID id,
        UUID recipeId,
        String name,
        String displayName,
        String type,
        boolean required,
        String defaultValue,
        String options,
        String description,
        int displayOrder
) {
    public static MigrationSlotDto from(MigrationSlot s) {
        return new MigrationSlotDto(
                s.getId(),
                s.getRecipeId(),
                s.getName(),
                s.getDisplayName(),
                s.getType() == null ? null : s.getType().name(),
                s.isRequired(),
                s.getDefaultValue(),
                s.getOptions(),
                s.getDescription(),
                s.getDisplayOrder()
        );
    }
}
