package com.navneet.executespec.readymade.service;

import com.navneet.executespec.readymade.dto.ReadymadeDependencyDto;
import com.navneet.executespec.readymade.dto.ReadymadeDetailDto;
import com.navneet.executespec.readymade.dto.ReadymadeListItemDto;
import com.navneet.executespec.readymade.dto.ReadymadeSlotDto;
import com.navneet.executespec.readymade.dto.ReadymadeVariantDto;
import com.navneet.executespec.readymade.entity.ReadymadeSolution;
import com.navneet.executespec.readymade.entity.ReadymadeSolution.Status;
import com.navneet.executespec.readymade.entity.ReadymadeSolution.Tier;
import com.navneet.executespec.readymade.repository.ReadymadeDependencyRepository;
import com.navneet.executespec.readymade.repository.ReadymadeSlotRepository;
import com.navneet.executespec.readymade.repository.ReadymadeSolutionRepository;
import com.navneet.executespec.readymade.repository.ReadymadeVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R-329 — read-side library service.  Wraps the repos with:
 *
 * <ul>
 *   <li>Filter-shaped list queries (by category, macro_group, tier,
 *       source) — backed by the V157 indexes.</li>
 *   <li>In-memory full-text search over name/description/tagline
 *       (FTS native query against the GIN index lands in W9; in-memory
 *       is fine for the v1 catalog of ≤ 130 entries).</li>
 *   <li>Tier-gating decoration — wraps each {@link ReadymadeSolution}
 *       in a {@link ReadymadeListItemDto} with a caller-tier-derived
 *       {@code locked} flag.</li>
 * </ul>
 *
 * <p>Tier ordering: FREE &lt; PRO &lt; TEAM &lt; ENTERPRISE
 * (declared by {@link Tier} enum ordinal).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadymadeService {

    private final ReadymadeSolutionRepository solutionRepo;
    private final ReadymadeVariantRepository variantRepo;
    private final ReadymadeSlotRepository slotRepo;
    private final ReadymadeDependencyRepository dependencyRepo;

    /**
     * List solutions visible to the caller, filter-narrowed.  Locked
     * state is derived per-row from the caller's tier.
     *
     * @param category    optional — exact match on category.
     * @param macroGroup  optional — exact match on macro group.
     * @param tier        optional — exact match on minimum tier
     *                    (e.g. "FREE" surfaces only FREE-tier rows).
     * @param search      optional — case-insensitive substring match
     *                    against name + tagline + description.
     * @param callerTier  caller's tier for lock-state derivation.
     */
    public List<ReadymadeListItemDto> list(
            String category, String macroGroup, String tier,
            String search, Tier callerTier) {

        // Pick the most-specific repo query available; do remaining
        // filters in-memory.  v1 catalog is small enough that a single
        // findByStatus + Stream filter is also fine.
        List<ReadymadeSolution> candidates;
        if (category != null && !category.isBlank()) {
            candidates = solutionRepo.findByCategoryAndStatus(category, Status.LISTED);
        } else if (macroGroup != null && !macroGroup.isBlank()) {
            candidates = solutionRepo.findByMacroGroupAndStatus(macroGroup, Status.LISTED);
        } else if (tier != null && !tier.isBlank()) {
            try {
                candidates = solutionRepo.findByTierAndStatus(Tier.valueOf(tier.toUpperCase()), Status.LISTED);
            } catch (IllegalArgumentException ex) {
                candidates = List.of();
            }
        } else {
            candidates = solutionRepo.findByStatus(Status.LISTED);
        }

        // Apply remaining filters in-memory.
        String needle = search == null ? null : search.trim().toLowerCase();
        return candidates.stream()
            .filter(s -> filterMatches(s, category, macroGroup, tier))
            .filter(s -> matchesSearch(s, needle))
            .map(s -> decorate(s, callerTier))
            .toList();
    }

    private boolean filterMatches(ReadymadeSolution s, String category, String macroGroup, String tier) {
        if (category != null && !category.isBlank() && !category.equals(s.getCategory())) return false;
        if (macroGroup != null && !macroGroup.isBlank() && !macroGroup.equals(s.getMacroGroup())) return false;
        if (tier != null && !tier.isBlank() && !tier.equalsIgnoreCase(s.getTier().name())) return false;
        return true;
    }

    private boolean matchesSearch(ReadymadeSolution s, String needle) {
        if (needle == null || needle.isEmpty()) return true;
        return contains(s.getName(), needle)
            || contains(s.getTagline(), needle)
            || contains(s.getDescription(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    /** Detail page payload for a single solution by slug. */
    public Optional<ReadymadeDetailDto> detail(String slug, Tier callerTier) {
        return solutionRepo.findBySlug(slug).map(s -> {
            List<ReadymadeVariantDto> variants = variantRepo.findBySolutionId(s.getId()).stream()
                .map(ReadymadeVariantDto::from).toList();
            List<ReadymadeSlotDto> slots = slotRepo.findBySolutionIdOrderByDisplayOrderAsc(s.getId()).stream()
                .map(ReadymadeSlotDto::from).toList();
            List<ReadymadeDependencyDto> deps = dependencyRepo.findBySolutionId(s.getId()).stream()
                .map(ReadymadeDependencyDto::from).toList();
            boolean locked = isLocked(s.getTier(), callerTier);
            return new ReadymadeDetailDto(
                com.navneet.executespec.readymade.dto.ReadymadeSolutionDto.from(s),
                variants, slots, deps,
                locked,
                locked ? s.getTier().name() : null
            );
        });
    }

    /**
     * Category facet counts for the gallery sidebar.  Returns
     * insertion-ordered map of {@code category → count} sorted by
     * count descending so the busiest categories surface first.
     */
    public Map<String, Long> categoryCounts() {
        return solutionRepo.findByStatus(Status.LISTED).stream()
            .collect(java.util.stream.Collectors.groupingBy(
                ReadymadeSolution::getCategory,
                java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));
    }

    /** Same shape as {@link #categoryCounts} but for the 4 macro-groups. */
    public Map<String, Long> macroGroupCounts() {
        return solutionRepo.findByStatus(Status.LISTED).stream()
            .collect(java.util.stream.Collectors.groupingBy(
                ReadymadeSolution::getMacroGroup,
                java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Returns true when {@code requiredTier} is strictly above
     * {@code callerTier} on the FREE &lt; PRO &lt; TEAM &lt;
     * ENTERPRISE ladder.  Caller tier null is treated as FREE
     * (anonymous / FREE).
     */
    static boolean isLocked(Tier requiredTier, Tier callerTier) {
        if (requiredTier == null) return false;
        Tier effective = callerTier == null ? Tier.FREE : callerTier;
        return requiredTier.ordinal() > effective.ordinal();
    }

    private ReadymadeListItemDto decorate(ReadymadeSolution s, Tier callerTier) {
        return isLocked(s.getTier(), callerTier)
            ? ReadymadeListItemDto.locked(s, s.getTier())
            : ReadymadeListItemDto.unlocked(s);
    }
}
