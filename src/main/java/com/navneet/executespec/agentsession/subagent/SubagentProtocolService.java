package com.navneet.executespec.agentsession.subagent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.BudgetState;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Completion;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Handle;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Result;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Role;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.SpawnRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Durable control-plane foundation for child agents.
 *
 * <p>No model is dispatched here. The service persists a bounded child session and an append-only
 * parent/child protocol in {@code agent_action_event}; a later executor must call {@link
 * #authorizeNextTurn} before every child turn and complete through {@link #complete}. This keeps
 * parent linkage, depth, role and budgets durable without overloading {@code pipeline_run} or
 * requiring a schema migration.
 */
@Service
@RequiredArgsConstructor
public class SubagentProtocolService {

  public static final int MAX_DEPTH = 2;
  public static final int MAX_CHILDREN_PER_PARENT = 4;
  public static final int MAX_TURNS_PER_CHILD = 12;
  public static final double MAX_COST_USD_PER_CHILD = 2.0;
  private static final int MAX_OBJECTIVE_CHARS = 4_000;

  static final String EVENT_CHILD_CREATED = "SUBAGENT_CREATED";
  static final String EVENT_CHILD_SPAWNED = "SUBAGENT_SPAWNED";
  static final String EVENT_CHILD_STARTED = "SUBAGENT_STARTED";
  static final String EVENT_CHILD_COMPLETED = "SUBAGENT_COMPLETED";
  static final String EVENT_CHILD_CANCELLED = "SUBAGENT_CANCELLED";

  private final AgentSessionRepository sessionRepository;
  private final AgentActionEventRepository eventRepository;
  private final AgentTurnRepository turnRepository;
  private final ObjectMapper objectMapper;

  @Transactional
  public Handle spawn(SpawnRequest request, String actorUserId, String actorOrgId) {
    validateSpawnRequest(request);
    AgentSession parent = requireOwnedSession(request.parentSessionId(), actorUserId, actorOrgId);
    requireParentActive(parent);

    int depth = depthOf(parent.getId()) + 1;
    if (depth > MAX_DEPTH) {
      throw new IllegalStateException("Subagent depth limit exceeded: " + depth + "/" + MAX_DEPTH);
    }
    long existingChildren =
        eventRepository.countBySessionIdAndType(parent.getId(), EVENT_CHILD_SPAWNED);
    if (existingChildren >= MAX_CHILDREN_PER_PARENT) {
      throw new IllegalStateException(
          "Subagent child limit reached: " + existingChildren + "/" + MAX_CHILDREN_PER_PARENT);
    }

    String childId = childId(parent.getId(), request.requestId());
    AgentSession existing = sessionRepository.findById(childId).orElse(null);
    if (existing != null) {
      requireOwnership(existing, actorUserId, actorOrgId);
      return handleFromMetadata(existing, metadataOf(childId));
    }

    Instant now = Instant.now();
    ChildMetadata metadata =
        new ChildMetadata(
            parent.getId(),
            blankToNull(request.parentTurnId()),
            request.role(),
            request.objective().trim(),
            depth,
            request.maxTurns(),
            request.maxCostUsd());
    AgentSession child =
        AgentSession.builder()
            .id(childId)
            .orgId(parent.getOrgId())
            .userId(parent.getUserId())
            .projectId(parent.getProjectId())
            .runId(parent.getRunId())
            .repoRemote(parent.getRepoRemote())
            .repoRootHash(parent.getRepoRootHash())
            .clientSurface("SUBAGENT")
            .title(
                request.role().name().toLowerCase(Locale.ROOT) + ": " + title(request.objective()))
            .status("READY")
            .defaultProvider(parent.getDefaultProvider())
            .defaultModel(parent.getDefaultModel())
            .defaultExecutionMode(parent.getDefaultExecutionMode())
            .approvalPolicy(parent.getApprovalPolicy())
            .createdAt(now)
            .updatedAt(now)
            .build();
    sessionRepository.save(child);
    append(childId, request.parentTurnId(), request.requestId(), EVENT_CHILD_CREATED, metadata);
    append(
        parent.getId(),
        request.parentTurnId(),
        request.requestId(),
        EVENT_CHILD_SPAWNED,
        new ParentLink(childId, metadata));
    return handleFromMetadata(child, metadata);
  }

  /** Fail-closed budget/cancellation check that an executor calls before each child turn. */
  @Transactional(readOnly = true)
  public BudgetState authorizeNextTurn(
      String childSessionId, String actorUserId, String actorOrgId) {
    AgentSession child = requireOwnedSession(childSessionId, actorUserId, actorOrgId);
    ChildMetadata metadata = metadataOf(childSessionId);
    long turns = turnRepository.countBySessionId(childSessionId);
    double cost =
        turnRepository.findBySessionIdOrderBySequenceAsc(childSessionId).stream()
            .mapToDouble(t -> t.getCostUsd())
            .sum();
    String reason = null;
    if (!List.of("READY", "RUNNING").contains(child.getStatus())) {
      reason = "child status is " + child.getStatus();
    } else if (turns >= metadata.maxTurns()) {
      reason = "turn budget exhausted";
    } else if (cost >= metadata.maxCostUsd()) {
      reason = "cost budget exhausted";
    }
    return new BudgetState(
        childSessionId,
        turns,
        metadata.maxTurns(),
        cost,
        metadata.maxCostUsd(),
        reason == null,
        reason);
  }

  /** Claims a READY child for execution. Idempotent while the child is already RUNNING. */
  @Transactional
  public Handle start(String childSessionId, String actorUserId, String actorOrgId) {
    AgentSession child = requireOwnedSession(childSessionId, actorUserId, actorOrgId);
    ChildMetadata metadata = metadataOf(childSessionId);
    if ("RUNNING".equals(child.getStatus())) return handleFromMetadata(child, metadata);
    if (!"READY".equals(child.getStatus())) {
      throw new IllegalStateException("Subagent cannot start from status " + child.getStatus());
    }
    BudgetState budget = authorizeNextTurn(childSessionId, actorUserId, actorOrgId);
    if (!budget.canContinue()) throw new IllegalStateException(budget.reason());
    child.setStatus("RUNNING");
    child.setUpdatedAt(Instant.now());
    sessionRepository.save(child);
    append(childSessionId, metadata.parentTurnId(), null, EVENT_CHILD_STARTED, metadata);
    return handleFromMetadata(child, metadata);
  }

  @Transactional
  public Result complete(
      String childSessionId, Completion completion, String actorUserId, String actorOrgId) {
    AgentSession child = requireOwnedSession(childSessionId, actorUserId, actorOrgId);
    ChildMetadata metadata = metadataOf(childSessionId);
    requireActiveChild(child);
    if (completion == null || completion.summary() == null || completion.summary().isBlank()) {
      throw new IllegalArgumentException("completion summary is required");
    }
    if (metadata.role() == Role.VERIFIER && completion.evidence().isEmpty()) {
      throw new IllegalArgumentException("verifier completion requires evidence");
    }
    BudgetState budget = authorizeNextTurn(childSessionId, actorUserId, actorOrgId);
    if (budget.turnsUsed() > budget.maxTurns() || budget.costUsd() > budget.maxCostUsd()) {
      throw new IllegalStateException("Subagent completed after exceeding its budget");
    }

    Instant completedAt = Instant.now();
    child.setStatus("COMPLETED");
    child.setUpdatedAt(completedAt);
    sessionRepository.save(child);
    Result result =
        new Result(
            handleFromMetadata(child, metadata),
            completion.summary().trim(),
            completion.evidence(),
            budget.turnsUsed(),
            budget.costUsd(),
            completedAt);
    append(childSessionId, metadata.parentTurnId(), null, EVENT_CHILD_COMPLETED, result);
    append(
        metadata.parentSessionId(), metadata.parentTurnId(), null, EVENT_CHILD_COMPLETED, result);
    return result;
  }

  @Transactional
  public Handle cancel(
      String childSessionId, String reason, String actorUserId, String actorOrgId) {
    AgentSession child = requireOwnedSession(childSessionId, actorUserId, actorOrgId);
    ChildMetadata metadata = metadataOf(childSessionId);
    if ("COMPLETED".equals(child.getStatus())) {
      throw new IllegalStateException("Completed subagent cannot be cancelled");
    }
    if (!"CANCELLED".equals(child.getStatus())) {
      child.setStatus("CANCELLED");
      child.setUpdatedAt(Instant.now());
      sessionRepository.save(child);
      Cancellation cancellation =
          new Cancellation(childSessionId, blankToDefault(reason, "cancelled by parent"));
      append(childSessionId, metadata.parentTurnId(), null, EVENT_CHILD_CANCELLED, cancellation);
      append(
          metadata.parentSessionId(),
          metadata.parentTurnId(),
          null,
          EVENT_CHILD_CANCELLED,
          cancellation);
    }
    return handleFromMetadata(child, metadata);
  }

  /** Parent-cancellation propagation seam. Already-terminal children are left untouched. */
  @Transactional
  public int cancelChildren(
      String parentSessionId, String reason, String actorUserId, String actorOrgId) {
    requireOwnedSession(parentSessionId, actorUserId, actorOrgId);
    int cancelled = 0;
    for (AgentActionEvent event :
        eventRepository.findBySessionIdAndTypeOrderByCreatedAtAsc(
            parentSessionId, EVENT_CHILD_SPAWNED)) {
      ParentLink link = read(event.getPayloadJson(), ParentLink.class);
      AgentSession child = sessionRepository.findById(link.childSessionId()).orElse(null);
      if (child != null && List.of("READY", "RUNNING").contains(child.getStatus())) {
        cancel(child.getId(), reason, actorUserId, actorOrgId);
        cancelled++;
      }
    }
    return cancelled;
  }

  private void validateSpawnRequest(SpawnRequest request) {
    if (request == null) throw new IllegalArgumentException("spawn request is required");
    if (blankToNull(request.requestId()) == null) {
      throw new IllegalArgumentException("requestId is required for idempotency");
    }
    if (blankToNull(request.parentSessionId()) == null) {
      throw new IllegalArgumentException("parentSessionId is required");
    }
    if (request.role() == null) throw new IllegalArgumentException("role is required");
    if (blankToNull(request.objective()) == null) {
      throw new IllegalArgumentException("objective is required");
    }
    if (request.objective().length() > MAX_OBJECTIVE_CHARS) {
      throw new IllegalArgumentException("objective exceeds " + MAX_OBJECTIVE_CHARS + " chars");
    }
    if (request.maxTurns() < 1 || request.maxTurns() > MAX_TURNS_PER_CHILD) {
      throw new IllegalArgumentException("maxTurns must be between 1 and " + MAX_TURNS_PER_CHILD);
    }
    if (!Double.isFinite(request.maxCostUsd())
        || request.maxCostUsd() <= 0
        || request.maxCostUsd() > MAX_COST_USD_PER_CHILD) {
      throw new IllegalArgumentException("maxCostUsd must be > 0 and <= " + MAX_COST_USD_PER_CHILD);
    }
  }

  private int depthOf(String sessionId) {
    List<AgentActionEvent> events =
        eventRepository.findBySessionIdAndTypeOrderByCreatedAtAsc(sessionId, EVENT_CHILD_CREATED);
    return events.isEmpty()
        ? 0
        : read(events.getLast().getPayloadJson(), ChildMetadata.class).depth();
  }

  private ChildMetadata metadataOf(String childSessionId) {
    List<AgentActionEvent> events =
        eventRepository.findBySessionIdAndTypeOrderByCreatedAtAsc(
            childSessionId, EVENT_CHILD_CREATED);
    if (events.isEmpty()) {
      throw new IllegalStateException("Session is not a persisted subagent: " + childSessionId);
    }
    return read(events.getLast().getPayloadJson(), ChildMetadata.class);
  }

  private AgentSession requireOwnedSession(String id, String userId, String orgId) {
    AgentSession session =
        sessionRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent session not found: " + id));
    requireOwnership(session, userId, orgId);
    return session;
  }

  private static void requireOwnership(AgentSession session, String userId, String orgId) {
    if (!session.getUserId().equals(userId) || !session.getOrgId().equals(orgId)) {
      throw new SecurityException("Agent session ownership mismatch");
    }
  }

  private static void requireParentActive(AgentSession session) {
    if (!List.of("ACTIVE", "READY", "RUNNING").contains(session.getStatus())) {
      throw new IllegalStateException("Parent session is not active");
    }
  }

  private static void requireActiveChild(AgentSession child) {
    if (!List.of("READY", "RUNNING").contains(child.getStatus())) {
      throw new IllegalStateException("Subagent is already terminal: " + child.getStatus());
    }
  }

  private void append(
      String sessionId, String turnId, String actionId, String type, Object payload) {
    eventRepository.save(
        AgentActionEvent.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(sessionId)
            .turnId(blankToNull(turnId))
            .actionId(blankToNull(actionId))
            .type(type)
            .payloadJson(write(payload))
            .createdAt(Instant.now())
            .build());
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to persist subagent protocol payload", e);
    }
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid persisted subagent protocol payload", e);
    }
  }

  private static String childId(String parentSessionId, String requestId) {
    return UUID.nameUUIDFromBytes(
            ("subagent:" + parentSessionId + ":" + requestId).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static Handle handleFromMetadata(AgentSession child, ChildMetadata metadata) {
    return new Handle(
        child.getId(),
        metadata.parentSessionId(),
        metadata.parentTurnId(),
        metadata.role(),
        metadata.objective(),
        metadata.depth(),
        metadata.maxTurns(),
        metadata.maxCostUsd(),
        child.getStatus());
  }

  private static String title(String objective) {
    String text = objective.trim().replaceAll("\\s+", " ");
    return text.length() <= 180 ? text : text.substring(0, 177) + "...";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String blankToDefault(String value, String fallback) {
    String text = blankToNull(value);
    return text == null ? fallback : text;
  }

  record ChildMetadata(
      String parentSessionId,
      String parentTurnId,
      Role role,
      String objective,
      int depth,
      int maxTurns,
      double maxCostUsd) {}

  record ParentLink(String childSessionId, ChildMetadata child) {}

  record Cancellation(String childSessionId, String reason) {}
}
