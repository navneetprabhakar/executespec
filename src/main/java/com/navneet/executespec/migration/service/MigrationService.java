package com.navneet.executespec.migration.service;

import com.navneet.executespec.migration.dto.MigrationDependencyDto;
import com.navneet.executespec.migration.dto.MigrationDetailDto;
import com.navneet.executespec.migration.dto.MigrationListItemDto;
import com.navneet.executespec.migration.dto.MigrationRecipeDto;
import com.navneet.executespec.migration.dto.MigrationSlotDto;
import com.navneet.executespec.migration.dto.MigrationVariantDto;
import com.navneet.executespec.migration.entity.MigrationRecipe;
import com.navneet.executespec.migration.entity.MigrationRecipe.Status;
import com.navneet.executespec.migration.entity.MigrationRecipe.Tier;
import com.navneet.executespec.migration.repository.MigrationDependencyRepository;
import com.navneet.executespec.migration.repository.MigrationRecipeRepository;
import com.navneet.executespec.migration.repository.MigrationSlotRepository;
import com.navneet.executespec.migration.repository.MigrationVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * R-338 — read-side library service for migration recipes.  Mirrors
 * {@link com.navneet.executespec.readymade.service.ReadymadeService}
 * shape; same tier-gating ladder.
 */
@Service
@RequiredArgsConstructor
public class MigrationService {

    private final MigrationRecipeRepository recipeRepo;
    private final MigrationVariantRepository variantRepo;
    private final MigrationSlotRepository slotRepo;
    private final MigrationDependencyRepository dependencyRepo;

    public List<MigrationListItemDto> list(String category, String tier, String search, Tier callerTier) {
        List<MigrationRecipe> candidates;
        if (category != null && !category.isBlank()) {
            candidates = recipeRepo.findByCategoryAndStatus(category, Status.LISTED);
        } else if (tier != null && !tier.isBlank()) {
            try {
                candidates = recipeRepo.findByTierAndStatus(Tier.valueOf(tier.toUpperCase()), Status.LISTED);
            } catch (IllegalArgumentException ex) {
                candidates = List.of();
            }
        } else {
            candidates = recipeRepo.findByStatus(Status.LISTED);
        }
        String needle = search == null ? null : search.trim().toLowerCase();
        return candidates.stream()
            .filter(r -> matchesSearch(r, needle))
            .map(r -> decorate(r, callerTier))
            .toList();
    }

    public Optional<MigrationDetailDto> detail(String slug, Tier callerTier) {
        return recipeRepo.findBySlug(slug).map(r -> {
            List<MigrationVariantDto> variants = variantRepo.findByRecipeId(r.getId()).stream()
                .map(MigrationVariantDto::from).toList();
            List<MigrationSlotDto> slots = slotRepo.findByRecipeIdOrderByDisplayOrderAsc(r.getId()).stream()
                .map(MigrationSlotDto::from).toList();
            List<MigrationDependencyDto> deps = dependencyRepo.findByRecipeId(r.getId()).stream()
                .map(MigrationDependencyDto::from).toList();
            boolean locked = isLocked(r.getTier(), callerTier);
            return new MigrationDetailDto(
                MigrationRecipeDto.from(r),
                variants, slots, deps,
                locked,
                locked ? r.getTier().name() : null
            );
        });
    }

    /**
     * FREE &lt; PRO &lt; TEAM &lt; ENTERPRISE; null caller treated
     * as FREE.  Same shape as the readymades tier-gate so the UI
     * ladder is consistent across both products.
     *
     * [F-05] (audit Sprint 2, 2026-04-30): promoted to public so
     * {@code MigrationController#dryRun} can enforce the tier gate at
     * the dry-run boundary. Apply already enforced via the orchestrator;
     * dry-run leaked the full applicability + cost + sample diff value
     * to FREE users on locked recipes.
     */
    public static boolean isLocked(Tier requiredTier, Tier callerTier) {
        if (requiredTier == null) return false;
        Tier effective = callerTier == null ? Tier.FREE : callerTier;
        return requiredTier.ordinal() > effective.ordinal();
    }

    /**
     * Returns the recipe's required tier so the controller can enforce
     * the tier gate at the dry-run boundary without re-querying the full
     * detail DTO. Empty if the slug is unknown.
     */
    public Optional<Tier> requiredTierFor(String slug) {
        return recipeRepo.findBySlug(slug).map(r -> r.getTier());
    }

    private MigrationListItemDto decorate(MigrationRecipe r, Tier callerTier) {
        return isLocked(r.getTier(), callerTier)
            ? MigrationListItemDto.locked(r, r.getTier())
            : MigrationListItemDto.unlocked(r);
    }

    private static boolean matchesSearch(MigrationRecipe r, String needle) {
        if (needle == null || needle.isEmpty()) return true;
        return contains(r.getName(), needle)
            || contains(r.getTagline(), needle)
            || contains(r.getDescription(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }
}
