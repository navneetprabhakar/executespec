package com.navneet.executespec.readymade.dto;

import com.navneet.executespec.readymade.entity.ReadymadeVariant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReadymadeVariantDto(
        UUID id,
        UUID solutionId,
        String language,
        String framework,
        String options,
        String specTemplate,
        String scaffoldKey,
        BigDecimal estCreditsLow,
        BigDecimal estCreditsHigh,
        Instant createdAt
) {
    public static ReadymadeVariantDto from(ReadymadeVariant v) {
        return new ReadymadeVariantDto(
                v.getId(),
                v.getSolutionId(),
                v.getLanguage(),
                v.getFramework(),
                v.getOptions(),
                v.getSpecTemplate(),
                v.getScaffoldKey(),
                v.getEstCreditsLow(),
                v.getEstCreditsHigh(),
                v.getCreatedAt()
        );
    }
}
