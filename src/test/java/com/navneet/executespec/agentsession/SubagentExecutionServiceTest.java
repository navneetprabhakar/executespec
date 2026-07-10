package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.BudgetState;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Completion;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Handle;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Result;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Role;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.SpawnRequest;
import com.navneet.executespec.agentsession.subagent.SubagentProtocolService;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.specchat.SpecChatService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SubagentExecutionServiceTest {

  private final SubagentProtocolService protocol = mock(SubagentProtocolService.class);
  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final AgentTurnRepository turns = mock(AgentTurnRepository.class);
  private final AgentOrchestratorTurnService orchestrator =
      mock(AgentOrchestratorTurnService.class);
  private final SpecChatService chat = mock(SpecChatService.class);
  private final RunCostTracker costs = mock(RunCostTracker.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final AppUserDetails user =
      new AppUserDetails(
          AppUser.builder()
              .id("user")
              .email("u@example.com")
              .firstName("U")
              .status("ACTIVE")
              .build(),
          null);

  private SubagentExecutionService service;
  private SpawnRequest request;
  private Handle running;
  private AgentSession child;

  @BeforeEach
  void setUp() {
    service =
        new SubagentExecutionService(protocol, sessions, turns, orchestrator, chat, costs, flags);
    request = new SpawnRequest("req", "parent", "parent-turn", Role.VERIFIER, "check auth", 4, 0.5);
    running =
        new Handle(
            "child", "parent", "parent-turn", Role.VERIFIER, "check auth", 1, 4, 0.5, "RUNNING");
    child =
        AgentSession.builder()
            .id("child")
            .orgId("org")
            .userId("user")
            .projectId("project")
            .runId("run")
            .title("verifier")
            .status("RUNNING")
            .defaultProvider("anthropic")
            .build();
    when(protocol.spawn(request, "user", "org")).thenReturn(running);
    when(protocol.start("child", "user", "org")).thenReturn(running);
    when(sessions.findById("child")).thenReturn(Optional.of(child));
    when(orchestrator.orchestrate(eq(child), any(), eq(user)))
        .thenReturn(
            new OrchestratorTurnResponse(
                "route",
                "READ_ONLY_CODE_HELP",
                1.0,
                "read_only",
                false,
                "inspect",
                null,
                new OrchestratorPolicy(false, false, "read only"),
                "trace"));
    when(turns.countBySessionId("child")).thenReturn(1L, 1L);
    when(protocol.authorizeNextTurn("child", "user", "org"))
        .thenReturn(new BudgetState("child", 1, 4, 0, 0.5, true, null));
    when(costs.snapshot("child")).thenReturn(new RunCostTracker.Snapshot(20, 10, 0, 0, 0.02, 1));
  }

  @Test
  void coordinatorExecutionRequiresOptInAndFlag() {
    var coordinator =
        new SubagentProtocol.CoordinatorExecutionRequest(request, false, "COORDINATOR", null);
    assertThatThrownBy(() -> service.executeCoordinator(coordinator, user, "org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("automatic coordinator opt-in");

    var optedIn =
        new SubagentProtocol.CoordinatorExecutionRequest(request, true, "COORDINATOR", null);
    when(flags.isEnabled(SubagentExecutionService.COORDINATOR_AUTO_FLAG)).thenReturn(false);
    assertThatThrownBy(() -> service.executeCoordinator(optedIn, user, "org"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disabled");
  }

  @Test
  void coordinatorExecutionRefreshesEligibilityAndRecordsTrigger() {
    var optedIn =
        new SubagentProtocol.CoordinatorExecutionRequest(request, true, "COORDINATOR", null);
    when(flags.isEnabled(SubagentExecutionService.COORDINATOR_AUTO_FLAG)).thenReturn(true);
    when(protocol.evaluateDelegation(request, "user", "org"))
        .thenReturn(
            new SubagentProtocol.DelegationDecision(
                "parent", Role.VERIFIER, true, "eligible", 1, 0, 4, 0, 0, null));
    when(protocol.spawnAndRecordCoordinator(request, "user", "org")).thenReturn(running);
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              Runnable beforeRound = invocation.getArgument(10);
              beforeRound.run();
              sink.token("Verified src/AuthService.java:42");
              sink.done(1, new RunCostTracker.Snapshot(20, 10, 0, 0, 0.02, 1));
              return null;
            })
        .when(chat)
        .runTurnBounded(
            any(),
            eq(user),
            eq("child"),
            eq("project"),
            eq("run"),
            eq("org"),
            any(),
            eq("anthropic"),
            any(),
            eq(3),
            any(),
            eq("parent-turn"));
    when(protocol.complete(eq("child"), any(), eq("user"), eq("org")))
        .thenReturn(
            new Result(
                running,
                "Verified src/AuthService.java:42",
                java.util.List.of(
                    new com.navneet.executespec.agentsession.subagent.SubagentProtocol.Evidence(
                        "src/AuthService.java:42", "evidence")),
                2,
                0.02,
                Instant.now()));

    Result result = service.executeCoordinator(optedIn, user, "org");

    assertThat(result.summary()).contains("Verified");
    verify(protocol).spawnAndRecordCoordinator(request, "user", "org");
    verify(protocol).spawn(request, "user", "org");
  }

  @Test
  void verifierRunsOnlyReadToolsAndPersistsCitedCompletion() {
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              Runnable beforeRound = invocation.getArgument(10);
              beforeRound.run();
              sink.token("Verified guard at src/AuthService.java:42");
              sink.toolCall("search_repo_files", "{}", "tc1");
              sink.toolResult("tc1", "src/AuthService.java:42: requireRole");
              sink.done(1, new RunCostTracker.Snapshot(20, 10, 0, 0, 0.02, 1));
              return null;
            })
        .when(chat)
        .runTurnBounded(
            any(),
            eq(user),
            eq("child"),
            eq("project"),
            eq("run"),
            eq("org"),
            any(),
            eq("anthropic"),
            any(),
            eq(3),
            any(),
            eq("parent-turn"));
    when(protocol.complete(eq("child"), any(), eq("user"), eq("org")))
        .thenAnswer(
            invocation -> {
              Completion completion = invocation.getArgument(1);
              return new Result(
                  new Handle(
                      "child",
                      "parent",
                      "parent-turn",
                      Role.VERIFIER,
                      "check auth",
                      1,
                      4,
                      0.5,
                      "COMPLETED"),
                  completion.summary(),
                  completion.evidence(),
                  2,
                  0.02,
                  Instant.now());
            });

    Result result = service.execute(request, user, "org");

    assertThat(result.evidence())
        .extracting(evidence -> evidence.citation())
        .containsExactly("src/AuthService.java:42");
    ArgumentCaptor<Set<String>> tools = ArgumentCaptor.forClass(Set.class);
    verify(chat)
        .runTurnBounded(
            any(),
            eq(user),
            eq("child"),
            eq("project"),
            eq("run"),
            eq("org"),
            any(),
            eq("anthropic"),
            tools.capture(),
            eq(3),
            any(),
            eq("parent-turn"));
    assertThat(tools.getValue()).contains("search_repo_files", "search_code");
    assertThat(tools.getValue()).doesNotContain("submit_run", "approve_plan", "shell");
    ArgumentCaptor<AgentTurn> persisted = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turns).save(persisted.capture());
    assertThat(persisted.getValue().getIntentType()).isEqualTo("SUBAGENT_VERIFIER");
    assertThat(persisted.getValue().getCostUsd()).isEqualTo(0.02);
  }

  @Test
  void cancellationFromBoundedLoopPropagatesToProtocol() {
    doAnswer(
            invocation -> {
              throw new CancellationException("parent cancelled");
            })
        .when(chat)
        .runTurnBounded(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(Integer.class),
            any(),
            eq("parent-turn"));

    assertThatThrownBy(() -> service.execute(request, user, "org"))
        .isInstanceOf(CancellationException.class)
        .hasMessageContaining("parent cancelled");
    verify(protocol).cancel(eq("child"), any(), eq("user"), eq("org"));
  }
}
