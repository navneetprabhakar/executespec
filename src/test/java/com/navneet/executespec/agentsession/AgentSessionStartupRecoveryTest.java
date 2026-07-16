package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AgentSessionStartupRecoveryTest {

  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final AgentEventRecorder events = mock(AgentEventRecorder.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final AgentSessionStartupRecovery recovery =
      new AgentSessionStartupRecovery(sessions, events, metrics);

  @Test
  void staleRunningChildrenAreCancelledAndLeaveDurableRecoveryEvidence() {
    AgentSession child =
        AgentSession.builder()
            .id("child-1")
            .orgId("org-1")
            .userId("user-1")
            .title("scout")
            .status("RUNNING")
            .updatedAt(Instant.now().minusSeconds(300))
            .build();
    when(sessions.findByStatusAndClientSurface("RUNNING", "SUBAGENT")).thenReturn(List.of(child));
    when(sessions.findByIdForUpdate("child-1")).thenReturn(java.util.Optional.of(child));

    assertThat(recovery.reconcileRunningSessions()).isEqualTo(1);
    assertThat(child.getStatus()).isEqualTo("CANCELLED");
    assertThat(child.getUpdatedAt()).isNotNull();
    verify(sessions).save(child);
    ArgumentCaptor<String> actionId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(events)
        .appendAction(
            eq("child-1"), actionId.capture(), eq("SUBAGENT_RECOVERY"), payload.capture());
    assertThat(actionId.getValue()).startsWith("startup-recovery:");
    assertThat(actionId.getValue()).contains(payload.getValue().get("recoveryBatchId").toString());
  }

  @Test
  void staleReadyChildrenAreCancelledSoAnUnstartedChildCannotReserveBudgetForever() {
    AgentSession child =
        AgentSession.builder()
            .id("ready-child-1")
            .orgId("org-1")
            .userId("user-1")
            .title("scout")
            .status("READY")
            .updatedAt(Instant.now().minusSeconds(300))
            .build();
    when(sessions.findByStatusAndClientSurface("READY", "SUBAGENT")).thenReturn(List.of(child));
    when(sessions.findByIdForUpdate("ready-child-1")).thenReturn(java.util.Optional.of(child));

    assertThat(recovery.reconcileRunningSessions()).isEqualTo(1);
    assertThat(child.getStatus()).isEqualTo("CANCELLED");
    verify(sessions).save(child);
    verify(events)
        .appendAction(
            eq("ready-child-1"),
            org.mockito.ArgumentMatchers.argThat(
                actionId -> actionId.startsWith("startup-recovery:")),
            eq("SUBAGENT_RECOVERY"),
            org.mockito.ArgumentMatchers.argThat(
                payload -> payload.toString().contains("previousStatus=READY")));
  }

  @Test
  void emptyRunningSetIsAStableNoOp() {
    when(sessions.findByStatusAndClientSurface("RUNNING", "SUBAGENT")).thenReturn(List.of());
    assertThat(recovery.reconcileRunningSessions()).isZero();
  }

  @Test
  void liveRunningSessionIsNotCancelledByDelayedStartupRecovery() {
    AgentSession live =
        AgentSession.builder()
            .id("live-1")
            .orgId("org-1")
            .userId("user-1")
            .title("scout")
            .status("RUNNING")
            .updatedAt(Instant.now())
            .build();
    when(sessions.findByStatusAndClientSurface("RUNNING", "SUBAGENT")).thenReturn(List.of(live));
    when(sessions.findByIdForUpdate("live-1")).thenReturn(java.util.Optional.of(live));

    assertThat(recovery.reconcileRunningSessions()).isZero();
    assertThat(live.getStatus()).isEqualTo("RUNNING");
    verify(sessions, org.mockito.Mockito.never()).save(live);
    verify(events, org.mockito.Mockito.never())
        .appendAction(
            eq("live-1"),
            org.mockito.ArgumentMatchers.any(),
            eq("SUBAGENT_RECOVERY"),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void peerTerminalTransitionWinsBeforeRecoveryWrites() {
    AgentSession staleSnapshot =
        AgentSession.builder()
            .id("peer-1")
            .orgId("org-1")
            .userId("user-1")
            .title("scout")
            .status("RUNNING")
            .updatedAt(Instant.now().minusSeconds(300))
            .build();
    AgentSession peerState =
        AgentSession.builder()
            .id("peer-1")
            .orgId("org-1")
            .userId("user-1")
            .title("scout")
            .status("COMPLETED")
            .updatedAt(Instant.now())
            .build();
    when(sessions.findByStatusAndClientSurface("RUNNING", "SUBAGENT"))
        .thenReturn(List.of(staleSnapshot));
    when(sessions.findByIdForUpdate("peer-1")).thenReturn(java.util.Optional.of(peerState));

    assertThat(recovery.reconcileRunningSessions()).isZero();
    verify(sessions, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    verify(events, org.mockito.Mockito.never())
        .appendAction(
            eq("peer-1"),
            org.mockito.ArgumentMatchers.any(),
            eq("SUBAGENT_RECOVERY"),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void startupRecoveryNeverCancelsAStaleNonSubagentSession() {
    AgentSession ordinary =
        AgentSession.builder()
            .id("web-running")
            .orgId("org-1")
            .userId("user-1")
            .title("web conversation")
            .clientSurface("WEB")
            .status("RUNNING")
            .updatedAt(Instant.now().minusSeconds(300))
            .build();
    // Repository query is surface-scoped; a non-child row must never enter the recovery loop.
    when(sessions.findByStatusAndClientSurface("RUNNING", "SUBAGENT")).thenReturn(List.of());

    assertThat(recovery.reconcileRunningSessions()).isZero();
    assertThat(ordinary.getStatus()).isEqualTo("RUNNING");
    verify(sessions, org.mockito.Mockito.never()).findByIdForUpdate("web-running");
    verify(sessions, org.mockito.Mockito.never()).save(ordinary);
  }

  @Test
  void recoveryEventExplainsMissingHeartbeatAndCorrelatesTheStartupSweep() {
    AgentSession child =
        AgentSession.builder()
            .id("legacy-child")
            .orgId("org-1")
            .userId("user-1")
            .title("scout")
            .status("RUNNING")
            // Legacy rows may not have a heartbeat; the fail-closed cancellation must be auditable.
            .updatedAt(null)
            .build();
    when(sessions.findByStatusAndClientSurface("RUNNING", "SUBAGENT")).thenReturn(List.of(child));
    when(sessions.findByIdForUpdate("legacy-child")).thenReturn(java.util.Optional.of(child));

    assertThat(recovery.reconcileRunningSessions()).isEqualTo(1);

    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(events)
        .appendAction(
            eq("legacy-child"),
            org.mockito.ArgumentMatchers.argThat(
                actionId -> actionId.startsWith("startup-recovery:")),
            eq("SUBAGENT_RECOVERY"),
            payload.capture());
    assertThat(payload.getValue())
        .containsEntry("clientSurface", "SUBAGENT")
        .containsEntry("staleAfterSeconds", 120L)
        .containsEntry("staleTimestampPresent", false)
        .containsEntry("previousStatus", "RUNNING")
        .containsKey("recoveryBatchId");
  }

  @Test
  void recoveryPublishesOutcomeCountersAndSweepDuration() {
    when(sessions.findByStatusAndClientSurface("RUNNING", "SUBAGENT")).thenReturn(List.of());
    when(sessions.findByStatusAndClientSurface("READY", "SUBAGENT")).thenReturn(List.of());

    assertThat(recovery.reconcileRunningSessions()).isZero();

    assertThat(metrics.get("executespec.agent_session.startup_recovery.sweeps").counter().count())
        .isEqualTo(1);
    assertThat(
            metrics
                .get("executespec.agent_session.startup_recovery.outcomes")
                .tag("outcome", "empty")
                .tag("previous_status", "RUNNING")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            metrics
                .get("executespec.agent_session.startup_recovery.outcomes")
                .tag("outcome", "empty")
                .tag("previous_status", "READY")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(metrics.get("executespec.agent_session.startup_recovery.duration").timer().count())
        .isEqualTo(1);
  }
}
