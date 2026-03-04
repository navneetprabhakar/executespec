package com.navneet.agentteam.auth.dto;

import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.OrgMember;

public record UserDto(
        String id,
        String email,
        String firstName,
        String lastName,
        String globalRole,
        String tier,
        String status,
        boolean mustChangePassword,
        boolean emailVerified,
        String orgId,
        String orgRole,      // null if not in an org
        String githubOwner,  // GitHub username or org — null if not configured
        boolean hasGithubPat // true if a PAT is stored (raw PAT never exposed)
) {
    public static UserDto from(AppUser user, OrgMember orgMember) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getGlobalRole(),
                user.getTier(),
                user.getStatus(),
                user.isMustChangePassword(),
                user.isEmailVerified(),
                user.getOrgId(),
                orgMember != null ? orgMember.getOrgRole() : null,
                user.getGithubOwner(),
                user.getGithubPat() != null && !user.getGithubPat().isBlank()
        );
    }
}
