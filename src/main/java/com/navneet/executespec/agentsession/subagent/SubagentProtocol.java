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

  public record BudgetState(
      String childSessionId,
      long turnsUsed,
      int maxTurns,
      double costUsd,
      double maxCostUsd,
      boolean canContinue,
      String reason) {}
}
