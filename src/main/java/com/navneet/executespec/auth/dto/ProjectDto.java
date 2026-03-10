package com.navneet.executespec.auth.dto;

import com.navneet.executespec.auth.entity.Project;

import java.time.Instant;

public record ProjectDto(
        String id,
        String orgId,
        String name,
        String description,
        String status,
        Instant createdAt
) {
    public static ProjectDto from(Project p) {
        return new ProjectDto(p.getId(), p.getOrgId(), p.getName(), p.getDescription(), p.getStatus(), p.getCreatedAt());
    }
}
