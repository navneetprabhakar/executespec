package com.navneet.executespec.agentsession.subagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.specchat.SpecChatService;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolRegistry;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Bounded model-driven IMPLEMENTER loop behind the guarded writer reservation seam. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImplementerExecutionService {

  public static final String FLAG = ImplementerMutationService.FLAG;
  private static final int MAX_ROUNDS = 5;
  private static final int MAX_OBJECTIVE_CHARS = 8_000;
  private static final Set<String> TOOLS =
      Set.of(
          "readFile",
          "listFiles",
          "searchFiles",
          "gitDiff",
          "writeFile",
          "editFile",
          "buildProject",
          "runWorkspaceTests");

  private final FeatureFlagService featureFlagService;
  private final AgentSessionRepository sessionRepository;
  private final AgentTurnRepository turnRepository;
  private final AgentActionEventRepository eventRepository;
  private final ImplementerMutationService mutationService;
  private final ImplementerWorkspaceScope workspaceScope;
  private final SubagentProtocolService protocolService;
  private final ToolRegistry toolRegistry;
  private final SpecChatService specChatService;
  private final ObjectMapper objectMapper;

  public ExecutionResult execute(
      String parentSessionId,
      String reservationId,
      String scope,
      String objective,
      String provider,
      String model,
      int maxRounds,
      String parentTurnId,
      AppUserDetails user,
      String orgId) {
    if (!featureFlagService.isEnabled(FLAG)) {
      throw new IllegalStateException("IMPLEMENTER is disabled");
    }
    if (objective == null || objective.isBlank()) {
      throw new IllegalArgumentException("implementer objective is required");
    }
    if (objective.length() > MAX_OBJECTIVE_CHARS) {
      throw new IllegalArgumentException("implementer objective is too long");
    }
    if (user == null || user.getUser() == null) {
      throw new SecurityException("authenticated user is required");
    }
    AgentSession session =
        sessionRepository
            .findById(parentSessionId)
            .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
    if (!user.getUser().getId().equals(session.getUserId())
        || (orgId != null && !orgId.equals(session.getOrgId()))) {
      throw new SecurityException("agent session ownership mismatch");
    }
    if (!isLocalSurface(session)) {
      throw new SecurityException("IMPLEMENTER requires a CLI or VS Code session");
    }
    // Server-authoritative scope. The loop below binds SERVER read/write/build/test tools, so the
    // directory must come from server state; a caller-named absolute host path is refused.
    Path root = workspaceScope.resolve(session, scope);

    // Validate before any model call. Every mutation callback repeats this check.
    protocolService.authorizeWriter(
        parentSessionId, reservationId, root.toString(), session.getUserId(), orgId);
    int rounds = Math.max(1, Math.min(MAX_ROUNDS, maxRounds <= 0 ? MAX_ROUNDS : maxRounds));
    String turnId = UUID.randomUUID().toString();
    checkpoint(parentSessionId, turnId, reservationId, root, "PREPARED", objective);
    CapturingSink sink = new CapturingSink();
    ExecutionContext context = new ExecutionContext(parentSessionId, "IMPLEMENTER", root);
    List<ToolCallback> callbacks =
        toolRegistry.bindForImplementer(
            context,
            TOOLS,
            (tool, args) ->
                mutationService.mutateResolved(
                    parentSessionId, reservationId, root, tool, args, user, orgId));
    String systemPrompt = systemPrompt(root, rounds);
    try {
      specChatService.runTurnBoundedWithTools(
          sink,
          user,
          parentSessionId,
          orgId,
          List.of(Map.of("role", "user", "content", objective)),
          provider == null || provider.isBlank() ? session.getDefaultProvider() : provider,
          model == null || model.isBlank() ? session.getDefaultModel() : model,
          AgentTier.WORKHORSE,
          "ImplementerExecutionService",
          systemPrompt,
          context,
          callbacks,
          rounds,
          () -> beforeRound(parentSessionId, reservationId, root, session, user, orgId),
          parentTurnId);
      if (!sink.mutationSeen()) {
        throw new IllegalStateException("IMPLEMENTER stopped without a file mutation");
      }
      if (!sink.verificationSucceeded()) {
        throw new IllegalStateException(
            "IMPLEMENTER stopped without successful buildProject or runWorkspaceTests"
                + " verification");
      }
      persistTurn(session, turnId, objective, sink, "COMPLETED");
      checkpoint(parentSessionId, turnId, reservationId, root, "COMPLETED", sink.summary());
      return new ExecutionResult(
          "COMPLETED", sink.summary(), sink.turnCount, sink.usage.costUsd(), sink.toolCalls);
    } catch (RuntimeException failure) {
      String reason =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      persistTurn(session, turnId, objective, sink, "FAILED: " + reason);
      checkpoint(parentSessionId, turnId, reservationId, root, "FAILED", reason);
      throw failure;
    } finally {
      try {
        protocolService.releaseWriter(
            parentSessionId,
            reservationId,
            "implementer execution terminal",
            session.getUserId(),
            orgId);
      } catch (RuntimeException releaseFailure) {
        log.warn(
            "Unable to release implementer reservation {}: {}",
            reservationId,
            releaseFailure.getMessage());
      }
    }
  }

  private void beforeRound(
      String sessionId,
      String reservationId,
      Path root,
      AgentSession session,
      AppUserDetails user,
      String orgId) {
    AgentSession current =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new CancellationException("session disappeared"));
    if (!"ACTIVE".equalsIgnoreCase(current.getStatus())) {
      throw new CancellationException("implementer session is " + current.getStatus());
    }
    protocolService.authorizeWriter(
        sessionId, reservationId, root.toString(), user.getUser().getId(), orgId);
  }

  private void persistTurn(
      AgentSession session, String turnId, String objective, CapturingSink sink, String status) {
    int sequence = Math.toIntExact(turnRepository.countBySessionId(session.getId()) + 1);
    turnRepository.save(
        AgentTurn.builder()
            .id(turnId)
            .sessionId(session.getId())
            .orgId(session.getOrgId())
            .userId(session.getUserId())
            .sequence(sequence)
            .userMessage(objective)
            .assistantSummary(sink.summary())
            .intentType("IMPLEMENTER_V1")
            .intentConfidence(1.0)
            .actionType("WRITE_SINGLE_WRITER")
            .inputTokens(sink.usage.inputTokens())
            .outputTokens(sink.usage.outputTokens())
            .reasoningTokens(sink.usage.reasoningTokens())
            .costUsd(sink.usage.costUsd())
            .status(status)
            .createdAt(Instant.now())
            .build());
  }

  private void checkpoint(
      String sessionId,
      String turnId,
      String reservationId,
      Path root,
      String status,
      String detail) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reservationId", reservationId);
    payload.put("scope", root.toString());
    payload.put("status", status);
    payload.put(
        "detail", detail == null ? "" : detail.substring(0, Math.min(detail.length(), 2_000)));
    payload.put("workspaceFingerprint", fingerprint(root));
    eventRepository.save(
        AgentActionEvent.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(sessionId)
            .turnId(turnId)
            .type("IMPLEMENTER_CHECKPOINT")
            .payloadJson(writeJson(payload))
            .createdAt(Instant.now())
            .build());
  }

  private String fingerprint(Path root) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      int count = 0;
      try (var stream = Files.walk(root)) {
        for (Path path : stream.filter(Files::isRegularFile).sorted().limit(2_000).toList()) {
          String rel = root.relativize(path).toString();
          digest.update(rel.getBytes(StandardCharsets.UTF_8));
          digest.update(Long.toString(Files.size(path)).getBytes(StandardCharsets.UTF_8));
          count++;
        }
      }
      digest.update(Integer.toString(count).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      return "unavailable:" + e.getClass().getSimpleName();
    }
  }

  private String writeJson(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      return "{\"error\":\"checkpoint serialization failed\"}";
    }
  }

  private static boolean isLocalSurface(AgentSession session) {
    return "CLI".equalsIgnoreCase(session.getClientSurface())
        || "VSCODE".equalsIgnoreCase(session.getClientSurface());
  }

  private static String systemPrompt(Path root, int rounds) {
    return """
You are ExecuteSpec IMPLEMENTER_V1. Work only inside the authorized workspace below.
Workspace: %s
You have at most %d model rounds. First inspect relevant files with readFile/searchFiles/listFiles.
Make the smallest correct edits using writeFile/editFile. Never use shell, git commit, network,
credentials, or files outside the workspace. After edits, run buildProject or runWorkspaceTests
when available, repair failures, then stop only after a successful verification or explain the
exact blocker. Do not claim completion without an actual mutation and verification evidence.
The server enforces one writer and rechecks the reservation before every mutation.
"""
        .formatted(root, rounds);
  }

  public record ExecutionResult(
      String status, String summary, int turnsUsed, double costUsd, List<String> toolCalls) {
    public ExecutionResult {
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
  }

  private static final class CapturingSink implements SpecChatService.Sink {
    private final StringBuilder text = new StringBuilder();
    private final List<String> toolCalls = new ArrayList<>();
    private final Map<String, String> toolNames = new LinkedHashMap<>();
    private boolean mutationSeen;
    private boolean verificationSucceeded;
    private RunCostTracker.Snapshot usage = new RunCostTracker.Snapshot(0, 0, 0, 0, 0, 0);
    private int turnCount;

    @Override
    public void token(String delta) {
      if (delta != null && !delta.isBlank()) {
        if (!text.isEmpty()) text.append('\n');
        text.append(delta);
      }
    }

    @Override
    public void toolCall(String tool, String argsJson, String id) {
      toolCalls.add(tool);
      toolNames.put(id, tool);
      if ("writeFile".equals(tool) || "editFile".equals(tool)) mutationSeen = true;
    }

    @Override
    public void toolResult(String id, String resultJson) {
      String tool = toolNames.get(id);
      if (!"buildProject".equals(tool) && !"runWorkspaceTests".equals(tool)) return;
      try {
        com.fasterxml.jackson.databind.JsonNode result =
            new ObjectMapper().readTree(resultJson == null ? "{}" : resultJson);
        if (result.path("ok").asBoolean(false)) verificationSucceeded = true;
      } catch (Exception ignored) {
        // A malformed result is not verification evidence.
      }
    }

    @Override
    public void done(int turnCount, RunCostTracker.Snapshot cost) {
      this.turnCount = turnCount;
      if (cost != null) usage = cost;
    }

    private String summary() {
      return text.isEmpty() ? "IMPLEMENTER completed without a textual summary." : text.toString();
    }

    private boolean mutationSeen() {
      return mutationSeen;
    }

    private boolean verificationSucceeded() {
      return verificationSucceeded;
    }
  }
}
