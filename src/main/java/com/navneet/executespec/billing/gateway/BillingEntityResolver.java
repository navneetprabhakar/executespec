package com.navneet.executespec.billing.gateway;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the billing entity for a given user.
 * If the user belongs to an org, the org is the billing entity (org-level subscriptions).
 * Otherwise, the user themselves is the billing entity (personal subscriptions).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingEntityResolver {

    private final OrgRepository orgRepo;

    /**
     * Resolve the billing entity for the given user.
     * If user has an org → return OrgBillingEntity wrapper.
     * Otherwise → return UserBillingEntity wrapper.
     */
    public BillingEntity resolve(AppUser user) {
        if (user.getOrgId() != null) {
            Org org = orgRepo.findById(user.getOrgId()).orElse(null);
            if (org != null) {
                log.debug("Resolved billing entity to org: orgId={}, userId={}", org.getId(), user.getId());
                return new OrgBillingEntity(org);
            }
        }
        log.debug("Resolved billing entity to user: userId={}", user.getId());
        return new UserBillingEntity(user);
    }

    // ── Wrapper implementations ─────────────────────────────────────────────

    /**
     * Wraps an AppUser as a BillingEntity.
     */
    public static class UserBillingEntity implements BillingEntity {
        private final AppUser user;

        public UserBillingEntity(AppUser user) {
            this.user = user;
        }

        @Override public String getId() { return user.getId(); }
        @Override public String getTier() { return user.getTier(); }
        @Override public String getPaymentGateway() { return user.getPaymentGateway(); }
        @Override public String getStripeCustomerId() { return user.getStripeCustomerId(); }
        @Override public void setStripeCustomerId(String id) { user.setStripeCustomerId(id); }
        @Override public String getEntityType() { return "USER"; }

        public AppUser getUser() { return user; }
    }

    /**
     * Wraps an Org as a BillingEntity.
     */
    public static class OrgBillingEntity implements BillingEntity {
        private final Org org;

        public OrgBillingEntity(Org org) {
            this.org = org;
        }

        @Override public String getId() { return org.getId(); }
        @Override public String getTier() { return org.getTier(); }
        @Override public String getPaymentGateway() { return org.getPaymentGateway(); }
        @Override public String getStripeCustomerId() { return org.getStripeCustomerId(); }
        @Override public void setStripeCustomerId(String id) { org.setStripeCustomerId(id); }
        @Override public String getEntityType() { return "ORG"; }

        public Org getOrg() { return org; }
    }
}
