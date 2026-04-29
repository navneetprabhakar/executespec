package com.navneet.executespec.readymade.dto;

import com.navneet.executespec.readymade.entity.ReadymadeSolution;

/**
 * R-329 — gallery card row.  Wraps {@link ReadymadeSolutionDto} with
 * caller-tier-dependent lock state so the UI can render the lock icon
 * + upgrade CTA without re-deriving tier comparisons client-side.
 *
 * @param locked      true when the caller's tier is below the
 *                    solution's required tier; false otherwise.
 * @param unlockTier  name of the tier needed to unlock; {@code null}
 *                    when {@code locked == false}.
 */
public record ReadymadeListItemDto(
        ReadymadeSolutionDto solution,
        boolean locked,
        String unlockTier
) {
    public static ReadymadeListItemDto unlocked(ReadymadeSolution s) {
        return new ReadymadeListItemDto(ReadymadeSolutionDto.from(s), false, null);
    }

    public static ReadymadeListItemDto locked(ReadymadeSolution s, ReadymadeSolution.Tier requiredTier) {
        return new ReadymadeListItemDto(
            ReadymadeSolutionDto.from(s), true, requiredTier.name()
        );
    }
}
