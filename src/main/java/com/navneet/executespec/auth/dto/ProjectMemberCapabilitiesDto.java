package com.navneet.executespec.auth.dto;

/**
 * Effective per-action authority for the calling user on one project (RR-1507 / RR-1509).
 *
 * <p>Membership administration and share-link administration are two different server guards —
 * {@code ProjectService.requireProjectAdmin} versus {@code
 * ProjectShareLinkController.isProjectWriter} — and a single client-side boolean cannot express
 * both. External invites add a third, independent authority with three separate causes, so the
 * blocker is reported explicitly instead of leaving the client to guess one message.
 *
 * @param projectRole the caller's own {@code ProjectMember} role, or null when they have none
 * @param canManageMembership add/remove/role-change: PROJECT_ADMIN, host ORG_ADMIN or SUPER_ADMIN
 * @param canManageShareLinks the above plus PROJECT_DEVELOPER
 * @param canInviteExternal whether an external invite would be accepted right now
 * @param inviteBlockedReason null when {@code canInviteExternal}; otherwise {@code TIER}, {@code
 *     ROLE} or {@code ORG_EXTERNAL_SHARING_DISABLED}
 */
public record ProjectMemberCapabilitiesDto(
    String projectRole,
    boolean canManageMembership,
    boolean canManageShareLinks,
    boolean canInviteExternal,
    String inviteBlockedReason) {}
