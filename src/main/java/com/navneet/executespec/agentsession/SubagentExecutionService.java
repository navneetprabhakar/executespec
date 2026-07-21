package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.navneet.executespec.specchat.SpecChatService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Executes one bounded, read-only persisted role-contract child-agent assignment. */
@Service
@RequiredArgsConstructor
public class SubagentExecutionService {

  private static final int MAX_EXECUTION_ROUNDS = 4;

  /**
   * Lease-renewal period for an executing child. Must stay comfortably below {@code
   * AgentSessionStartupRecovery.STALE_AFTER_SECONDS} so a couple of missed ticks still cannot make
   * a live child look abandoned.
   */
  private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;

  private static final Pattern CITATION =
      Pattern.compile("(?m)([A-Za-z0-9_./-]+\\.[A-Za-z0-9_-]+):(\\d+)");

  private final SubagentProtocolService protocolService;
  private final AgentSessionRepository sessionRepository;
  private final AgentTurnRepository turnRepository;
  private final AgentOrchestratorTurnService orchestratorTurnService;
  private final SpecChatService specChatService;
  private final RunCostTracker costTracker;
  private final FeatureFlagService featureFlagService;

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
    Thread heartbeat = startHeartbeat(child.getId(), userId, orgId);
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
      CapturingSink sink = new CapturingSink();
      List<Map<String, Object>> conversation =
          List.of(Map.of("role", "user", "content", rolePrompt(handle.role(), handle.objective())));

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
          },
          handle.parentTurnId());

      RunCostTracker.Snapshot usage = sink.usage();
      if (usage.costUsd() > handle.maxCostUsd()) {
        throw new SubagentBudgetExceededException(
            "SUBAGENT_COST_BUDGET_EXHAUSTED", "subagent cost budget exceeded");
      }
      String summary = sink.summary();
      List<Evidence> evidence = extractEvidence(summary, sink.toolEvidence());
      if (handle.role().contract().requiresEvidence() && evidence.isEmpty()) {
        throw new IllegalStateException(
            handle.role().name().toLowerCase(java.util.Locale.ROOT)
                + " returned no file:line evidence");
      }
      persistExecutionTurns(
          child, handle.role(), handle.objective(), summary, usage, sink.turnCount());
      return protocolService.complete(
          child.getId(), new SubagentProtocol.Completion(summary, evidence), userId, orgId);
    } catch (RuntimeException e) {
      try {
        protocolService.cancel(
            child.getId(), "execution stopped: " + safeMessage(e), userId, orgId);
      } catch (RuntimeException ignored) {
        // Preserve the original execution failure; terminal/racing cancellation is harmless.
      }
      throw e;
    } finally {
      heartbeat.interrupt();
    }
  }

  /**
   * Starts a bounded lease-renewal ticker for one executing child.
   *
   * <p>The ticker is advisory: a failed renewal is logged by the protocol service and never fails
   * the turn, and the returned thread is interrupted on every terminal path.
   */
  private Thread startHeartbeat(String childSessionId, String userId, String orgId) {
    return Thread.ofVirtual()
        .name("subagent-heartbeat-" + childSessionId)
        .start(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                  protocolService.heartbeat(childSessionId, userId, orgId);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } catch (RuntimeException renewalFailure) {
                  // Keep ticking: one failed renewal must not silently end the lease.
                }
              }
            });
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
    try (ExecutorService executor = Executors.newFixedThreadPool(reserved.size())) {
      for (int index = 0; index < requests.size(); index++) {
        SpawnRequest request = requests.get(index);
        Handle handle = reserved.get(index);
        futures.add(executor.submit(() -> executeReserved(request, handle, user, orgId)));
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

  private static String rolePrompt(Role role, String objective) {
    String contract =
        switch (role) {
          case SCOUT ->
              "Locate the smallest relevant code surface. Report findings only; do not propose or"
                  + " perform edits. Cite every concrete finding as path:line.";
          case VERIFIER ->
              "Independently verify the stated objective against repository evidence. Do not edit,"
                  + " run lifecycle actions, or claim success without path:line citations.";
          case REPRODUCER ->
              "Trace the smallest deterministic reproduction path using repository evidence only."
                  + " Do not execute commands or edit files; report prerequisites, expected failure"
                  + " signatures, and path:line evidence.";
          case REVIEWER ->
              "Review the relevant implementation for correctness, regressions, and missing tests."
                  + " Do not edit or run lifecycle actions; report findings with path:line"
                  + " evidence.";
          case DOCS_SCOUT ->
              "Find the authoritative documentation and contract surfaces relevant to the"
                  + " objective. Do not edit; report stale or missing documentation with path:line"
                  + " evidence.";
          case IMPLEMENTER ->
              "Implementer execution is only available through the explicit single-writer"
                  + " mutation seam; do not use this read-only child executor.";
        };
    return "ROLE: " + role + " (strictly read-only)\n" + contract + "\n\nOBJECTIVE:\n" + objective;
  }

  private static List<Evidence> extractEvidence(String summary, List<String> toolEvidence) {
    Set<String> citations = new LinkedHashSet<>();
    collectCitations(summary, citations);
    for (String value : toolEvidence) collectCitations(value, citations);
    return citations.stream()
        .limit(20)
        .map(citation -> new Evidence(citation, "read-only subagent evidence"))
        .toList();
  }

  private static void collectCitations(String value, Set<String> citations) {
    if (value == null) return;
    Matcher matcher = CITATION.matcher(value);
    while (matcher.find()) citations.add(matcher.group(1) + ":" + matcher.group(2));
  }

  private static String safeMessage(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static final class CapturingSink implements SpecChatService.Sink {
    private final StringBuilder text = new StringBuilder();
    private final List<String> toolEvidence = new ArrayList<>();
    private final Map<String, String> toolNames = new LinkedHashMap<>();
    private RunCostTracker.Snapshot usage = new RunCostTracker.Snapshot(0, 0, 0, 0, 0, 0);
    private int turnCount;

    @Override
    public void token(String text) {
      if (text == null || text.isBlank()) return;
      if (!this.text.isEmpty()) this.text.append('\n');
      this.text.append(text);
    }

    @Override
    public void toolCall(String tool, String argsJson, String id) {
      toolNames.put(id, tool);
    }

    @Override
    public void toolResult(String id, String resultJson) {
      toolEvidence.add(toolNames.getOrDefault(id, "tool") + ": " + resultJson);
    }

    @Override
    public void done(int turnCount, RunCostTracker.Snapshot cost) {
      this.turnCount = turnCount;
      usage = cost == null ? usage : cost;
    }

    String summary() {
      return text.isEmpty()
          ? "Read-only subagent completed without a textual summary."
          : text.toString();
    }

    List<String> toolEvidence() {
      return List.copyOf(toolEvidence);
    }

    RunCostTracker.Snapshot usage() {
      return usage;
    }

    int turnCount() {
      return turnCount;
    }
  }
}
