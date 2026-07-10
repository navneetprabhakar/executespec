package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
