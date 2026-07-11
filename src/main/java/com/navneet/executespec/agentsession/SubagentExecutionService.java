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

/** Executes one bounded, read-only persisted SCOUT or VERIFIER child-agent assignment. */
@Service
@RequiredArgsConstructor
public class SubagentExecutionService {

  private static final int MAX_EXECUTION_ROUNDS = 4;
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
    AgentSession child =
        sessionRepository
            .findById(handle.childSessionId())
            .orElseThrow(() -> new IllegalStateException("Persisted child session disappeared"));

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
      if (remainingTurns < 1) throw new IllegalStateException("Subagent turn budget exhausted");
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
            if (!budget.canContinue()) throw new CancellationException(budget.reason());
            if (roundsStarted.incrementAndGet() > maxRounds) {
              throw new CancellationException("subagent round budget exhausted");
            }
            if (costTracker.snapshot(child.getId()).costUsd() >= handle.maxCostUsd()) {
              throw new CancellationException("subagent cost budget exhausted");
            }
          },
          handle.parentTurnId());

      RunCostTracker.Snapshot usage = sink.usage();
      if (usage.costUsd() > handle.maxCostUsd()) {
        throw new CancellationException("subagent cost budget exceeded");
      }
      String summary = sink.summary();
      List<Evidence> evidence = extractEvidence(summary, sink.toolEvidence());
      if (handle.role() == Role.VERIFIER && evidence.isEmpty()) {
        throw new IllegalStateException("Verifier returned no file:line evidence");
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
    try {
      for (SpawnRequest request : requests) {
        Handle handle = protocolService.spawn(request, user.getUser().getId(), orgId);
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
      for (SpawnRequest request : requests) {
        futures.add(executor.submit(() -> execute(request, user, orgId)));
      }
      boolean failed = false;
      for (int index = 0; index < futures.size(); index++) {
        Future<Result> future = futures.get(index);
        try {
          results.add(future.get());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          failed = true;
          failures.add(failure(requests.get(index), reserved.get(index), "batch interrupted"));
          cancelReserved(reserved, interrupted, user, orgId);
          break;
        } catch (ExecutionException executionFailure) {
          failed = true;
          failures.add(
              failure(requests.get(index), reserved.get(index), rootMessage(executionFailure)));
          cancelReserved(reserved, executionFailure, user, orgId);
          break;
        }
      }
      if (failed) {
        for (int index = 0; index < futures.size(); index++) {
          Future<Result> future = futures.get(index);
          if (!future.isDone()) future.cancel(true);
        }
        for (int index = results.size() + failures.size(); index < futures.size(); index++) {
          if (!futures.get(index).isDone()) {
            failures.add(
                failure(
                    requests.get(index), reserved.get(index), "cancelled after sibling failure"));
          }
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
      if (!requestIds.add(request.requestId())) {
        throw new IllegalArgumentException("batch request ids must be unique");
      }
    }
  }

  private void cancelReserved(
      List<Handle> reserved, Throwable cause, AppUserDetails user, String orgId) {
    if (reserved.isEmpty()) return;
    try {
      protocolService.cancelChildren(
          reserved.getFirst().parentSessionId(),
          "batch stopped: " + rootMessage(cause),
          user.getUser().getId(),
          orgId);
    } catch (RuntimeException ignored) {
      // The original reservation/model failure is the useful response; protocol cancellation is
      // best-effort because another worker may have already reached a terminal state.
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
    protocolService.spawnAndRecordCoordinator(request, user.getUser().getId(), orgId);
    return execute(request, user, orgId);
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
        role == Role.SCOUT
            ? "Locate the smallest relevant code surface. Report findings only; do not propose or"
                + " perform edits. Cite every concrete finding as path:line."
            : "Independently verify the stated objective against repository evidence. Do not edit,"
                + " run lifecycle actions, or claim success without path:line citations.";
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
