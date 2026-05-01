package com.navneet.executespec.readymade.dto;

import java.util.UUID;

import com.navneet.executespec.readymade.entity.ReadymadeSlot;

public record ReadymadeSlotDto(
    UUID id,
    UUID solutionId,
    String name,
    String displayName,
    String type,
    boolean required,
    String defaultValue,
    String options,
    String description,
    int displayOrder) {
  public static ReadymadeSlotDto from(ReadymadeSlot s) {
    return new ReadymadeSlotDto(
        s.getId(),
        s.getSolutionId(),
        s.getName(),
        s.getDisplayName(),
        s.getType() == null ? null : s.getType().name(),
        s.isRequired(),
        s.getDefaultValue(),
        s.getOptions(),
        s.getDescription(),
        s.getDisplayOrder());
  }
}
