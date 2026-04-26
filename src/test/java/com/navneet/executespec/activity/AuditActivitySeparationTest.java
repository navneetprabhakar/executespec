package com.navneet.executespec.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.service.ActivityAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E4.4 — cross-cutting verification that the AUDIT-only tier never
 * leaks into the customer-facing activity feed and that dual-surface
 * events DO appear in both surfaces.
 *
 * <p>The taxonomy + per-event tier metadata is asserted in
 * {@link ActivityEventTypeTest}; the emitter dual-write contract is
 * asserted in {@link ActivityEventEmitterTest}; the controller's
 * AUDIT-tier filtering is asserted in {@link ActivityFeedControllerTest}.
 *
 * <p>This file pulls those guarantees together into one
 * "if these break, the customer-vs-compliance separation is broken"
 * regression suite per plan §4.4. Updates to taxonomy that re-tier
 * an event must update one of these test methods or the contract is
 * violated.
 */
@ExtendWith(MockitoExtension.class)
class AuditActivitySeparationTest {

    @Test
    void everyAuditOnlyEvent_onlyTargetsAuditSurface() {
        // No AUDIT_ONLY event should declare ACTIVITY in its surfaces.
        for (ActivityEventType t : ActivityEventType.values()) {
            if (t.tier() == ActivityEventType.Tier.AUDIT_ONLY) {
                assertThat(t.surfaces())
                        .as("AUDIT_ONLY tier event %s must NOT include ACTIVITY surface", t)
                        .doesNotContain(ActivityEventType.Surface.ACTIVITY);
            }
        }
    }

    @Test
    void emitter_neverWritesToActivityFeed_forAuditOnlyEvents() {
        ActivityFeedEntryRepository feedRepo = mock(ActivityFeedEntryRepository.class);
        ActivityAuditService auditService = mock(ActivityAuditService.class);
        ActivityEventEmitter emitter = new ActivityEventEmitter(
                feedRepo, auditService, new ObjectMapper());

        // Drive every AUDIT_ONLY event through the emitter
        for (ActivityEventType t : ActivityEventType.values()) {
            if (t.tier() != ActivityEventType.Tier.AUDIT_ONLY) continue;
            emitter.emit(UUID.randomUUID(), t, UUID.randomUUID(),
                    "TARGET", "id-" + t, "summary for " + t, null);
        }
        // activity_feed must remain untouched for every AUDIT_ONLY event
        verify(feedRepo, never()).save(any());
        // activity_audit must have been called once per AUDIT_ONLY event
        long auditOnlyCount = java.util.Arrays.stream(ActivityEventType.values())
                .filter(t -> t.tier() == ActivityEventType.Tier.AUDIT_ONLY).count();
        verify(auditService, times((int) auditOnlyCount)).record(any(ActivityAudit.class));
    }

    @Test
    void emitter_dualSurfaceEvent_writesToBoth() {
        ActivityFeedEntryRepository feedRepo = mock(ActivityFeedEntryRepository.class);
        ActivityAuditService auditService = mock(ActivityAuditService.class);
        ActivityEventEmitter emitter = new ActivityEventEmitter(
                feedRepo, auditService, new ObjectMapper());

        // MEMBER_ROLE_CHANGED is the canonical dual-surface event per plan §3.3
        assertThat(ActivityEventType.MEMBER_ROLE_CHANGED.surfaces())
                .containsExactlyInAnyOrder(
                        ActivityEventType.Surface.ACTIVITY,
                        ActivityEventType.Surface.AUDIT);

        emitter.emit(UUID.randomUUID(), ActivityEventType.MEMBER_ROLE_CHANGED,
                UUID.randomUUID(), "MEMBER", "user-x", "role change", null);

        verify(feedRepo, times(1)).save(any(ActivityFeedEntry.class));
        verify(auditService, times(1)).record(any(ActivityAudit.class));
    }

    @Test
    void everyHighSignalEvent_writesToActivitySurface() {
        for (ActivityEventType t : ActivityEventType.values()) {
            if (t.tier() == ActivityEventType.Tier.HIGH_SIGNAL) {
                assertThat(t.writesTo(ActivityEventType.Surface.ACTIVITY))
                        .as("HIGH_SIGNAL %s must write to ACTIVITY", t)
                        .isTrue();
            }
        }
    }
}
