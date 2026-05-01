package com.navneet.executespec.readymade.dto;

import java.util.UUID;

import com.navneet.executespec.readymade.entity.ReadymadeDependency;

public record ReadymadeDependencyDto(UUID solutionId, UUID dependsOnId, boolean required) {
  public static ReadymadeDependencyDto from(ReadymadeDependency d) {
    return new ReadymadeDependencyDto(d.getSolutionId(), d.getDependsOnId(), d.isRequired());
  }
}
