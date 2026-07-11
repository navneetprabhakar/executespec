package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.junit.jupiter.api.Test;

class AgentSessionStartupRecoveryTest {

  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final AgentEventRecorder events = mock(AgentEventRecorder.class);
  private final AgentSessionStartupRecovery recovery =
      new AgentSessionStartupRecovery(sessions, events);

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
    when(sessions.findByStatus("RUNNING")).thenReturn(List.of(child));

    assertThat(recovery.reconcileRunningSessions()).isEqualTo(1);
    assertThat(child.getStatus()).isEqualTo("CANCELLED");
    assertThat(child.getUpdatedAt()).isNotNull();
    verify(sessions).save(child);
    verify(events)
        .appendAction(
            eq("child-1"),
            eq("startup-recovery:child-1"),
            eq("SUBAGENT_RECOVERY"),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void emptyRunningSetIsAStableNoOp() {
    when(sessions.findByStatus("RUNNING")).thenReturn(List.of());
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
    when(sessions.findByStatus("RUNNING")).thenReturn(List.of(live));

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
}
