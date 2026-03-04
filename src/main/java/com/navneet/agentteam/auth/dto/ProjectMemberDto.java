package com.navneet.agentteam.auth.dto;

import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.ProjectMember;

import java.time.Instant;

public record ProjectMemberDto(
        String userId,
        String email,
        String firstName,
        String lastName,
        String projectRole,
        Instant assignedAt
) {
    public static ProjectMemberDto from(AppUser user, ProjectMember member) {
        return new ProjectMemberDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                member.getProjectRole(),
                member.getAssignedAt()
        );
    }
}
