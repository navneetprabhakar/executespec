package com.navneet.executespec.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.integration.PlanGateDecisionCommitted;
import com.navneet.executespec.integration.PlanGateDecisionService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Approval auditability — the AFTER_COMMIT bridge from the atomic V348 plan-gate decision to the
 * project activity feed / activity_audit chain must carry the REAL human decider (never SYSTEM),
 * and must be a no-op for standalone runs and non-customer decision kinds. First-time-only
 * semantics (no duplicate on replay) are pinned at the publisher in {@code
 * PlanGateDecisionServiceTest} — the service never re-publishes the event on an exact replay.
 */
class PlanGateDecisionActivityListenerTest {

  private final ActivityEventEmitter emitter = mock(ActivityEventEmitter.class);
  private final PlanGateDecisionActivityListener listener =
      new PlanGateDecisionActivityListener(emitter);

  private static final String RUN_ID = "123e4567-e89b-12d3-a456-426614174000";
  private static final UUID PROJECT_ID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
  private static final UUID ACTOR_ID = UUID.fromString("323e4567-e89b-12d3-a456-426614174000");

  @Test
  @SuppressWarnings("unchecked")
  void approvalEmitsRunPlanApprovedWithTheRealHumanActor() {
    listener.onDecisionCommitted(
        new PlanGateDecisionCommitted(
            RUN_ID,
            PROJECT_ID.toString(),
            PlanGateDecisionService.DecisionKind.APPROVE,
            ACTOR_ID.toString(),
            "owner@example.com",
            "Ada Lovelace",
            "Fixture run"));

    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(emitter)
        .emit(
            eq(PROJECT_ID),
            eq(ActivityEventType.RUN_PLAN_APPROVED),
            eq(ACTOR_ID), // the real decider — the emitter renders actorType=USER, not SYSTEM
            eq("RUN"),
            eq(RUN_ID),
            summary.capture(),
            payload.capture());
    assertThat(summary.getValue()).contains("Plan approved by owner@example.com", "Fixture run");
    assertThat(payload.getValue())
        .containsEntry("runId", RUN_ID)
        .containsEntry("decision", "APPROVE")
        .containsEntry("actorUserId", ACTOR_ID.toString())
        .containsEntry("actorEmail", "owner@example.com")
        .containsEntry("actorDisplayName", "Ada Lovelace");
  }

  @Test
  void rejectionEmitsRunPlanRejected() {
    listener.onDecisionCommitted(
        new PlanGateDecisionCommitted(
            RUN_ID,
            PROJECT_ID.toString(),
            PlanGateDecisionService.DecisionKind.REJECT,
            ACTOR_ID.toString(),
            "owner@example.com",
            null,
            null));

    verify(emitter)
        .emit(
            eq(PROJECT_ID),
            eq(ActivityEventType.RUN_PLAN_REJECTED),
            eq(ACTOR_ID),
            eq("RUN"),
            eq(RUN_ID),
            eq("Plan rejected by owner@example.com"),
            any(Map.class));
  }

  @Test
  void standaloneRunWithoutProjectIsSkipped() {
    listener.onDecisionCommitted(
        new PlanGateDecisionCommitted(
            RUN_ID,
            null,
            PlanGateDecisionService.DecisionKind.APPROVE,
            ACTOR_ID.toString(),
            "owner@example.com",
            null,
            null));

    verifyNoInteractions(emitter);
  }

  @Test
  void cancelDecisionIsNotACustomerPlanDecisionEvent() {
    listener.onDecisionCommitted(
        new PlanGateDecisionCommitted(
            RUN_ID,
            PROJECT_ID.toString(),
            PlanGateDecisionService.DecisionKind.CANCEL,
            ACTOR_ID.toString(),
            "owner@example.com",
            null,
            null));

    verifyNoInteractions(emitter);
  }

  @Test
  void planDecisionEventsAreDualSurfaceSoTheAuditChainCarriesTheActor() {
    // The activity_audit hash-chain (V292) is the org-level compliance surface; plan decisions are
    // governance/spend decisions and — like MEMBER_ROLE_CHANGED — must land on BOTH surfaces.
    for (ActivityEventType t :
        java.util.List.of(
            ActivityEventType.RUN_PLAN_APPROVED, ActivityEventType.RUN_PLAN_REJECTED)) {
      assertThat(t.surfaces())
          .containsExactlyInAnyOrder(
              ActivityEventType.Surface.ACTIVITY, ActivityEventType.Surface.AUDIT);
      assertThat(t.tier()).isEqualTo(ActivityEventType.Tier.HIGH_SIGNAL);
    }
  }
}
