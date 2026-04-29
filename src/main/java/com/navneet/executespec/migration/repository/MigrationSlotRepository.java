package com.navneet.executespec.migration.repository;

import com.navneet.executespec.migration.entity.MigrationSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface MigrationSlotRepository extends JpaRepository<MigrationSlot, UUID> {

    List<MigrationSlot> findByRecipeIdOrderByDisplayOrderAsc(UUID recipeId);

    /** See {@link MigrationVariantRepository#deleteByRecipeId} — same
     *  reseeder-self-invocation rationale for the @Transactional. */
    @Transactional
    void deleteByRecipeId(UUID recipeId);
}
