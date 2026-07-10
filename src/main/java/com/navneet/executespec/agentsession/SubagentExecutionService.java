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
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.DelegationDecision;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Evidence;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Result;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Role;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.SpawnRequest;
import com.navneet.executespec.agentsession.subagent.SubagentProtocolService;
import com.navneet.executespec.auth.security.AppUserDetails;
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
          });

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
