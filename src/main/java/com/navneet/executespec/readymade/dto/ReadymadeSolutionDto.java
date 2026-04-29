package com.navneet.executespec.readymade.dto;

import com.navneet.executespec.readymade.entity.ReadymadeSolution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * R-327 — flat over-the-wire representation of {@link ReadymadeSolution}
 * for the W3 library API (gallery + detail page).  Tier-gating /
 * lock-state decoration happens in the controller layer (the
 * {@code is_locked} flag isn't a DTO field — it's caller-tier-dependent
 * and decided per-request by the W3 controller).
 */
public record ReadymadeSolutionDto(
        UUID id,
        String slug,
        String name,
        String tagline,
        String description,
        String category,
        String macroGroup,
        String tags,
        String kind,
        String tier,
        String source,
        String status,
        String iconUrl,
        String thumbnailUrl,
        String samplePrUrl,
        Integer estFilesLow,
        Integer estFilesHigh,
        BigDecimal estCreditsLow,
        BigDecimal estCreditsHigh,
        String authorUserId,
        String authorOrgId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReadymadeSolutionDto from(ReadymadeSolution s) {
        return new ReadymadeSolutionDto(
                s.getId(),
                s.getSlug(),
                s.getName(),
                s.getTagline(),
                s.getDescription(),
                s.getCategory(),
                s.getMacroGroup(),
                s.getTags(),
                s.getKind() == null ? null : s.getKind().name(),
                s.getTier() == null ? null : s.getTier().name(),
                s.getSource() == null ? null : s.getSource().name(),
                s.getStatus() == null ? null : s.getStatus().name(),
                s.getIconUrl(),
                s.getThumbnailUrl(),
                s.getSamplePrUrl(),
                s.getEstFilesLow(),
                s.getEstFilesHigh(),
                s.getEstCreditsLow(),
                s.getEstCreditsHigh(),
                s.getAuthorUserId(),
                s.getAuthorOrgId(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
