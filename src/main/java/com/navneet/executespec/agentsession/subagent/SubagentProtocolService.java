package com.navneet.executespec.agentsession.subagent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.SubagentBudgetExceededException;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.BudgetState;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Completion;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.DelegationDecision;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Handle;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Result;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Role;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.RoleContract;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.SpawnRequest;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.WriterReservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

  /**
   * Hard ceiling for the complete descendant tree rooted at one parent session. A child budget is
   * not a license to fan out another full set of children: nested children consume the same
   * parent-owned budget and are counted recursively from the append-only spawn links.
   */
  public static final int MAX_TURNS_PER_TREE = MAX_CHILDREN_PER_PARENT * MAX_TURNS_PER_CHILD;

  public static final double MAX_COST_USD_PER_TREE =
      MAX_CHILDREN_PER_PARENT * MAX_COST_USD_PER_CHILD;
  private static final int MAX_OBJECTIVE_CHARS = 4_000;

  static final String EVENT_CHILD_CREATED = "SUBAGENT_CREATED";
  static final String EVENT_CHILD_SPAWNED = "SUBAGENT_SPAWNED";
  static final String EVENT_CHILD_STARTED = "SUBAGENT_STARTED";
  static final String EVENT_CHILD_COMPLETED = "SUBAGENT_COMPLETED";
  static final String EVENT_CHILD_CANCELLED = "SUBAGENT_CANCELLED";
  static final String EVENT_COORDINATOR_ACCEPTED = "SUBAGENT_COORDINATOR_ACCEPTED";
  static final String EVENT_WRITER_RESERVED = "SUBAGENT_WRITER_RESERVED";
  static final String EVENT_WRITER_RELEASED = "SUBAGENT_WRITER_RELEASED";
  private static final int MAX_WRITER_SCOPE_CHARS = 512;

  private final AgentSessionRepository sessionRepository;
  private final AgentActionEventRepository eventRepository;
  private final AgentTurnRepository turnRepository;
  private final ObjectMapper objectMapper;

  /**
   * Computes whether a parent may delegate one bounded read-only child.
   *
   * <p>This is the coordinator seam for parent-driven delegation. It is intentionally
   * side-effect-free and model-free; callers must still call {@link #spawn} to reserve the child,
   * which repeats all limits in one transaction. A parent can therefore inspect this decision
   * without turning an eligibility probe into a child or a billable model call.
   */
  @Transactional(readOnly = true)
  public DelegationDecision evaluateDelegation(
      SpawnRequest request, String actorUserId, String actorOrgId) {
    validateSpawnRequest(request);
    AgentSession parent = requireOwnedSession(request.parentSessionId(), actorUserId, actorOrgId);
    validateParentTurn(parent, request.parentTurnId());
    String childId = childId(parent.getId(), request.requestId());
    AgentSession existing = sessionRepository.findById(childId).orElse(null);
    if (existing != null) {
      requireOwnership(existing, actorUserId, actorOrgId);
      return new DelegationDecision(
          parent.getId(),
          request.role(),
          true,
          "already_accepted",
          depthOf(parent.getId()) + 1,
          countChildren(parent.getId()),
          remainingChildren(countChildren(parent.getId())),
          treeBudget(parent.getId()).turns(),
          treeBudget(parent.getId()).costUsd(),
          childId);
    }
    int depth = depthOf(parent.getId()) + 1;
    long existingChildren = countChildren(parent.getId());
    TreeBudget treeBudget = treeBudget(parent.getId());
    String reason = null;
    if (!List.of("ACTIVE", "READY", "RUNNING").contains(parent.getStatus())) {
      reason = "parent_inactive";
    } else if (depth > MAX_DEPTH) {
      reason = "depth_limit";
    } else if (existingChildren >= MAX_CHILDREN_PER_PARENT) {
      reason = "child_capacity_exhausted";
    } else if (treeBudget.turns() + request.maxTurns() > MAX_TURNS_PER_TREE) {
      reason = "tree_turn_budget_exhausted";
    } else if (treeBudget.costUsd() + request.maxCostUsd() > MAX_COST_USD_PER_TREE) {
      reason = "tree_cost_budget_exhausted";
    }
    return new DelegationDecision(
        parent.getId(),
        request.role(),
        reason == null,
        reason == null ? "eligible" : reason,
        depth,
        existingChildren,
        remainingChildren(existingChildren),
        treeBudget.turns(),
        treeBudget.costUsd(),
        null);
  }

  /**
   * Records an accepted deterministic coordinator trigger on the parent activity stream.
   *
   * <p>The trigger is deliberately separate from {@code spawn}: callers can audit why an automatic
   * child was requested, while {@link #spawn} remains the transactional reservation boundary. This
   * method performs the same ownership check and never calls a provider.
   */
  @Transactional
  public void recordCoordinatorTrigger(
      SpawnRequest request, String childSessionId, String actorUserId, String actorOrgId) {
    validateSpawnRequest(request);
    AgentSession parent = requireOwnedSession(request.parentSessionId(), actorUserId, actorOrgId);
    requireParentActive(parent);
    validateParentTurn(parent, request.parentTurnId());
    append(
        parent.getId(),
        request.parentTurnId(),
        request.requestId(),
        EVENT_COORDINATOR_ACCEPTED,
        new CoordinatorTrigger(
            request.requestId(),
            childSessionId,
            request.role(),
            request.objective().trim(),
            "COORDINATOR"));
  }

  /**
   * Atomically reserves a child and records the coordinator acceptance with the actual child id.
   *
   * <p>The eligibility endpoint is intentionally advisory, so a second coordinator can consume the
   * last slot between the probe and execution. Keeping reservation and acceptance in one
   * transaction makes the accepted event an honest audit record: it is written only after the same
   * transactional spawn checks succeed, and it names the child that will be executed. The request
   * id still makes retries idempotent through {@link #spawn}.
   */
  @Transactional
  public Handle spawnAndRecordCoordinator(
      SpawnRequest request, String actorUserId, String actorOrgId) {
    Handle handle = spawn(request, actorUserId, actorOrgId);
    AgentSession parent = requireOwnedSession(request.parentSessionId(), actorUserId, actorOrgId);
    append(
        parent.getId(),
        request.parentTurnId(),
        request.requestId(),
        EVENT_COORDINATOR_ACCEPTED,
        new CoordinatorTrigger(
            request.requestId(),
            handle.childSessionId(),
            request.role(),
            request.objective().trim(),
            "COORDINATOR"));
    return handle;
  }

  @Transactional
  public Handle spawn(SpawnRequest request, String actorUserId, String actorOrgId) {
    validateSpawnRequest(request);
    AgentSession parent = requireOwnedSession(request.parentSessionId(), actorUserId, actorOrgId);
    requireParentActive(parent);
    validateParentTurn(parent, request.parentTurnId());

    // Resolve idempotency before enforcing capacity. A retry of an already-accepted request must
    // return its original child even when that child filled the parent's final available slot.
    String childId = childId(parent.getId(), request.requestId());
    AgentSession existing = sessionRepository.findById(childId).orElse(null);
    if (existing != null) {
      requireOwnership(existing, actorUserId, actorOrgId);
      return handleFromMetadata(existing, metadataOf(childId));
    }

    int depth = depthOf(parent.getId()) + 1;
    if (depth > MAX_DEPTH) {
      throw new IllegalStateException("Subagent depth limit exceeded: " + depth + "/" + MAX_DEPTH);
    }
    long existingChildren = countChildren(parent.getId());
    if (existingChildren >= MAX_CHILDREN_PER_PARENT) {
      throw new IllegalStateException(
          "Subagent child limit reached: " + existingChildren + "/" + MAX_CHILDREN_PER_PARENT);
    }
    TreeBudget treeBudget = treeBudget(parent.getId());
    if (treeBudget.turns() + request.maxTurns() > MAX_TURNS_PER_TREE) {
      throw new SubagentBudgetExceededException(
          "SUBAGENT_TREE_TURN_BUDGET_EXHAUSTED",
          "Subagent total-tree turn budget exceeded: "
              + (treeBudget.turns() + request.maxTurns())
              + "/"
              + MAX_TURNS_PER_TREE);
    }
    if (treeBudget.costUsd() + request.maxCostUsd() > MAX_COST_USD_PER_TREE) {
      throw new SubagentBudgetExceededException(
          "SUBAGENT_TREE_COST_BUDGET_EXHAUSTED",
          "Subagent total-tree cost budget exceeded: "
              + formatBudget(treeBudget.costUsd() + request.maxCostUsd())
              + "/"
              + formatBudget(MAX_COST_USD_PER_TREE));
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
            request.maxCostUsd(),
            request.role().contract());
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
    // requestId is the durable action correlation id.  Keeping it on every protocol event lets
    // the parent session render one coherent child activity card across reconnects, rather than
    // forcing clients to infer the relationship from payload JSON.
    append(childId, request.parentTurnId(), request.requestId(), EVENT_CHILD_CREATED, metadata);
    append(
        parent.getId(),
        request.parentTurnId(),
        request.requestId(),
        EVENT_CHILD_SPAWNED,
        new ParentLink(childId, metadata));
    return handleFromMetadata(child, metadata);
  }

  /**
   * Reserves the single-writer boundary for a future IMPLEMENTER execution.
   *
   * <p>This is intentionally only an authority reservation: it does not add an IMPLEMENTER role,
   * dispatch a model, or grant shell/file tools. The pessimistic parent-session lock serializes
   * competing reservations in one JVM/database, while the append-only events make the lease
   * recoverable after a restart. A caller must keep the returned id and call {@link
   * #authorizeWriter} before every future mutation and {@link #releaseWriter} on terminal paths.
   */
  @Transactional
  public WriterReservation reserveWriter(
      String parentSessionId,
      String requestId,
      String scope,
      String actorUserId,
      String actorOrgId) {
    String normalizedRequest = requireText(requestId, "writer requestId");
    String normalizedScope = requireText(scope, "writer scope");
    if (normalizedScope.length() > MAX_WRITER_SCOPE_CHARS) {
      throw new IllegalArgumentException(
          "writer scope exceeds " + MAX_WRITER_SCOPE_CHARS + " chars");
    }
    AgentSession parent =
        sessionRepository
            .findByIdForUpdate(parentSessionId)
            .orElseThrow(
                () -> new IllegalArgumentException("Agent session not found: " + parentSessionId));
    requireOwnership(parent, actorUserId, actorOrgId);
    requireParentActive(parent);

    List<WriterReservation> history = writerHistory(parentSessionId, normalizedScope);
    String reservationId = writerReservationId(parentSessionId, normalizedScope, normalizedRequest);
    Optional<WriterReservation> sameRequest =
        history.stream().filter(item -> reservationId.equals(item.reservationId())).findFirst();
    if (sameRequest.isPresent()) {
      WriterReservation prior = sameRequest.get();
      if ("ACTIVE".equals(prior.status())) return prior;
      throw new IllegalStateException("writer request was already released; use a new requestId");
    }
    Optional<WriterReservation> active =
        history.stream().filter(item -> "ACTIVE".equals(item.status())).findFirst();
    if (active.isPresent()) {
      throw new IllegalStateException(
          "single-writer scope is already reserved by " + active.get().reservationId());
    }
    Instant now = Instant.now();
    WriterReservation reservation =
        new WriterReservation(
            reservationId,
            parentSessionId,
            normalizedRequest,
            normalizedScope,
            SubagentProtocol.Authority.WRITE_SINGLE_WRITER.name(),
            "ACTIVE",
            now,
            null,
            null);
    append(parentSessionId, null, normalizedRequest, EVENT_WRITER_RESERVED, reservation);
    return reservation;
  }

  /** Fail-closed authority check used by the future mutation executor before each write. */
  @Transactional(readOnly = true)
  public WriterReservation authorizeWriter(
      String parentSessionId,
      String reservationId,
      String scope,
      String actorUserId,
      String actorOrgId) {
    requireText(reservationId, "writer reservationId");
    String normalizedScope = requireText(scope, "writer scope");
    AgentSession parent = requireOwnedSession(parentSessionId, actorUserId, actorOrgId);
    requireParentActive(parent);
    WriterReservation reservation =
        writerHistory(parentSessionId, normalizedScope).stream()
            .filter(item -> reservationId.equals(item.reservationId()))
            .filter(item -> "ACTIVE".equals(item.status()))
            .findFirst()
            .orElseThrow(() -> new SecurityException("writer reservation is not active"));
    if (!normalizedScope.equals(reservation.scope())) {
      throw new SecurityException("writer reservation scope mismatch");
    }
    return reservation;
  }

  /** Releases an active writer reservation; repeated release is idempotent. */
  @Transactional
  public WriterReservation releaseWriter(
      String parentSessionId,
      String reservationId,
      String reason,
      String actorUserId,
      String actorOrgId) {
    String normalizedId = requireText(reservationId, "writer reservationId");
    AgentSession parent =
        sessionRepository
            .findByIdForUpdate(parentSessionId)
            .orElseThrow(
                () -> new IllegalArgumentException("Agent session not found: " + parentSessionId));
    requireOwnership(parent, actorUserId, actorOrgId);
    WriterReservation current =
        writerHistory(parentSessionId, null).stream()
            .filter(item -> normalizedId.equals(item.reservationId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("writer reservation not found"));
    if ("RELEASED".equals(current.status())) return current;
    WriterReservation released =
        new WriterReservation(
            current.reservationId(),
            current.parentSessionId(),
            current.requestId(),
            current.scope(),
            current.authority(),
            "RELEASED",
            current.reservedAt(),
            Instant.now(),
            blankToDefault(reason, "released"));
    append(parentSessionId, null, normalizedId, EVENT_WRITER_RELEASED, released);
    return released;
  }

  /**
   * Fail-closed budget/cancellation check that an executor calls before each child turn.
   *
   * <p>The check also advances the child heartbeat when the turn is admitted. Admission alone is
   * NOT sufficient liveness evidence: a single model round can outlive the startup-recovery stale
   * window, so the executor must also renew the lease mid-turn through {@link #heartbeat}. The
   * timestamp is advisory only (the status/budget checks remain the authority) and is persisted in
   * the same transaction as the admission decision.
   */
  @Transactional
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
    if (reason == null) {
      child.setUpdatedAt(Instant.now());
      sessionRepository.save(child);
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

  /**
   * Renews a live child's lease mid-turn so a long provider call is not read as abandonment.
   *
   * <p>This carries no authority: it never changes status, budget or ownership, and it refuses to
   * touch a terminal child. It runs in its own transaction because the executing turn holds no
   * transaction of its own while the provider call is in flight.
   *
   * @return true when the lease was renewed; false when the child is gone or already terminal.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean heartbeat(String childSessionId, String actorUserId, String actorOrgId) {
    AgentSession child = requireOwnedSession(childSessionId, actorUserId, actorOrgId);
    if (!List.of("READY", "RUNNING").contains(child.getStatus())) return false;
    child.setUpdatedAt(Instant.now());
    sessionRepository.save(child);
    return true;
  }

  /** Claims a READY child for execution. Idempotent while the child is already RUNNING. */
  @Transactional
  public Handle start(String childSessionId, String actorUserId, String actorOrgId) {
    // Claim the READY row under a database lock. A plain findById allows two coordinators to both
    // observe READY and dispatch the same child before either transaction commits RUNNING.
    AgentSession child =
        sessionRepository
            .findByIdForUpdate(childSessionId)
            .orElseThrow(
                () -> new IllegalArgumentException("Agent session not found: " + childSessionId));
    requireOwnership(child, actorUserId, actorOrgId);
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
    String actionId = actionIdForChild(metadata.parentSessionId(), childSessionId);
    append(childSessionId, metadata.parentTurnId(), actionId, EVENT_CHILD_STARTED, metadata);
    append(
        metadata.parentSessionId(),
        metadata.parentTurnId(),
        actionId,
        EVENT_CHILD_STARTED,
        metadata);
    return handleFromMetadata(child, metadata);
  }

  @Transactional
  public Result complete(
      String childSessionId, Completion completion, String actorUserId, String actorOrgId) {
    AgentSession child = requireOwnedSession(childSessionId, actorUserId, actorOrgId);
    ChildMetadata metadata = metadataOf(childSessionId);
    requireRunningChild(child);
    if (completion == null || completion.summary() == null || completion.summary().isBlank()) {
      throw new IllegalArgumentException("completion summary is required");
    }
    if (metadata.resolvedContract().requiresEvidence() && completion.evidence().isEmpty()) {
      throw new IllegalArgumentException(
          metadata.role().name().toLowerCase(Locale.ROOT) + " completion requires evidence");
    }
    if (metadata.resolvedContract().requiresEvidence()
        && completion.evidence().stream()
            .anyMatch(
                evidence ->
                    evidence == null
                        || evidence.citation() == null
                        || evidence.citation().isBlank()
                        || evidence.summary() == null
                        || evidence.summary().isBlank())) {
      throw new IllegalArgumentException(
          metadata.role().name().toLowerCase(Locale.ROOT)
              + " completion evidence requires citation and summary");
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
    String actionId = actionIdForChild(metadata.parentSessionId(), childSessionId);
    append(childSessionId, metadata.parentTurnId(), actionId, EVENT_CHILD_COMPLETED, result);
    append(
        metadata.parentSessionId(),
        metadata.parentTurnId(),
        actionId,
        EVENT_CHILD_COMPLETED,
        result);
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
      String actionId = actionIdForChild(metadata.parentSessionId(), childSessionId);
      append(
          childSessionId, metadata.parentTurnId(), actionId, EVENT_CHILD_CANCELLED, cancellation);
      append(
          metadata.parentSessionId(),
          metadata.parentTurnId(),
          actionId,
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
    if (request.role().contract().authority() != SubagentProtocol.Authority.READ_ONLY) {
      throw new IllegalArgumentException("subagent role must have READ_ONLY authority");
    }
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

  private void validateParentTurn(AgentSession parent, String parentTurnId) {
    String normalizedTurnId = blankToNull(parentTurnId);
    if (normalizedTurnId == null) return;
    if (!turnRepository.existsByIdAndSessionId(normalizedTurnId, parent.getId())) {
      throw new IllegalArgumentException(
          "parentTurnId does not belong to parent session: " + normalizedTurnId);
    }
  }

  private List<WriterReservation> writerHistory(String parentSessionId, String scope) {
    List<AgentActionEvent> reserved = safeEvents(parentSessionId, EVENT_WRITER_RESERVED);
    List<AgentActionEvent> released = safeEvents(parentSessionId, EVENT_WRITER_RELEASED);
    List<AgentActionEvent> all = new java.util.ArrayList<>(reserved.size() + released.size());
    all.addAll(reserved);
    all.addAll(released);
    all.sort(
        Comparator.comparing(
                AgentActionEvent::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                AgentActionEvent::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
    java.util.Map<String, WriterReservation> latest = new java.util.LinkedHashMap<>();
    for (AgentActionEvent event : all) {
      WriterReservation reservation = read(event.getPayloadJson(), WriterReservation.class);
      if (scope == null || scope.equals(reservation.scope())) {
        latest.put(reservation.reservationId(), reservation);
      }
    }
    return List.copyOf(latest.values());
  }

  private static String writerReservationId(
      String parentSessionId, String scope, String requestId) {
    return UUID.nameUUIDFromBytes(
            ("subagent-writer:" + parentSessionId + ":" + scope + ":" + requestId)
                .getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static String requireText(String value, String label) {
    String normalized = blankToNull(value);
    if (normalized == null) throw new IllegalArgumentException(label + " is required");
    return normalized;
  }

  private int depthOf(String sessionId) {
    List<AgentActionEvent> events =
        eventRepository.findBySessionIdAndTypeOrderByCreatedAtAsc(sessionId, EVENT_CHILD_CREATED);
    return events.isEmpty()
        ? 0
        : read(events.getLast().getPayloadJson(), ChildMetadata.class).depth();
  }

  /**
   * RR-557 (2026-07-20) — resolves the ROOT of the subagent tree containing {@code sessionId}.
   *
   * <p>The tree turn/cost ceilings are advertised as whole-tree limits, but the spawn check used to
   * start its accounting at the immediate parent. A depth-1 child's own descendant budget therefore
   * started at zero, so a root could reserve its documented maximum breadth and every one of those
   * children could independently reserve the same again — the depth-2 tree could reserve five times
   * the authorized root budget. Walking to the root makes the ceiling mean what it says.
   *
   * <p>The walk is bounded by {@code MAX_DEPTH + 1} hops and a visited set, so a corrupt or cyclic
   * parent chain terminates instead of looping.
   */
  private String rootOf(String sessionId) {
    String current = sessionId;
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int hop = 0; hop <= MAX_DEPTH + 1; hop++) {
      if (current == null || !seen.add(current)) break;
      List<AgentActionEvent> events =
          eventRepository.findBySessionIdAndTypeOrderByCreatedAtAsc(current, EVENT_CHILD_CREATED);
      if (events.isEmpty()) return current;
      ChildMetadata metadata = read(events.getLast().getPayloadJson(), ChildMetadata.class);
      String parent = metadata == null ? null : blankToNull(metadata.parentSessionId());
      if (parent == null || parent.equals(current)) return current;
      current = parent;
    }
    return current == null ? sessionId : current;
  }

  private long countChildren(String sessionId) {
    return eventRepository.countBySessionIdAndType(sessionId, EVENT_CHILD_SPAWNED);
  }

  private static int remainingChildren(long existingChildren) {
    return (int)
        Math.max(0, Math.min(MAX_CHILDREN_PER_PARENT, MAX_CHILDREN_PER_PARENT - existingChildren));
  }

  /**
   * Calculates the requested budget of every descendant, not the spend so far. Requested budgets
   * are the safe reservation: enforcing them at spawn time prevents a nested tree from becoming
   * unbounded before any model turn has been recorded.
   *
   * <p>RR-557 (2026-07-20): accounting starts at the ROOT of {@code parentSessionId}'s tree, not at
   * that session. Starting at the immediate parent gave every nested child a fresh zero budget, so
   * the documented whole-tree turn/cost ceiling could be exceeded several times over by nesting.
   * Both the advisory delegation preview and the enforcing spawn path now read the same root
   * figure, so the number a caller is shown is the number that will be enforced.
   */
  private TreeBudget treeBudget(String parentSessionId) {
    return treeBudget(rootOf(parentSessionId), new java.util.HashSet<>());
  }

  private TreeBudget treeBudget(String sessionId, java.util.Set<String> visited) {
    if (!visited.add(sessionId)) return TreeBudget.ZERO;
    long turns = 0;
    double costUsd = 0;
    for (AgentActionEvent event : safeEvents(sessionId, EVENT_CHILD_SPAWNED)) {
      ParentLink link = read(event.getPayloadJson(), ParentLink.class);
      if (link == null || link.childSessionId() == null || link.child() == null) continue;
      turns += link.child().maxTurns();
      costUsd += link.child().maxCostUsd();
      TreeBudget nested = treeBudget(link.childSessionId(), visited);
      turns += nested.turns();
      costUsd += nested.costUsd();
    }
    return new TreeBudget(turns, costUsd);
  }

  private List<AgentActionEvent> safeEvents(String sessionId, String type) {
    List<AgentActionEvent> events =
        eventRepository.findBySessionIdAndTypeOrderByCreatedAtAsc(sessionId, type);
    return events == null ? List.of() : events;
  }

  /**
   * Recover the original model/tool action id from the parent's durable spawn link. Older rows may
   * not have an action id (pre-correlation protocol), so the helper deliberately returns null for
   * those rows and keeps replay backward compatible.
   */
  private String actionIdForChild(String parentSessionId, String childSessionId) {
    for (AgentActionEvent event : safeEvents(parentSessionId, EVENT_CHILD_SPAWNED)) {
      ParentLink link = read(event.getPayloadJson(), ParentLink.class);
      if (link != null && childSessionId.equals(link.childSessionId())) {
        return blankToNull(event.getActionId());
      }
    }
    return null;
  }

  private static String formatBudget(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
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

  private static void requireRunningChild(AgentSession child) {
    if (!"RUNNING".equals(child.getStatus())) {
      throw new IllegalStateException(
          "Subagent must be started before completion (status=" + child.getStatus() + ")");
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
      double maxCostUsd,
      RoleContract contract) {
    ChildMetadata(
        String parentSessionId,
        String parentTurnId,
        Role role,
        String objective,
        int depth,
        int maxTurns,
        double maxCostUsd) {
      this(parentSessionId, parentTurnId, role, objective, depth, maxTurns, maxCostUsd, null);
    }

    RoleContract resolvedContract() {
      return contract == null ? role.contract() : contract;
    }
  }

  record ParentLink(String childSessionId, ChildMetadata child) {}

  record Cancellation(String childSessionId, String reason) {}

  record CoordinatorTrigger(
      String requestId, String childSessionId, Role role, String objective, String trigger) {}

  private record TreeBudget(long turns, double costUsd) {
    private static final TreeBudget ZERO = new TreeBudget(0, 0);
  }
}
