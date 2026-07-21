package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.Test;

/**
 * RR-563 — raw provider-dollar cost is a billing figure. It must be visible only to a billing-role
 * viewer of the resource's OWN org (or a global SUPER_ADMIN / FINANCE), not to every project
 * reader.
 */
class CostVisibilityPolicyTest {

  private static AppUserDetails principal(String globalRole, String orgId, String orgRole) {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setGlobalRole(globalRole);
    user.setOrgId(orgId);
    OrgMember member = null;
    if (orgRole != null) {
      member = new OrgMember();
      member.setOrgId(orgId);
      member.setOrgRole(orgRole);
    }
    return new AppUserDetails(user, member);
  }

  @Test
  void globalBillingRolesSeeCostRegardlessOfOrg() {
    assertThat(CostVisibilityPolicy.canViewRawCost(principal("SUPER_ADMIN", null, null), "org-a"))
        .isTrue();
    assertThat(CostVisibilityPolicy.canViewRawCost(principal("FINANCE", null, null), "org-a"))
        .isTrue();
  }

  @Test
  void owningOrgBillingRolesSeeCost() {
    assertThat(
            CostVisibilityPolicy.canViewRawCost(principal("USER", "org-a", "ORG_ADMIN"), "org-a"))
        .isTrue();
    assertThat(
            CostVisibilityPolicy.canViewRawCost(
                principal("USER", "org-a", "BILLING_ADMIN"), "org-a"))
        .isTrue();
  }

  @Test
  void aLowerPrivilegedProjectReaderDoesNotSeeRawCost() {
    // An ordinary DEVELOPER in the resource's own org — admitted by the project read guard, denied
    // raw provider-dollar cost here.
    assertThat(
            CostVisibilityPolicy.canViewRawCost(principal("USER", "org-a", "DEVELOPER"), "org-a"))
        .isFalse();
  }

  @Test
  void aCrossOrgAdminDoesNotSeeAnotherTenantsCost() {
    // A cross-org project collaborator who is an ORG_ADMIN of their OWN workspace (org-b) must not
    // inherit cost visibility on the host org's (org-a) resource.
    assertThat(
            CostVisibilityPolicy.canViewRawCost(principal("USER", "org-b", "ORG_ADMIN"), "org-a"))
        .isFalse();
  }

  @Test
  void nullUserAndUnknownOrgFailClosed() {
    assertThat(CostVisibilityPolicy.canViewRawCost(null, "org-a")).isFalse();
    // No owning-org scope + non-global role => cannot evaluate a billing role, fail closed.
    assertThat(
            CostVisibilityPolicy.canViewRawCost(principal("USER", "org-a", "BILLING_ADMIN"), null))
        .isFalse();
  }
}
