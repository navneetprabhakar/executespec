package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorMode;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.BatchFailure;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.BatchResult;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.DelegationDecision;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Evidence;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Handle;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Result;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Role;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.SpawnRequest;
import com.navneet.executespec.agentsession.subagent.SubagentProtocolService;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.specchat.SpecChatService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Executes one bounded, read-only persisted role-contract child-agent assignment. */
@Service
public class SubagentExecutionService {

  private static final int MAX_EXECUTION_ROUNDS = 4;

  private final SubagentProtocolService protocolService;
  private final AgentSessionRepository sessionRepository;
  private final AgentTurnRepository turnRepository;
  private final AgentOrchestratorTurnService orchestratorTurnService;
  private final SpecChatService specChatService;
  private final RunCostTracker costTracker;
  private final FeatureFlagService featureFlagService;
  private final LlmExecutionOperationCoordinator operationCoordinator;
  private final SubagentOperationLifecycle operationLifecycle;

  @Autowired
  public SubagentExecutionService(
      SubagentProtocolService protocolService,
      AgentSessionRepository sessionRepository,
      AgentTurnRepository turnRepository,
      AgentOrchestratorTurnService orchestratorTurnService,
      SpecChatService specChatService,
      RunCostTracker costTracker,
      FeatureFlagService featureFlagService,
      LlmExecutionOperationCoordinator operationCoordinator) {
    this.protocolService = protocolService;
    this.sessionRepository = sessionRepository;
    this.turnRepository = turnRepository;
    this.orchestratorTurnService = orchestratorTurnService;
    this.specChatService = specChatService;
    this.costTracker = costTracker;
    this.featureFlagService = featureFlagService;
    this.operationCoordinator = operationCoordinator;
    this.operationLifecycle = new SubagentOperationLifecycle(protocolService, operationCoordinator);
  }

  SubagentExecutionService(
      SubagentProtocolService protocolService,
      AgentSessionRepository sessionRepository,
      AgentTurnRepository turnRepository,
      AgentOrchestratorTurnService orchestratorTurnService,
      SpecChatService specChatService,
      RunCostTracker costTracker,
      FeatureFlagService featureFlagService) {
    this(
        protocolService,
        sessionRepository,
        turnRepository,
        orchestratorTurnService,
        specChatService,
        costTracker,
        featureFlagService,
        null);
  }

  public static final String COORDINATOR_AUTO_FLAG = "agent.subagents.coordinator-auto.enabled";

  /** Side-effect-free parent coordinator probe; no model call or child session is created. */
  public DelegationDecision evaluateDelegation(
      SpawnRequest request, AppUserDetails user, String orgId) {
    if (user == null || user.getUser() == null) {
      throw new SecurityException("authenticated user is required");
    }
    return protocolService.evaluateDelegation(request, user.getUser().getId(), orgId);
  }

  /** Spawn, policy-check, execute and persist a single child result. */
  public Result execute(SpawnRequest request, AppUserDetails user, String orgId) {
    if (user == null || user.getUser() == null) {
      throw new SecurityException("authenticated user is required");
    }
    String userId = user.getUser().getId();
    var handle = protocolService.spawn(request, userId, orgId);
    protocolService.start(handle.childSessionId(), userId, orgId);
    return executeReserved(request, handle, user, orgId);
  }

  /** Execute a child that was already reserved by the bounded batch path. */
  Result executeReserved(SpawnRequest request, Handle handle, AppUserDetails user, String orgId) {
    if (user == null || user.getUser() == null) {
      throw new SecurityException("authenticated user is required");
    }
    String userId = user.getUser().getId();
    AgentSession child =
        sessionRepository
            .findById(handle.childSessionId())
            .orElseThrow(() -> new IllegalStateException("Persisted child session disappeared"));

    // A single WORKHORSE round routinely outlives the startup-recovery stale window, and turn
    // admission is the only other heartbeat. Without this renewal a peer JVM booting mid-call
    // cancels a child that is actively spending, so keep the lease fresh for the whole execution.
    SubagentOperationLifecycle.Lease operation =
        operationLifecycle.start(child.getId(), userId, orgId);
    try {
      var route =
          orchestratorTurnService.orchestrate(
              child,
              new OrchestratorTurnRequest(
                  child.getId(),
                  child.getId() + "-route",
                  handle.objective(),
                  "SUBAGENT",
                  "2.0",
                  child.getProjectId(),
                  null,
                  null,
                  new OrchestratorClientCapabilities(
                      false, false, false, false, true, false, false, false),
                  new OrchestratorMode("read_only", "NONE", false),
                  List.of()),
              user);
      if (route.policy().runApiAllowed()) {
        throw new IllegalStateException("Subagent route attempted to acquire run authority");
      }

      long turnsBeforeModel = turnRepository.countBySessionId(child.getId());
      int remainingTurns = Math.toIntExact(Math.max(0, handle.maxTurns() - turnsBeforeModel));
      if (remainingTurns < 1)
        throw new SubagentBudgetExceededException(
            "SUBAGENT_TURN_BUDGET_EXHAUSTED", "Subagent turn budget exhausted");
      int maxRounds = Math.min(MAX_EXECUTION_ROUNDS, remainingTurns);
      AtomicInteger roundsStarted = new AtomicInteger();
      SubagentTurnSupport.CapturingSink sink = new SubagentTurnSupport.CapturingSink();
      String durableTurnId =
          handle.parentTurnId() == null || handle.parentTurnId().isBlank()
              ? child.getId() + ":subagent-execution"
              : handle.parentTurnId();
      List<Map<String, Object>> conversation =
          List.of(
              Map.of(
                  "role",
                  "user",
                  "content",
                  SubagentTurnSupport.rolePrompt(handle.role(), handle.objective())));

      Runnable beforeRound =
          () -> {
            var budget = protocolService.authorizeNextTurn(child.getId(), userId, orgId);
            if (!budget.canContinue()) {
              String code =
                  budget.reason().toLowerCase(java.util.Locale.ROOT).contains("cost")
                      ? "SUBAGENT_COST_BUDGET_EXHAUSTED"
                      : "SUBAGENT_TURN_BUDGET_EXHAUSTED";
              throw new SubagentBudgetExceededException(code, budget.reason());
            }
            if (roundsStarted.incrementAndGet() > maxRounds) {
              throw new SubagentBudgetExceededException(
                  "SUBAGENT_TURN_BUDGET_EXHAUSTED", "subagent round budget exhausted");
            }
            if (costTracker.snapshot(child.getId()).costUsd() >= handle.maxCostUsd()) {
              throw new SubagentBudgetExceededException(
                  "SUBAGENT_COST_BUDGET_EXHAUSTED", "subagent cost budget exhausted");
            }
          };
      if (operationCoordinator == null) {
        specChatService.runTurnBounded(
            sink,
            user,
            child.getId(),
            child.getProjectId(),
            child.getRunId(),
            orgId,
            conversation,
            child.getDefaultProvider(),
            AgentStreamTurnSupport.allowedReadOnlyTools(),
            maxRounds,
            beforeRound,
            durableTurnId);
      } else {
        specChatService.runDurableTurnBounded(
            sink,
            user,
            child.getId(),
            child.getProjectId(),
            child.getRunId(),
            orgId,
            conversation,
            child.getDefaultProvider(),
            AgentStreamTurnSupport.allowedReadOnlyTools(),
            maxRounds,
            beforeRound,
            durableTurnId,
            operation::bind);
      }

      RunCostTracker.Snapshot usage = sink.usage();
      if (usage.costUsd() > handle.maxCostUsd()) {
        throw new SubagentBudgetExceededException(
            "SUBAGENT_COST_BUDGET_EXHAUSTED", "subagent cost budget exceeded");
      }
      String summary = sink.summary();
      List<Evidence> evidence = SubagentTurnSupport.extractEvidence(summary, sink.toolEvidence());
      if (handle.role().contract().requiresEvidence() && evidence.isEmpty()) {
        throw new IllegalStateException(
            handle.role().name().toLowerCase(java.util.Locale.ROOT)
                + " returned no file:line evidence");
      }
      persistExecutionTurns(
          child, handle.role(), handle.objective(), summary, usage, sink.turnCount());
      Result completed =
          protocolService.complete(
              child.getId(), new SubagentProtocol.Completion(summary, evidence), userId, orgId);
      operationLifecycle.complete(operation);
      return completed;
    } catch (RuntimeException e) {
      try {
        protocolService.cancel(
            child.getId(),
            "execution stopped: " + SubagentTurnSupport.safeMessage(e),
            userId,
            orgId);
      } catch (RuntimeException ignored) {
        // Preserve the original execution failure; terminal/racing cancellation is harmless.
      }
      operationLifecycle.terminate(operation, e);
      throw e;
    } finally {
      operation.close();
    }
  }

  /**
   * Executes a small, bounded fan-out concurrently while reserving every child first.
   *
   * <p>Reservation happens serially through the protocol service so capacity and tree budgets are
   * enforced before any model call starts. The model turns then run on at most four workers. A
   * failed child cancels still-running siblings through the canonical parent cancellation path;
   * successful siblings and their durable evidence are returned to the caller.
   */
  public BatchResult executeBatch(List<SpawnRequest> requests, AppUserDetails user, String orgId) {
    if (user == null || user.getUser() == null) {
      throw new SecurityException("authenticated user is required");
    }
    validateBatch(requests);
    String parentSessionId = requests.getFirst().parentSessionId();
    List<Handle> reserved = new ArrayList<>(requests.size());
    boolean coordinatorAuto = featureFlagService.isEnabled(COORDINATOR_AUTO_FLAG);
    try {
      for (SpawnRequest request : requests) {
        Handle handle;
        if (coordinatorAuto) {
          // Keep model-requested fan-out on the same audited coordinator lane as the single-child
          // tool. Eligibility is a fresh, side-effect-free preflight; the transactional
          // spawnAndRecordCoordinator call repeats the hard limits and records the actual child
          // id, so a stale probe cannot create a false acceptance event.
          DelegationDecision decision = evaluateDelegation(request, user, orgId);
          if (!decision.eligible()) {
            throw new IllegalStateException(
                "coordinator delegation is not eligible: " + decision.reason());
          }
          handle =
              protocolService.spawnAndRecordCoordinator(request, user.getUser().getId(), orgId);
        } else {
          handle = protocolService.spawn(request, user.getUser().getId(), orgId);
        }
        protocolService.start(handle.childSessionId(), user.getUser().getId(), orgId);
        reserved.add(handle);
      }
    } catch (RuntimeException reservationFailure) {
      cancelReserved(reserved, reservationFailure, user, orgId);
      throw reservationFailure;
    }

    List<Result> results = new ArrayList<>();
    List<BatchFailure> failures = new ArrayList<>();
    List<Future<Result>> futures = new ArrayList<>(reserved.size());
    AtomicReferenceArray<Result> completedResults = new AtomicReferenceArray<>(reserved.size());
    try (ExecutorService executor = Executors.newFixedThreadPool(reserved.size())) {
      for (int index = 0; index < requests.size(); index++) {
        SpawnRequest request = requests.get(index);
        Handle handle = reserved.get(index);
        int resultIndex = index;
        futures.add(
            executor.submit(
                () -> {
                  Result result = executeReserved(request, handle, user, orgId);
                  // Publish a durable child success before FutureTask transitions to DONE. A
                  // sibling can fail in that narrow transition window; relying only on isDone()
                  // would then cancel this already-completed child and discard its result.
                  completedResults.set(resultIndex, result);
                  return result;
                }));
      }
      boolean failed = false;
      Set<Integer> processed = new LinkedHashSet<>();
      for (int index = 0; index < futures.size(); index++) {
        Future<Result> future = futures.get(index);
        try {
          results.add(future.get());
          processed.add(index);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          failed = true;
          failures.add(failure(requests.get(index), reserved.get(index), "batch interrupted"));
          processed.add(index);
          cancelReserved(reserved, interrupted, user, orgId);
          break;
        } catch (ExecutionException executionFailure) {
          failed = true;
          failures.add(
              failure(requests.get(index), reserved.get(index), rootMessage(executionFailure)));
          processed.add(index);
          cancelReserved(reserved, executionFailure, user, orgId);
          break;
        }
      }
      if (failed) {
        for (int index = 0; index < futures.size(); index++) {
          Future<Result> future = futures.get(index);
          if (processed.contains(index)) continue;
          Result completedResult = completedResults.get(index);
          if (completedResult != null) {
            results.add(completedResult);
            processed.add(index);
            continue;
          }
          if (!future.isDone()) {
            future.cancel(true);
            failures.add(
                failure(
                    requests.get(index), reserved.get(index), "cancelled after sibling failure"));
            processed.add(index);
            continue;
          }
          try {
            results.add(future.get());
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(failure(requests.get(index), reserved.get(index), "batch interrupted"));
          } catch (CancellationException cancelled) {
            failures.add(
                failure(
                    requests.get(index), reserved.get(index), "cancelled after sibling failure"));
          } catch (ExecutionException executionFailure) {
            failures.add(
                failure(requests.get(index), reserved.get(index), rootMessage(executionFailure)));
          }
          processed.add(index);
        }
      }
    }
    return new BatchResult(results, failures);
  }

  private void validateBatch(List<SpawnRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      throw new IllegalArgumentException("at least one subagent request is required");
    }
    if (requests.size() > SubagentProtocolService.MAX_CHILDREN_PER_PARENT) {
      throw new IllegalArgumentException(
          "batch child limit is " + SubagentProtocolService.MAX_CHILDREN_PER_PARENT);
    }
    String parent = requests.getFirst() == null ? null : requests.getFirst().parentSessionId();
    if (parent == null || parent.isBlank()) {
      throw new IllegalArgumentException("batch parent session id is required");
    }
    Set<String> requestIds = new LinkedHashSet<>();
    for (SpawnRequest request : requests) {
      if (request == null || !parent.equals(request.parentSessionId())) {
        throw new IllegalArgumentException("all batch requests must share one parent session");
      }
      validateParentTurn(request);
      if (!requestIds.add(request.requestId())) {
        throw new IllegalArgumentException("batch request ids must be unique");
      }
    }
  }

  /** Validate every foreign-key-bearing parent turn before reserving any batch child. */
  private void validateParentTurn(SpawnRequest request) {
    String parentTurnId = request.parentTurnId();
    if (parentTurnId == null || parentTurnId.isBlank()) return;
    AgentTurn parentTurn =
        turnRepository
            .findById(parentTurnId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "parentTurnId does not belong to parent session: " + parentTurnId));
    if (!request.parentSessionId().equals(parentTurn.getSessionId())) {
      throw new IllegalArgumentException(
          "parentTurnId does not belong to parent session: " + parentTurnId);
    }
  }

  private void cancelReserved(
      List<Handle> reserved, Throwable cause, AppUserDetails user, String orgId) {
    if (reserved.isEmpty()) return;
    String reason = "batch stopped: " + rootMessage(cause);
    for (Handle handle : reserved) {
      try {
        // Cancel only children reserved by this batch. Cancelling the whole parent scope can
        // terminate unrelated concurrent children that predate this batch.
        protocolService.cancel(handle.childSessionId(), reason, user.getUser().getId(), orgId);
      } catch (RuntimeException ignored) {
        // The original reservation/model failure is the useful response; protocol cancellation is
        // best-effort because another worker may have already reached a terminal state.
      }
    }
  }

  private static BatchFailure failure(SpawnRequest request, Handle handle, String reason) {
    return new BatchFailure(request.requestId(), handle.childSessionId(), reason);
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) current = current.getCause();
    String message = current.getMessage();
    return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
  }

  /**
   * Deterministic coordinator-triggered execution. This is intentionally not exposed as a model
   * tool: an authenticated coordinator must explicitly opt in, the platform flag must be enabled,
   * and a fresh eligibility check must still pass before a child is reserved and executed.
   */
  public Result executeCoordinator(
      SubagentProtocol.CoordinatorExecutionRequest coordinator, AppUserDetails user, String orgId) {
    if (coordinator == null || coordinator.spawn() == null) {
      throw new IllegalArgumentException("coordinator execution request is required");
    }
    if (!coordinator.automatic()) {
      throw new IllegalArgumentException("automatic coordinator opt-in is required");
    }
    if (!"COORDINATOR".equals(coordinator.trigger())) {
      throw new IllegalArgumentException("trigger must be COORDINATOR");
    }
    if (!featureFlagService.isEnabled(COORDINATOR_AUTO_FLAG)) {
      throw new IllegalStateException("coordinator-triggered subagents are disabled");
    }
    SpawnRequest request = coordinator.spawn();
    DelegationDecision decision = evaluateDelegation(request, user, orgId);
    if (!decision.eligible()) {
      throw new IllegalStateException(
          "coordinator delegation is not eligible: " + decision.reason());
    }
    if (coordinator.eligibilityChildSessionId() != null
        && !coordinator.eligibilityChildSessionId().isBlank()
        && decision.childSessionId() != null
        && !coordinator.eligibilityChildSessionId().equals(decision.childSessionId())) {
      throw new IllegalStateException("eligibility child session does not match request");
    }
    // Reserve first and append the acceptance in the same transaction.  The eligibility probe is
    // advisory; another coordinator may win the last slot before this point.  In that case spawn
    // fails closed and no misleading "accepted" event is emitted.  The persisted event names the
    // actual child rather than the probe's (normally null) placeholder.
    Handle handle =
        protocolService.spawnAndRecordCoordinator(request, user.getUser().getId(), orgId);
    // The coordinator reservation already created this exact child. Calling execute() here would
    // spawn a second child for the same request (and can fail with a 500 when the parent has no
    // remaining capacity). Start and execute the reserved handle directly so the acceptance audit
    // and the model execution refer to one persisted child.
    Handle started = protocolService.start(handle.childSessionId(), user.getUser().getId(), orgId);
    return executeReserved(request, started, user, orgId);
  }

  /** Explicit parent cancellation propagation for an in-flight execution owner. */
  public int cancelChildren(
      String parentSessionId, String reason, AppUserDetails user, String orgId) {
    if (user == null || user.getUser() == null) {
      throw new SecurityException("authenticated user is required");
    }
    return protocolService.cancelChildren(parentSessionId, reason, user.getUser().getId(), orgId);
  }

  private void persistExecutionTurns(
      AgentSession child,
      Role role,
      String objective,
      String summary,
      RunCostTracker.Snapshot usage,
      int modelRounds) {
    int rounds = Math.max(1, modelRounds);
    int firstSequence = Math.toIntExact(turnRepository.countBySessionId(child.getId()) + 1);
    for (int round = 0; round < rounds; round++) {
      boolean last = round == rounds - 1;
      turnRepository.save(
          AgentTurn.builder()
              .id(UUID.randomUUID().toString())
              .sessionId(child.getId())
              .orgId(child.getOrgId())
              .userId(child.getUserId())
              .sequence(firstSequence + round)
              .userMessage(round == 0 ? objective : null)
              .assistantSummary(last ? summary : "Read-only subagent tool round " + (round + 1))
              .intentType("SUBAGENT_" + role.name())
              .intentConfidence(1.0)
              .actionType("READ_ONLY_" + role.name())
              .inputTokens(portion(usage.inputTokens(), rounds, round))
              .outputTokens(portion(usage.outputTokens(), rounds, round))
              .reasoningTokens(portion(usage.reasoningTokens(), rounds, round))
              .costUsd(portion(usage.costUsd(), rounds, round))
              .status("OK")
              .createdAt(Instant.now())
              .build());
    }
  }

  private static long portion(long total, int parts, int index) {
    long base = total / parts;
    return index == parts - 1 ? total - base * (parts - 1L) : base;
  }

  private static double portion(double total, int parts, int index) {
    double base = total / parts;
    return index == parts - 1 ? total - base * (parts - 1) : base;
  }
}
