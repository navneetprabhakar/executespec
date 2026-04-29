package com.navneet.executespec.migration.repository;

import com.navneet.executespec.migration.entity.MigrationDryRunCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MigrationDryRunCacheRepository
        extends JpaRepository<MigrationDryRunCache, MigrationDryRunCache.Id> {

    Optional<MigrationDryRunCache> findByRecipeIdAndStackSignature(
        UUID recipeId, String stackSignature);

    /** Cache-bust path: drop every entry for a recipe when the recipe
     *  definition changes (W2 reseeder calls this). */
    void deleteByRecipeId(UUID recipeId);
}
