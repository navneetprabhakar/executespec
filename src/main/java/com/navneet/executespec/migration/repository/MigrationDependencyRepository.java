package com.navneet.executespec.migration.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.migration.entity.MigrationDependency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface MigrationDependencyRepository
    extends JpaRepository<MigrationDependency, MigrationDependency.Id> {

  /** Outbound edges: "what does {@code recipeId} depend on?" */
  List<MigrationDependency> findByRecipeId(UUID recipeId);

  /** Inbound edges: "what depends on {@code dependsOnId}?" */
  List<MigrationDependency> findByDependsOnId(UUID dependsOnId);

  /**
   * See {@link MigrationVariantRepository#deleteByRecipeId} — same reseeder-self-invocation
   * rationale for the @Transactional.
   */
  @Transactional
  void deleteByRecipeId(UUID recipeId);
}
