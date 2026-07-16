package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentSessionHistoryServiceTest {

  @Test
  void clearDeletesTranscriptButRetainsSessionAndReturnsCounts() {
    AgentSessionReadService reads = mock(AgentSessionReadService.class);
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    AgentTurnRepository turns = mock(AgentTurnRepository.class);
    AgentActionEventRepository events = mock(AgentActionEventRepository.class);
    AgentSessionHistoryService service =
        new AgentSessionHistoryService(reads, sessions, turns, events);
    AppUserDetails user = mock(AppUserDetails.class);
    AgentSession session =
        AgentSession.builder().id("s1").title("old").updatedAt(Instant.MIN).build();
    when(reads.requireSession("s1", user)).thenReturn(session);
    when(turns.countBySessionId("s1")).thenReturn(3L);
    when(events.countBySessionId("s1")).thenReturn(7L);

    var result = service.clear("s1", user);

    assertThat(result.sessionId()).isEqualTo("s1");
    assertThat(result.turnsDeleted()).isEqualTo(3);
    assertThat(result.eventsDeleted()).isEqualTo(7);
    assertThat(result.clearedAt()).isEqualTo(session.getUpdatedAt());
    verify(events).deleteBySessionId("s1");
    verify(turns).deleteBySessionId("s1");
    verify(sessions).save(session);
    assertThat(session.getTitle()).isEqualTo("old");
  }

  @Test
  void ownershipFailureStopsBeforeAnyDelete() {
    AgentSessionReadService reads = mock(AgentSessionReadService.class);
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    AgentTurnRepository turns = mock(AgentTurnRepository.class);
    AgentActionEventRepository events = mock(AgentActionEventRepository.class);
    AgentSessionHistoryService service =
        new AgentSessionHistoryService(reads, sessions, turns, events);
    AppUserDetails user = mock(AppUserDetails.class);
    when(reads.requireSession("s1", user))
        .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.clear("s1", user))
        .isInstanceOf(ResponseStatusException.class);
    verify(events, never()).deleteBySessionId("s1");
    verify(turns, never()).deleteBySessionId("s1");
    verify(sessions, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void repeatedClearIsIdempotentAfterTranscriptAlreadyGone() {
    AgentSessionReadService reads = mock(AgentSessionReadService.class);
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    AgentTurnRepository turns = mock(AgentTurnRepository.class);
    AgentActionEventRepository events = mock(AgentActionEventRepository.class);
    AgentSessionHistoryService service =
        new AgentSessionHistoryService(reads, sessions, turns, events);
    AppUserDetails user = mock(AppUserDetails.class);
    AgentSession session = AgentSession.builder().id("s1").build();
    when(reads.requireSession("s1", user)).thenReturn(session);
    when(turns.countBySessionId("s1")).thenReturn(0L);
    when(events.countBySessionId("s1")).thenReturn(0L);

    var result = service.clear("s1", user);

    assertThat(result.turnsDeleted()).isZero();
    assertThat(result.eventsDeleted()).isZero();
    verify(events).deleteBySessionId("s1");
    verify(turns).deleteBySessionId("s1");
  }
}
