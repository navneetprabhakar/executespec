package com.navneet.executespec.migration.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.migration.entity.MigrationVariant;

public record MigrationVariantDto(
    UUID id,
    UUID recipeId,
    String optionsSignature,
    String options,
    String specTemplate,
    String aiFallbackPromptPath,
    BigDecimal estCreditsLow,
    BigDecimal estCreditsHigh,
    Instant createdAt) {
  public static MigrationVariantDto from(MigrationVariant v) {
    return new MigrationVariantDto(
        v.getId(),
        v.getRecipeId(),
        v.getOptionsSignature(),
        v.getOptions(),
        v.getSpecTemplate(),
        v.getAiFallbackPromptPath(),
        v.getEstCreditsLow(),
        v.getEstCreditsHigh(),
        v.getCreatedAt());
  }
}
