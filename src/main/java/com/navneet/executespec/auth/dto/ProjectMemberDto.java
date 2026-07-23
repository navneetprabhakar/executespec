package com.navneet.executespec.auth.dto;

import java.time.Instant;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.ProjectMember;

/**
 * One project membership row as the members screen sees it.
 *
 * <p>RR-1510 — {@code external} and {@code homeOrgName} are load-bearing governance fields, not
 * decoration. {@code ProjectMember.external} is persisted {@code true} when a cross-org invite is
 * accepted, but the DTO never carried it, so on the one screen whose job is answering "who outside
 * my organization can see this project's code, runs and cost" an external collaborator rendered
 * identically to an employee.
 */
public record ProjectMemberDto(
    String userId,
    String email,
    String firstName,
    String lastName,
    String projectRole,
    Instant assignedAt,
    boolean external,
    String homeOrgName) {

  /** Compatibility factory for callers that have no org-name lookup available. */
  public static ProjectMemberDto from(AppUser user, ProjectMember member) {
    return from(user, member, null);
  }

  public static ProjectMemberDto from(AppUser user, ProjectMember member, String homeOrgName) {
    return new ProjectMemberDto(
        user.getId(),
        user.getEmail(),
        user.getFirstName(),
        user.getLastName(),
        member.getProjectRole(),
        member.getAssignedAt(),
        member.isExternal(),
        homeOrgName);
  }
}
