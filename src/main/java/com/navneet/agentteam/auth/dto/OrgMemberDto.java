package com.navneet.agentteam.auth.dto;

import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.OrgMember;

import java.time.Instant;

public record OrgMemberDto(
        String userId,
        String email,
        String firstName,
        String lastName,
        String orgRole,
        Instant joinedAt
) {
    public static OrgMemberDto from(AppUser user, OrgMember member) {
        return new OrgMemberDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                member.getOrgRole(),
                member.getJoinedAt()
        );
    }
}
