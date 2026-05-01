package com.navneet.executespec.migration.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.migration.entity.MigrationRecipe;

/**
 * R-336 — flat over-the-wire representation of {@link MigrationRecipe} for the W4 library API.
 * Tier-gating / lock-state decoration happens in the controller layer per-request based on caller
 * tier.
 */
public record MigrationRecipeDto(
    UUID id,
    String slug,
    String name,
    String tagline,
    String description,
    String sourceStack,
    String targetStack,
    String affectedFilesGlob,
    String category,
    String tags,
    String tier,
    String source,
    String status,
    String deterministicRecipeRef,
    String aiFallbackPromptPath,
    String preservationContract,
    Integer estFilesLow,
    Integer estFilesHigh,
    BigDecimal estCreditsLow,
    BigDecimal estCreditsHigh,
    String iconUrl,
    String thumbnailUrl,
    String samplePrUrl,
    String authorUserId,
    String authorOrgId,
    Instant createdAt,
    Instant updatedAt) {
  public static MigrationRecipeDto from(MigrationRecipe r) {
    return new MigrationRecipeDto(
        r.getId(),
        r.getSlug(),
        r.getName(),
        r.getTagline(),
        r.getDescription(),
        r.getSourceStack(),
        r.getTargetStack(),
        r.getAffectedFilesGlob(),
        r.getCategory(),
        r.getTags(),
        r.getTier() == null ? null : r.getTier().name(),
        r.getSource() == null ? null : r.getSource().name(),
        r.getStatus() == null ? null : r.getStatus().name(),
        r.getDeterministicRecipeRef(),
        r.getAiFallbackPromptPath(),
        r.getPreservationContract(),
        r.getEstFilesLow(),
        r.getEstFilesHigh(),
        r.getEstCreditsLow(),
        r.getEstCreditsHigh(),
        r.getIconUrl(),
        r.getThumbnailUrl(),
        r.getSamplePrUrl(),
        r.getAuthorUserId(),
        r.getAuthorOrgId(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
