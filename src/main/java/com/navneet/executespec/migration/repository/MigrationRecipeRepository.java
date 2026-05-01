package com.navneet.executespec.migration.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.migration.entity.MigrationRecipe;
import com.navneet.executespec.migration.entity.MigrationRecipe.Source;
import com.navneet.executespec.migration.entity.MigrationRecipe.Status;
import com.navneet.executespec.migration.entity.MigrationRecipe.Tier;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * R-336 — basic CRUD + filter-shaped queries the W4 migration-library API will consume.
 * Source-stack matching (W4 stack-aware filtering via the GIN index on source_stack) lands as a
 * native query in W4.
 */
public interface MigrationRecipeRepository extends JpaRepository<MigrationRecipe, UUID> {

  Optional<MigrationRecipe> findBySlug(String slug);

  List<MigrationRecipe> findByStatus(Status status);

  List<MigrationRecipe> findByCategoryAndStatus(String category, Status status);

  List<MigrationRecipe> findByTierAndStatus(Tier tier, Status status);

  List<MigrationRecipe> findBySourceAndStatus(Source source, Status status);

  boolean existsBySlug(String slug);
}
