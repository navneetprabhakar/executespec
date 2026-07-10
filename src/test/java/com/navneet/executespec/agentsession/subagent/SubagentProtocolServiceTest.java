package com.navneet.executespec.agentsession.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Completion;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Evidence;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Role;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.SpawnRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SubagentProtocolServiceTest {

  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final AgentActionEventRepository events = mock(AgentActionEventRepository.class);
  private final AgentTurnRepository turns = mock(AgentTurnRepository.class);
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  private SubagentProtocolService service;

  @BeforeEach
  void setUp() {
    service = new SubagentProtocolService(sessions, events, turns, json);
    when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void spawnPersistsBoundedChildAndBidirectionalLinks() {
    AgentSession parent = parent("parent", "ACTIVE");
    when(sessions.findById("parent")).thenReturn(Optional.of(parent));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "parent", SubagentProtocolService.EVENT_CHILD_CREATED))
        .thenReturn(List.of());
    when(events.countBySessionIdAndType("parent", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(0L);

    var handle =
        service.spawn(
            new SpawnRequest("req-1", "parent", "turn-1", Role.SCOUT, "Locate auth flow", 5, 0.5),
            "user",
            "org");

    assertThat(handle.parentSessionId()).isEqualTo("parent");
    assertThat(handle.depth()).isEqualTo(1);
    assertThat(handle.status()).isEqualTo("READY");
    assertThat(handle.role()).isEqualTo(Role.SCOUT);
    ArgumentCaptor<AgentSession> child = ArgumentCaptor.forClass(AgentSession.class);
    verify(sessions).save(child.capture());
    assertThat(child.getValue().getClientSurface()).isEqualTo("SUBAGENT");
    assertThat(child.getValue().getProjectId()).isEqualTo("project");
    ArgumentCaptor<AgentActionEvent> persisted = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(events, org.mockito.Mockito.times(2)).save(persisted.capture());
    assertThat(persisted.getAllValues())
        .extracting(AgentActionEvent::getType)
        .containsExactly(
            SubagentProtocolService.EVENT_CHILD_CREATED,
            SubagentProtocolService.EVENT_CHILD_SPAWNED);
  }

  @Test
  void evaluateDelegation_isSideEffectFreeAndReportsCapacity() {
    AgentSession parent = parent("parent", "ACTIVE");
    when(sessions.findById("parent")).thenReturn(Optional.of(parent));
    when(events.countBySessionIdAndType("parent", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(1L);
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

    var decision =
        service.evaluateDelegation(
            new SpawnRequest(
                "req", "parent", "turn", Role.SCOUT, "locate mobile reconnect", 4, 0.5),
            "user",
            "org");

    assertThat(decision.eligible()).isTrue();
    assertThat(decision.reason()).isEqualTo("eligible");
    assertThat(decision.depth()).isEqualTo(1);
    assertThat(decision.existingChildren()).isEqualTo(1);
    assertThat(decision.remainingChildren()).isEqualTo(3);
    assertThat(decision.childSessionId()).isNull();
    verify(sessions, org.mockito.Mockito.never()).save(any());
    verify(events, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void evaluateDelegation_rejectsInactiveParentWithoutSideEffects() {
    AgentSession parent = parent("parent", "COMPLETED");
    when(sessions.findById("parent")).thenReturn(Optional.of(parent));
    when(events.countBySessionIdAndType("parent", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(0L);
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

    var decision =
        service.evaluateDelegation(
            new SpawnRequest("req", "parent", "turn", Role.VERIFIER, "verify reconnect", 2, 0.2),
            "user",
            "org");

    assertThat(decision.eligible()).isFalse();
    assertThat(decision.reason()).isEqualTo("parent_inactive");
    verify(sessions, org.mockito.Mockito.never()).save(any());
    verify(events, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void spawnRejectsDepthBeyondHardLimit() throws Exception {
    AgentSession parent = parent("nested", "RUNNING");
    var metadata =
        new SubagentProtocolService.ChildMetadata("root", null, Role.SCOUT, "nested", 2, 4, 0.4);
    when(sessions.findById("nested")).thenReturn(Optional.of(parent));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "nested", SubagentProtocolService.EVENT_CHILD_CREATED))
        .thenReturn(
            List.of(event("nested", SubagentProtocolService.EVENT_CHILD_CREATED, metadata)));

    assertThatThrownBy(
            () ->
                service.spawn(
                    new SpawnRequest("req-deep", "nested", null, Role.VERIFIER, "verify", 2, 0.2),
                    "user",
                    "org"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("depth limit");
  }

  @Test
  void idempotentSpawnReturnsExistingChildEvenAtParentCapacity() throws Exception {
    AgentSession parent = parent("parent", "ACTIVE");
    SpawnRequest request =
        new SpawnRequest("same-request", "parent", null, Role.SCOUT, "scan", 3, 0.2);
    String childId =
        java.util
            .UUID
            .nameUUIDFromBytes(
                "subagent:parent:same-request".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    AgentSession child = parent(childId, "READY");
    var metadata =
        new SubagentProtocolService.ChildMetadata("parent", null, Role.SCOUT, "scan", 1, 3, 0.2);
    when(sessions.findById("parent")).thenReturn(Optional.of(parent));
    when(sessions.findById(childId)).thenReturn(Optional.of(child));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            childId, SubagentProtocolService.EVENT_CHILD_CREATED))
        .thenReturn(List.of(event(childId, SubagentProtocolService.EVENT_CHILD_CREATED, metadata)));
    when(events.countBySessionIdAndType("parent", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn((long) SubagentProtocolService.MAX_CHILDREN_PER_PARENT);

    var handle = service.spawn(request, "user", "org");

    assertThat(handle.childSessionId()).isEqualTo(childId);
    assertThat(handle.status()).isEqualTo("READY");
    verify(sessions, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void spawnRejectsUnboundedTurnAndCostRequests() {
    assertThatThrownBy(
            () ->
                service.spawn(
                    new SpawnRequest("req", "parent", null, Role.SCOUT, "scan", 13, 0.5),
                    "user",
                    "org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTurns");
    assertThatThrownBy(
            () ->
                service.spawn(
                    new SpawnRequest("req", "parent", null, Role.SCOUT, "scan", 3, 2.01),
                    "user",
                    "org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxCostUsd");
  }

  @Test
  void spawnEnforcesBudgetAcrossTheEntireDescendantTree() throws Exception {
    AgentSession parent = parent("parent", "ACTIVE");
    when(sessions.findById("parent")).thenReturn(Optional.of(parent));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "parent", SubagentProtocolService.EVENT_CHILD_CREATED))
        .thenReturn(List.of());
    when(events.countBySessionIdAndType("parent", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(3L);

    var direct =
        List.of(childLink("child-a", 2.0), childLink("child-b", 2.0), childLink("child-c", 2.0));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "parent", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(
            List.of(
                event("parent", "spawn", direct.get(0)),
                event("parent", "spawn", direct.get(1)),
                event("parent", "spawn", direct.get(2))));
    // One nested child consumes another reservation even though the direct child count is only 3.
    var nested = childLink("nested-a", 2.0);
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "child-a", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(List.of(event("child-a", "spawn", nested)));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "child-b", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(List.of());
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "child-c", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(List.of());
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "nested-a", SubagentProtocolService.EVENT_CHILD_SPAWNED))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.spawn(
                    new SpawnRequest("req-over-tree", "parent", null, Role.SCOUT, "scan", 1, 0.1),
                    "user",
                    "org"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("total-tree cost budget exceeded");
  }

  @Test
  void authorizeNextTurnFailsClosedAtPersistedBudget() throws Exception {
    AgentSession child = parent("child", "RUNNING");
    var metadata =
        new SubagentProtocolService.ChildMetadata("parent", "turn", Role.SCOUT, "scan", 1, 2, 0.25);
    when(sessions.findById("child")).thenReturn(Optional.of(child));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "child", SubagentProtocolService.EVENT_CHILD_CREATED))
        .thenReturn(List.of(event("child", SubagentProtocolService.EVENT_CHILD_CREATED, metadata)));
    when(turns.countBySessionId("child")).thenReturn(2L);
    when(turns.findBySessionIdOrderBySequenceAsc("child"))
        .thenReturn(List.of(turn(1, 0.10), turn(2, 0.10)));

    var budget = service.authorizeNextTurn("child", "user", "org");

    assertThat(budget.canContinue()).isFalse();
    assertThat(budget.reason()).isEqualTo("turn budget exhausted");
    assertThat(budget.turnsUsed()).isEqualTo(2);
  }

  @Test
  void verifierCompletionRequiresEvidenceAndPersistsHotHandoff() throws Exception {
    AgentSession child = parent("child", "RUNNING");
    var metadata =
        new SubagentProtocolService.ChildMetadata(
            "parent", "turn", Role.VERIFIER, "verify", 1, 3, 0.5);
    when(sessions.findById("child")).thenReturn(Optional.of(child));
    when(events.findBySessionIdAndTypeOrderByCreatedAtAsc(
            "child", SubagentProtocolService.EVENT_CHILD_CREATED))
        .thenReturn(List.of(event("child", SubagentProtocolService.EVENT_CHILD_CREATED, metadata)));
    when(turns.countBySessionId("child")).thenReturn(1L);
    when(turns.findBySessionIdOrderBySequenceAsc("child")).thenReturn(List.of(turn(1, 0.12)));

    assertThatThrownBy(
            () -> service.complete("child", new Completion("green", List.of()), "user", "org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires evidence");

    var result =
        service.complete(
            "child",
            new Completion("tests pass", List.of(new Evidence("src/Auth.java:42", "guarded"))),
            "user",
            "org");

    assertThat(result.child().status()).isEqualTo("COMPLETED");
    assertThat(result.turnsUsed()).isEqualTo(1);
    assertThat(result.costUsd()).isEqualTo(0.12);
    assertThat(result.evidence())
        .extracting(Evidence::citation)
        .containsExactly("src/Auth.java:42");
    ArgumentCaptor<AgentActionEvent> persisted = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(events, org.mockito.Mockito.times(2)).save(persisted.capture());
    assertThat(persisted.getAllValues())
        .extracting(AgentActionEvent::getSessionId)
        .containsExactly("child", "parent");
  }

  private AgentActionEvent event(String sessionId, String type, Object payload) throws Exception {
    return AgentActionEvent.builder()
        .id("event-" + sessionId + "-" + type)
        .sessionId(sessionId)
        .type(type)
        .payloadJson(json.writeValueAsString(payload))
        .createdAt(Instant.now())
        .build();
  }

  private static SubagentProtocolService.ParentLink childLink(String childId, double maxCostUsd) {
    return new SubagentProtocolService.ParentLink(
        childId,
        new SubagentProtocolService.ChildMetadata(
            "parent", null, Role.SCOUT, "scan", 1, 1, maxCostUsd));
  }

  private static AgentSession parent(String id, String status) {
    return AgentSession.builder()
        .id(id)
        .orgId("org")
        .userId("user")
        .projectId("project")
        .clientSurface("CLI")
        .title("session")
        .status(status)
        .defaultExecutionMode("LOCAL")
        .approvalPolicy("PLAN")
        .build();
  }

  private static AgentTurn turn(int sequence, double cost) {
    return AgentTurn.builder()
        .id("turn-" + sequence)
        .sessionId("child")
        .orgId("org")
        .userId("user")
        .sequence(sequence)
        .intentType("SUBAGENT")
        .costUsd(cost)
        .build();
  }
}
