package com.navneet.executespec.migration.repository;

import com.navneet.executespec.migration.entity.MigrationDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MigrationDependencyRepository
        extends JpaRepository<MigrationDependency, MigrationDependency.Id> {

    /** Outbound edges: "what does {@code recipeId} depend on?" */
    List<MigrationDependency> findByRecipeId(UUID recipeId);

    /** Inbound edges: "what depends on {@code dependsOnId}?" */
    List<MigrationDependency> findByDependsOnId(UUID dependsOnId);

    void deleteByRecipeId(UUID recipeId);
}
