package com.navneet.executespec.auth.dto;

/**
 * Request body for POST /api/admin/invite.
 * Super admin can invite a user directly, optionally into an org (existing or new).
 */
public record AdminInviteRequest(
        String email,
        String firstName,
        String lastName,
        /** FREE | PRO | ENTERPRISE */
        String tier,
        /** ID of an existing org (nullable — use either orgId or newOrgName) */
        String orgId,
        /** Name of a new org to create (nullable) */
        String newOrgName,
        /** DEVELOPER | ORG_ADMIN | BILLING_ADMIN — required when orgId or newOrgName is set */
        String orgRole
) {}
