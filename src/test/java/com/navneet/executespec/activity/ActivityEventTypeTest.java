package com.navneet.executespec.activity;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the activity-event taxonomy declared in
 * {@link ActivityEventType}. Catches regressions where a contributor adds
 * a new event without declaring tier/surfaces/schemaVersion correctly,
 * or breaks the dual-surface contract for {@code MEMBER_ROLE_CHANGED}
 * which Phase E4 RBAC depends on.
 */
class ActivityEventTypeTest {

    @Test
    void everyEventDeclaresTierAndAtLeastOneSurface() {
        for (ActivityEventType t : ActivityEventType.values()) {
            assertThat(t.tier()).as("tier for %s", t).isNotNull();
            assertThat(t.surfaces()).as("surfaces for %s", t).isNotEmpty();
            assertThat(t.schemaVersion()).as("schemaVersion for %s", t).isPositive();
        }
    }

    @Test
    void auditOnlyTier_writesOnlyToAuditSurface() {
        for (ActivityEventType t : ActivityEventType.values()) {
            if (t.tier() == ActivityEventType.Tier.AUDIT_ONLY) {
                assertThat(t.surfaces())
                        .as("AUDIT_ONLY tier %s must write to AUDIT only", t)
                        .containsExactly(ActivityEventType.Surface.AUDIT);
            }
        }
    }

    @Test
    void highSignalTier_alwaysWritesToActivity() {
        for (ActivityEventType t : ActivityEventType.values()) {
            if (t.tier() == ActivityEventType.Tier.HIGH_SIGNAL) {
                assertThat(t.surfaces())
                        .as("HIGH_SIGNAL tier %s must include ACTIVITY surface", t)
                        .contains(ActivityEventType.Surface.ACTIVITY);
            }
        }
    }

    @Test
    void memberRoleChanged_isDualSurface() {
        // Plan §3.3 contract — RBAC + audit consumers both rely on this.
        ActivityEventType t = ActivityEventType.MEMBER_ROLE_CHANGED;
        assertThat(t.surfaces())
                .containsExactlyInAnyOrder(
                        ActivityEventType.Surface.ACTIVITY,
                        ActivityEventType.Surface.AUDIT);
    }

    @Test
    void writesTo_isConsistentWithSurfacesContains() {
        for (ActivityEventType t : ActivityEventType.values()) {
            for (ActivityEventType.Surface s : ActivityEventType.Surface.values()) {
                assertThat(t.writesTo(s))
                        .as("writesTo(%s) for %s should match surfaces().contains", s, t)
                        .isEqualTo(t.surfaces().contains(s));
            }
        }
    }

    @Test
    void executespecBootstrapEvents_areAllHighSignal() {
        // E1.5 bootstrap PR flow relies on these being visible to project members.
        Set<ActivityEventType> bootstrapEvents = EnumSet.of(
                ActivityEventType.EXECUTESPEC_BOOTSTRAP_INITIATED,
                ActivityEventType.EXECUTESPEC_BOOTSTRAP_COMPLETED,
                ActivityEventType.EXECUTESPEC_BOOTSTRAP_FAILED);
        for (ActivityEventType t : bootstrapEvents) {
            assertThat(t.tier()).isEqualTo(ActivityEventType.Tier.HIGH_SIGNAL);
            assertThat(t.surfaces()).contains(ActivityEventType.Surface.ACTIVITY);
        }
    }
}
