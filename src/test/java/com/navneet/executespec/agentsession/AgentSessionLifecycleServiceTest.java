package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocolService;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentSessionLifecycleServiceTest {

  @Mock AgentSessionRepository sessionRepository;
  @Mock AgentTurnRepository turnRepository;
  @Mock AgentActionEventRepository eventRepository;
  @Mock AgentEventRecorder eventRecorder;
  @Mock LocalHandsStateService localHandsStateService;
  @Mock SubagentProtocolService subagentProtocolService;
  @Mock PipelineRunRepository pipelineRunRepository;

  private AgentSessionLifecycleService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentSessionLifecycleService(
            sessionRepository,
            turnRepository,
            eventRepository,
            eventRecorder,
            localHandsStateService,
            subagentProtocolService,
            pipelineRunRepository);
  }

  @Test
  void archiveIsTenantAuthorizedIdempotentAndPreservesEvidence() {
    AgentSession session = session("ACTIVE");
    when(sessionRepository.findByIdForUpdate("session-1")).thenReturn(Optional.of(session));
    when(turnRepository.countBySessionId("session-1")).thenReturn(7L);
    when(eventRepository.countBySessionId("session-1")).thenReturn(11L, 12L);

    var first = service.archive("session-1", "finished", user());
    var replay = service.archive("session-1", "different reason", user());

    assertThat(first.state()).isEqualTo("ARCHIVED");
    assertThat(first.alreadyArchived()).isFalse();
    assertThat(first.archivedByUserId()).isEqualTo("user-1");
    assertThat(first.archiveReason()).isEqualTo("finished");
    assertThat(first.preservedTurns()).isEqualTo(7);
    assertThat(replay.alreadyArchived()).isTrue();
    assertThat(replay.archivedAt()).isEqualTo(first.archivedAt());
    assertThat(replay.archiveReason()).isEqualTo("finished");
    assertThat(session.getRunId()).isEqualTo("run-1");
    verify(sessionRepository).save(session);
    verify(eventRecorder)
        .append(
            org.mockito.ArgumentMatchers.eq("session-1"),
            org.mockito.ArgumentMatchers.eq("SESSION_ARCHIVED"),
            org.mockito.ArgumentMatchers.any());
    verify(turnRepository, never()).deleteBySessionId("session-1");
    verify(eventRepository, never()).deleteBySessionId("session-1");
  }

  @Test
  void archiveRejectsActiveStreamingTurn() {
    when(sessionRepository.findByIdForUpdate("session-1"))
        .thenReturn(Optional.of(session("ACTIVE")));
    when(turnRepository.existsBySessionIdAndStatus("session-1", "STREAMING")).thenReturn(true);

    assertThatThrownBy(() -> service.archive("session-1", null, user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("active streaming turn");
    verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void archiveRejectsPendingLocalToolAndWriterAuthority() {
    when(sessionRepository.findByIdForUpdate("session-1"))
        .thenReturn(Optional.of(session("ACTIVE")));
    when(localHandsStateService.hasPendingForSession("session-1")).thenReturn(true);

    assertThatThrownBy(() -> service.archive("session-1", null, user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("local tool result");

    when(localHandsStateService.hasPendingForSession("session-1")).thenReturn(false);
    when(subagentProtocolService.hasActiveWriterReservation("session-1")).thenReturn(true);
    assertThatThrownBy(() -> service.archive("session-1", null, user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("writer reservation");
  }

  @Test
  void archiveRejectsActiveChildAgent() {
    when(sessionRepository.findByIdForUpdate("session-1"))
        .thenReturn(Optional.of(session("ACTIVE")));
    when(subagentProtocolService.hasActiveChildren("session-1")).thenReturn(true);

    assertThatThrownBy(() -> service.archive("session-1", null, user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("active child agent");
    verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void archiveRejectsOwnerFromAnotherTenant() {
    AgentSession session = session("ACTIVE");
    session.setOrgId("other-org");
    when(sessionRepository.findByIdForUpdate("session-1")).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> service.archive("session-1", null, user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("forbidden");
  }

  @Test
  void archiveRejectsNonTerminalLinkedRun() {
    when(sessionRepository.findByIdForUpdate("session-1"))
        .thenReturn(Optional.of(session("ACTIVE")));
    when(pipelineRunRepository.findById("run-1"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-1").status("WORKING").build()));

    assertThatThrownBy(() -> service.archive("session-1", null, user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("linked run that has not finished");
    verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  private static AgentSession session(String status) {
    return AgentSession.builder()
        .id("session-1")
        .userId("user-1")
        .orgId("org-1")
        .runId("run-1")
        .title("Conversation")
        .status(status)
        .build();
  }

  private static AppUserDetails user() {
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setOrgId("org-1");
    user.setEmail("user@example.com");
    user.setGlobalRole("USER");
    return new AppUserDetails(user, null);
  }
}
