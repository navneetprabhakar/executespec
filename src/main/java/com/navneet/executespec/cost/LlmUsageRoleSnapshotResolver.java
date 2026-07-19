package com.navneet.executespec.cost;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Resolves the human authorization role once, before a physical provider attempt is dispatched. */
@Component
@RequiredArgsConstructor
public class LlmUsageRoleSnapshotResolver {

  private final OrgMemberRepository orgMemberRepository;
  private final ProjectMemberRepository projectMemberRepository;
  private final AppUserRepository appUserRepository;

  public String resolve(String orgId, String userId, String projectId) {
    if (userId == null || userId.isBlank()) return "UNKNOWN";
    if (projectId != null && !projectId.isBlank()) {
      String projectRole =
          projectMemberRepository
              .findByProjectIdAndUserId(projectId, userId)
              .map(member -> member.getProjectRole())
              .orElse(null);
      if (projectRole != null && !projectRole.isBlank()) return projectRole.trim();
    }
    if (orgId != null && !orgId.isBlank()) {
      String orgRole =
          orgMemberRepository
              .findByOrgIdAndUserId(orgId, userId)
              .map(member -> member.getOrgRole())
              .orElse(null);
      if (orgRole != null && !orgRole.isBlank()) return orgRole.trim();
    }
    return appUserRepository
        .findById(userId)
        .map(user -> user.getGlobalRole())
        .filter(role -> role != null && !role.isBlank())
        .map(String::trim)
        .orElse("UNKNOWN");
  }
}
