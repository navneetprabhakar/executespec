package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
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
    when(turns.findById(any()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    AgentTurn.builder()
                        .id(invocation.getArgument(0))
                        .sessionId("parent")
                        .orgId("org")
                        .userId("user")
                        .sequence(1)
                        .intentType("CHAT")
                        .intentConfidence(1.0)
                        .build()));
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
    verify(protocol).start("child", "user", "org");
    org.mockito.Mockito.verify(protocol, org.mockito.Mockito.never()).spawn(request, "user", "org");
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
  void durableChildCompletesOperationOnlyAfterTurnsAndChildResultCommit() {
    LlmExecutionOperationService operationService = mock(LlmExecutionOperationService.class);
    LlmExecutionOperationCoordinator coordinator =
        new LlmExecutionOperationCoordinator(operationService);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-child", LlmExecutionScope.customer("org"));
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-child", 1, 1, java.util.UUID.randomUUID());
    when(operationService.claimOrResume(any(), any(), any(), any())).thenReturn(Optional.of(claim));
    when(operationService.complete(eq(claim), eq("subagent-test"))).thenReturn(true);
    var lease =
        coordinator
            .claimOrResume(
                reference,
                LlmExecutionOperationDescriptor.of(
                    "agent-turn", "AGENT_TURN", "child|parent-turn|objective", 1),
                "subagent-test",
                Duration.ofMinutes(30))
            .orElseThrow();
    SubagentExecutionService durable =
        new SubagentExecutionService(
            protocol, sessions, turns, orchestrator, chat, costs, flags, coordinator);
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              Runnable beforeRound = invocation.getArgument(10);
              java.util.function.Consumer<SpecChatService.DurableTurnOperation> consumer =
                  invocation.getArgument(12);
              consumer.accept(new SpecChatService.DurableTurnOperation(reference, lease));
              beforeRound.run();
              sink.token("Verified src/AuthService.java:42");
              sink.done(1, new RunCostTracker.Snapshot(20, 10, 0, 0, 0.02, 1));
              return null;
            })
        .when(chat)
        .runDurableTurnBounded(
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
            eq("parent-turn"),
            any());
    when(protocol.complete(eq("child"), any(), eq("user"), eq("org")))
        .thenAnswer(
            invocation -> {
              Completion completion = invocation.getArgument(1);
              return new Result(
                  running, completion.summary(), completion.evidence(), 2, 0.02, Instant.now());
            });

    durable.execute(request, user, "org");

    var order = org.mockito.Mockito.inOrder(turns, protocol, operationService);
    order.verify(turns, org.mockito.Mockito.atLeastOnce()).save(any(AgentTurn.class));
    order.verify(protocol).complete(eq("child"), any(), eq("user"), eq("org"));
    order.verify(operationService).complete(claim, "subagent-test");
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

  @Test
  void batchReservesChildrenThenExecutesSiblingsConcurrently() throws Exception {
    SpawnRequest second =
        new SpawnRequest("req-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 4, 0.5);
    Handle secondHandle =
        new Handle(
            "child-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 1, 4, 0.5, "RUNNING");
    when(protocol.spawn(second, "user", "org")).thenReturn(secondHandle);
    when(protocol.start("child-2", "user", "org")).thenReturn(secondHandle);
    Result firstResult = new Result(running, "first", java.util.List.of(), 1, 0.01, Instant.now());
    Result secondResult =
        new Result(secondHandle, "second", java.util.List.of(), 1, 0.01, Instant.now());
    SubagentExecutionService spy = org.mockito.Mockito.spy(service);
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    doAnswer(
            invocation -> {
              int now = active.incrementAndGet();
              maximum.accumulateAndGet(now, Math::max);
              entered.countDown();
              assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
              active.decrementAndGet();
              return invocation.getArgument(0, SpawnRequest.class).requestId().equals("req")
                  ? firstResult
                  : secondResult;
            })
        .when(spy)
        .executeReserved(any(SpawnRequest.class), any(Handle.class), eq(user), eq("org"));

    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var future =
          worker.submit(() -> spy.executeBatch(java.util.List.of(request, second), user, "org"));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      var batch = future.get(2, TimeUnit.SECONDS);
      assertThat(batch.results()).extracting(Result::summary).containsExactly("first", "second");
      assertThat(batch.failures()).isEmpty();
      assertThat(maximum.get()).isEqualTo(2);
      verify(protocol, org.mockito.Mockito.times(1)).spawn(request, "user", "org");
      verify(protocol, org.mockito.Mockito.times(1)).spawn(second, "user", "org");
      verify(protocol, org.mockito.Mockito.times(1)).start("child-2", "user", "org");
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void coordinatorFlagMovesBatchThroughFreshEligibilityAndAuditedReservation() {
    SpawnRequest second =
        new SpawnRequest("req-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 4, 0.5);
    Handle secondHandle =
        new Handle(
            "child-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 1, 4, 0.5, "RUNNING");
    when(flags.isEnabled(SubagentExecutionService.COORDINATOR_AUTO_FLAG)).thenReturn(true);
    when(protocol.evaluateDelegation(any(), eq("user"), eq("org")))
        .thenReturn(
            new SubagentProtocol.DelegationDecision(
                "parent", Role.SCOUT, true, "eligible", 1, 0, 4, 0, 0, null));
    when(protocol.spawnAndRecordCoordinator(request, "user", "org")).thenReturn(running);
    when(protocol.spawnAndRecordCoordinator(second, "user", "org")).thenReturn(secondHandle);
    when(protocol.start("child", "user", "org")).thenReturn(running);
    when(protocol.start("child-2", "user", "org")).thenReturn(secondHandle);

    SubagentExecutionService spy = org.mockito.Mockito.spy(service);
    org.mockito.Mockito.doReturn(
            new Result(running, "first", java.util.List.of(), 1, 0.01, Instant.now()))
        .when(spy)
        .executeReserved(eq(request), eq(running), eq(user), eq("org"));
    org.mockito.Mockito.doReturn(
            new Result(secondHandle, "second", java.util.List.of(), 1, 0.01, Instant.now()))
        .when(spy)
        .executeReserved(eq(second), eq(secondHandle), eq(user), eq("org"));

    var batch = spy.executeBatch(java.util.List.of(request, second), user, "org");

    assertThat(batch.failures()).isEmpty();
    assertThat(batch.results()).extracting(Result::summary).containsExactly("first", "second");
    verify(protocol).evaluateDelegation(request, "user", "org");
    verify(protocol).evaluateDelegation(second, "user", "org");
    verify(protocol).spawnAndRecordCoordinator(request, "user", "org");
    verify(protocol).spawnAndRecordCoordinator(second, "user", "org");
    verify(protocol, org.mockito.Mockito.never()).spawn(any(), eq("user"), eq("org"));
  }

  @Test
  void batchRejectsForeignParentTurnBeforeReservingAnyChild() {
    SpawnRequest foreign =
        new SpawnRequest("req-foreign", "parent", "foreign-turn", Role.SCOUT, "scan", 2, 0.1);
    when(turns.findById("foreign-turn"))
        .thenReturn(
            Optional.of(
                AgentTurn.builder()
                    .id("foreign-turn")
                    .sessionId("another-parent")
                    .orgId("org")
                    .userId("user")
                    .sequence(1)
                    .intentType("CHAT")
                    .intentConfidence(1.0)
                    .build()));

    assertThatThrownBy(() -> service.executeBatch(java.util.List.of(request, foreign), user, "org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parentTurnId does not belong to parent session");
    verify(protocol, org.mockito.Mockito.never()).spawn(any(), eq("user"), eq("org"));
  }

  @Test
  void batchRetainsCompletedSuccessWhenLaterSiblingFails() throws Exception {
    SpawnRequest second =
        new SpawnRequest("req-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 4, 0.5);
    Handle secondHandle =
        new Handle(
            "child-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 1, 4, 0.5, "RUNNING");
    when(protocol.spawn(second, "user", "org")).thenReturn(secondHandle);
    when(protocol.start("child-2", "user", "org")).thenReturn(secondHandle);
    Result secondResult =
        new Result(secondHandle, "second", java.util.List.of(), 1, 0.01, Instant.now());
    CountDownLatch secondCompleted = new CountDownLatch(1);
    SubagentExecutionService spy = org.mockito.Mockito.spy(service);
    doAnswer(
            invocation -> {
              SpawnRequest candidate = invocation.getArgument(0, SpawnRequest.class);
              if (candidate.requestId().equals("req-2")) {
                secondCompleted.countDown();
                return secondResult;
              }
              assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();
              throw new IllegalStateException("first child failed");
            })
        .when(spy)
        .executeReserved(any(SpawnRequest.class), any(Handle.class), eq(user), eq("org"));

    // Put the successful child first so executeBatch has observed its terminal result before the
    // later sibling fails. The separate interruption test below covers an earlier failure while a
    // sibling is still genuinely in flight.
    var batch = spy.executeBatch(java.util.List.of(second, request), user, "org");

    assertThat(batch.results()).extracting(Result::summary).containsExactly("second");
    assertThat(batch.failures())
        .extracting(SubagentProtocol.BatchFailure::requestId)
        .containsExactly("req");
    assertThat(batch.failures().getFirst().reason()).isEqualTo("first child failed");
  }

  @Test
  void batchInterruptsAnInFlightSiblingAfterTheFirstChildFails() throws Exception {
    SpawnRequest second =
        new SpawnRequest("req-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 4, 0.5);
    Handle secondHandle =
        new Handle(
            "child-2", "parent", "parent-turn", Role.SCOUT, "locate routes", 1, 4, 0.5, "RUNNING");
    when(protocol.spawn(second, "user", "org")).thenReturn(secondHandle);
    when(protocol.start("child-2", "user", "org")).thenReturn(secondHandle);

    CountDownLatch secondEntered = new CountDownLatch(1);
    CountDownLatch secondInterrupted = new CountDownLatch(1);
    SubagentExecutionService spy = org.mockito.Mockito.spy(service);
    doAnswer(
            invocation -> {
              SpawnRequest candidate = invocation.getArgument(0, SpawnRequest.class);
              if (candidate.requestId().equals("req")) {
                assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("first child failed");
              }
              secondEntered.countDown();
              try {
                new CountDownLatch(1).await();
                return new Result(
                    secondHandle, "unexpected", java.util.List.of(), 1, 0.01, Instant.now());
              } catch (InterruptedException interrupted) {
                secondInterrupted.countDown();
                throw new CancellationException("sibling interrupted");
              }
            })
        .when(spy)
        .executeReserved(any(SpawnRequest.class), any(Handle.class), eq(user), eq("org"));

    var batch = spy.executeBatch(java.util.List.of(request, second), user, "org");

    assertThat(secondInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(batch.results()).isEmpty();
    assertThat(batch.failures())
        .extracting(SubagentProtocol.BatchFailure::requestId)
        .containsExactly("req", "req-2");
    assertThat(batch.failures().get(1).reason()).isEqualTo("cancelled after sibling failure");
    verify(protocol, org.mockito.Mockito.atLeastOnce())
        .cancel(eq("child"), any(), eq("user"), eq("org"));
    verify(protocol, org.mockito.Mockito.atLeastOnce())
        .cancel(eq("child-2"), any(), eq("user"), eq("org"));
  }
}
