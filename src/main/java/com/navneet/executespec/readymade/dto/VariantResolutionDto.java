package com.navneet.executespec.readymade.dto;

import java.util.List;

/**
 * R-329 — answer to "which variant of this solution should we use
 * for this user's repo?"
 *
 * @param bestMatch          variant that matches the detected stack;
 *                           null when no variant matches.
 * @param availableVariants  every variant for this solution; the UI
 *                           uses this to show "available variants"
 *                           when the user's stack has no exact
 *                           match.
 * @param detectedLanguage   normalised language we resolved from the
 *                           repo's detected_stack (or null if we
 *                           couldn't detect).
 * @param detectedFramework  normalised framework, same caveat.
 * @param reason             human-readable explanation when
 *                           bestMatch is null:
 *                           {@code "STACK_NOT_DETECTED"} /
 *                           {@code "NO_VARIANT_FOR_STACK"} /
 *                           {@code "OK"}.
 */
public record VariantResolutionDto(
        ReadymadeVariantDto bestMatch,
        List<ReadymadeVariantDto> availableVariants,
        String detectedLanguage,
        String detectedFramework,
        String reason
) {}
