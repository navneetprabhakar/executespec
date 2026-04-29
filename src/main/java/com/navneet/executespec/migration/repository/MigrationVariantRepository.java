package com.navneet.executespec.migration.repository;

import com.navneet.executespec.migration.entity.MigrationVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MigrationVariantRepository extends JpaRepository<MigrationVariant, UUID> {

    List<MigrationVariant> findByRecipeId(UUID recipeId);

    Optional<MigrationVariant> findByRecipeIdAndOptionsSignature(
        UUID recipeId, String optionsSignature);

    /**
     * Spring Data derived-name {@code deleteBy*} queries require an
     * active transaction.  Annotating the method itself means it
     * works whether the caller has a tx (admin controller path) or
     * not (boot-time {@code MigrationRecipeSeeder.@PostConstruct}
     * which calls {@code reseed()} via {@code this} and so bypasses
     * the {@code @Transactional} proxy on the seeder).
     */
    @Transactional
    void deleteByRecipeId(UUID recipeId);
}
