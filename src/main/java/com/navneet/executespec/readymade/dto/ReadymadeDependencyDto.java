package com.navneet.executespec.readymade.dto;

import com.navneet.executespec.readymade.entity.ReadymadeDependency;

import java.util.UUID;

public record ReadymadeDependencyDto(
        UUID solutionId,
        UUID dependsOnId,
        boolean required
) {
    public static ReadymadeDependencyDto from(ReadymadeDependency d) {
        return new ReadymadeDependencyDto(
                d.getSolutionId(),
                d.getDependsOnId(),
                d.isRequired()
        );
    }
}
