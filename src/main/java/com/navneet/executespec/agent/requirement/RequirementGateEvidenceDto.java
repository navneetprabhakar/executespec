package com.navneet.executespec.agent.requirement;

import java.util.List;

/** Secret-free, version-neutral projection of one durable plan gate's RequirementIR evidence. */
public record RequirementGateEvidenceDto(
    String runId,
    String gateKey,
    String gateStatus,
    String bindingMode,
    String bindingStatus,
    String bindingReason,
    boolean enforced,
    boolean approvalReady,
    Integer schemaVersion,
    String compilerFingerprint,
    String sourceSha256,
    String exactPlanFingerprint,
    String artifactDigest,
    String boundDigest,
    String completeness,
    int requirementCount,
    int acceptanceCriterionCount,
    int taskBindingCount,
    int issueCount,
    int blockingIssueCount,
    List<Requirement> requirements,
    List<AcceptanceCriterion> acceptanceCriteria,
    List<TaskBinding> taskBindings,
    List<Issue> issues) {

  public RequirementGateEvidenceDto {
    requirements = immutable(requirements);
    acceptanceCriteria = immutable(acceptanceCriteria);
    taskBindings = immutable(taskBindings);
    issues = immutable(issues);
  }

  public record Requirement(
      String id,
      String text,
      String kind,
      String origin,
      String state,
      String inferenceRationale,
      String deferredReason,
      List<String> acceptanceIds,
      List<String> taskIds) {
    public Requirement {
      acceptanceIds = immutable(acceptanceIds);
      taskIds = immutable(taskIds);
    }
  }

  public record AcceptanceCriterion(
      String id, List<String> requirementIds, String kind, String evidenceType, String expected) {
    public AcceptanceCriterion {
      requirementIds = immutable(requirementIds);
    }
  }

  public record TaskBinding(String taskId, List<String> requirementIds) {
    public TaskBinding {
      requirementIds = immutable(requirementIds);
    }
  }

  /** The deliberately narrow issue shape rendered by run and execution compatibility clients. */
  public record Issue(String code, boolean blocking, String message, List<String> requirementIds) {
    public Issue {
      requirementIds = immutable(requirementIds);
    }
  }

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
