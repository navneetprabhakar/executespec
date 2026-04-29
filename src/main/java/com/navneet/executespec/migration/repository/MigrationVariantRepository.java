package com.navneet.executespec.migration.repository;

import com.navneet.executespec.migration.entity.MigrationVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MigrationVariantRepository extends JpaRepository<MigrationVariant, UUID> {

    List<MigrationVariant> findByRecipeId(UUID recipeId);

    Optional<MigrationVariant> findByRecipeIdAndOptionsSignature(
        UUID recipeId, String optionsSignature);

    void deleteByRecipeId(UUID recipeId);
}
