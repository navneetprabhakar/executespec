package com.navneet.executespec.auth.dto;

import java.time.Instant;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;

public record OrgMemberDto(
    String userId,
    String email,
    String firstName,
    String lastName,
    String orgRole,
    Instant joinedAt) {
  public static OrgMemberDto from(AppUser user, OrgMember member) {
    return new OrgMemberDto(
        user.getId(),
        user.getEmail(),
        user.getFirstName(),
        user.getLastName(),
        member.getOrgRole(),
        member.getJoinedAt());
  }
}
