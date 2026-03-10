package com.navneet.executespec.auth.dto;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;

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
        String orgRole,           // null if not in an org

        // GitHub
        String githubOwner,       // GitHub username or org — null if not configured
        boolean hasGithubPat,     // true if a PAT is stored (raw PAT never exposed)

        // AI provider keys (booleans only — raw keys never exposed)
        boolean hasOpenAiKey,
        boolean hasAnthropicKey,
        boolean hasGeminiKey,

        // JIRA (non-sensitive fields exposed; token flag only)
        String jiraBaseUrl,
        String jiraEmail,
        boolean hasJiraToken,

        // Confluence (non-sensitive fields exposed; token flag only)
        String confluenceBaseUrl,
        String confluenceEmail,
        boolean hasConfluenceToken
) {
    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

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

                // GitHub
                user.getGithubOwner(),
                present(user.getGithubPat()),

                // AI keys
                present(user.getOpenAiApiKey()),
                present(user.getAnthropicApiKey()),
                present(user.getGeminiApiKey()),

                // JIRA
                user.getJiraBaseUrl(),
                user.getJiraEmail(),
                present(user.getJiraApiToken()),

                // Confluence
                user.getConfluenceBaseUrl(),
                user.getConfluenceEmail(),
                present(user.getConfluenceApiToken())
        );
    }
}
