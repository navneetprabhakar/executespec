package com.navneet.executespec.agentsession.subagent;

import java.time.Instant;
import java.util.List;

/** Stable request/result records for bounded child-agent execution. */
public final class SubagentProtocol {

  private SubagentProtocol() {}

  public enum Role {
    SCOUT,
    VERIFIER
  }

  public record SpawnRequest(
      String requestId,
      String parentSessionId,
      String parentTurnId,
      Role role,
      String objective,
      int maxTurns,
      double maxCostUsd) {}

  /** Explicit coordinator opt-in envelope for the deterministic automatic execution seam. */
  public record CoordinatorExecutionRequest(
      SpawnRequest spawn, boolean automatic, String trigger, String eligibilityChildSessionId) {}

  /**
   * Side-effect-free coordinator result used before a parent asks to create a child.
   *
   * <p>The decision is deliberately deterministic: it only considers persisted parent state,
   * role/depth/capacity and reserved descendant budgets. It never calls a model and it never grants
   * a child write or run authority.
   */
  public record DelegationDecision(
      String parentSessionId,
      Role role,
      boolean eligible,
      String reason,
      int depth,
      long existingChildren,
      int remainingChildren,
      long reservedTurns,
      double reservedCostUsd,
      String childSessionId) {}

  public record Handle(
      String childSessionId,
      String parentSessionId,
      String parentTurnId,
      Role role,
      String objective,
      int depth,
      int maxTurns,
      double maxCostUsd,
      String status) {}

  public record Evidence(String citation, String summary) {}

  public record Completion(String summary, List<Evidence> evidence) {
    public Completion {
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  public record Result(
      Handle child,
      String summary,
      List<Evidence> evidence,
      long turnsUsed,
      double costUsd,
      Instant completedAt) {
    public Result {
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  /**
   * Ordered result envelope for bounded fan-out. A batch is allowed to return partial evidence: one
   * child failing must not hide the successful siblings or their persisted citations.
   */
  public record BatchResult(List<Result> results, List<BatchFailure> failures) {
    public BatchResult {
      results = results == null ? List.of() : List.copyOf(results);
      failures = failures == null ? List.of() : List.copyOf(failures);
    }
  }

  public record BatchFailure(String requestId, String childSessionId, String reason) {}

  public record BudgetState(
      String childSessionId,
      long turnsUsed,
      int maxTurns,
      double costUsd,
      double maxCostUsd,
      boolean canContinue,
      String reason) {}
}
