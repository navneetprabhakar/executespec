package com.navneet.executespec.migration.repository;

import com.navneet.executespec.migration.entity.MigrationSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MigrationSlotRepository extends JpaRepository<MigrationSlot, UUID> {

    List<MigrationSlot> findByRecipeIdOrderByDisplayOrderAsc(UUID recipeId);

    void deleteByRecipeId(UUID recipeId);
}
