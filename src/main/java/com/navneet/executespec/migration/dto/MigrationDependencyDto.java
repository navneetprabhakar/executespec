package com.navneet.executespec.migration.dto;

import com.navneet.executespec.migration.entity.MigrationDependency;

import java.util.UUID;

public record MigrationDependencyDto(
        UUID recipeId,
        UUID dependsOnId,
        String kind
) {
    public static MigrationDependencyDto from(MigrationDependency d) {
        return new MigrationDependencyDto(
                d.getRecipeId(),
                d.getDependsOnId(),
                d.getKind() == null ? null : d.getKind().name()
        );
    }
}
