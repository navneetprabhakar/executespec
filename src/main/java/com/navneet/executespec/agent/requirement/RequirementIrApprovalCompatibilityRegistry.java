package com.navneet.executespec.agent.requirement;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;

import org.springframework.stereotype.Component;

/** Retains approval validators for RequirementIR versions that may remain at an open plan gate. */
@Component
public final class RequirementIrApprovalCompatibilityRegistry {
  private final Map<CompatibilityKey, ApprovalAdapter> adapters;
  private final CompatibilityKey current;

  public RequirementIrApprovalCompatibilityRegistry(ObjectMapper objectMapper) {
    ApprovalAdapter v1 = new V1ApprovalAdapter(objectMapper);
    this.adapters = Map.of(v1.key(), v1);
    this.current = v1.key();
  }

  public CompatibilityKey current() {
    return current;
  }

  public void requireSupported(int schemaVersion, String compilerFingerprint) {
    adapter(schemaVersion, compilerFingerprint);
  }

  public void verifyApprovalCompatible(RunRequirementContract contract, String exactSource) {
    if (contract == null) {
      throw new IllegalArgumentException("RequirementIR contract is required");
    }
    FrozenProjection projection =
        adapter(contract.getSchemaVersion(), contract.getCompilerFingerprint())
            .project(contract, exactSource);
    if (!projection.approvalReady()) {
      throw new IncompatibleRequirementIrException(
          new CompatibilityKey(contract.getSchemaVersion(), contract.getCompilerFingerprint()),
          FailureKind.APPROVAL_BLOCKED,
          "RequirementIR artifact is not approval compatible");
    }
  }

  /**
   * Strictly validates and projects a retained frozen RequirementIR version.
   *
   * <p>This deliberately accepts a structurally valid {@code BLOCKED} document so authorized read
   * surfaces can explain why approval is unavailable. Approval callers must continue through {@link
   * #verifyApprovalCompatible(RunRequirementContract, String)}. The retained wire codec is never
   * exposed outside this registry.
   */
  public FrozenProjection projectFrozenVersion(
      RunRequirementContract contract, String exactSource) {
    if (contract == null) {
      throw new IllegalArgumentException("RequirementIR contract is required");
    }
    return adapter(contract.getSchemaVersion(), contract.getCompilerFingerprint())
        .project(contract, exactSource);
  }

  private ApprovalAdapter adapter(int schemaVersion, String compilerFingerprint) {
    CompatibilityKey key = new CompatibilityKey(schemaVersion, compilerFingerprint);
    ApprovalAdapter adapter = adapters.get(key);
    if (adapter == null) {
      throw new IncompatibleRequirementIrException(
          key, FailureKind.UNSUPPORTED, "unsupported RequirementIR approval compatibility pair");
    }
    return adapter;
  }

  public record CompatibilityKey(int schemaVersion, String compilerFingerprint) {
    public CompatibilityKey {
      if (schemaVersion <= 0 || compilerFingerprint == null || compilerFingerprint.isBlank()) {
        throw new IllegalArgumentException("Incomplete RequirementIR compatibility key");
      }
    }
  }

  public static final class IncompatibleRequirementIrException extends IllegalStateException {
    private final CompatibilityKey key;
    private final FailureKind failureKind;

    public IncompatibleRequirementIrException(
        CompatibilityKey key, FailureKind failureKind, String reason) {
      super(
          reason
              + " schemaVersion="
              + key.schemaVersion()
              + " compilerFingerprint="
              + key.compilerFingerprint());
      this.key = key;
      this.failureKind = failureKind;
    }

    public CompatibilityKey key() {
      return key;
    }

    public FailureKind failureKind() {
      return failureKind;
    }
  }

  public enum FailureKind {
    UNSUPPORTED,
    DECODE_FAILED,
    NON_CANONICAL,
    DOCUMENT_METADATA_MISMATCH,
    PERSISTED_SUMMARY_MISMATCH,
    STRUCTURE_INVALID,
    APPROVAL_BLOCKED
  }

  private interface ApprovalAdapter {
    CompatibilityKey key();

    FrozenProjection project(RunRequirementContract contract, String exactSource);
  }

  /** Retained V1 adapter: do not replace it when a later compiler/schema pair becomes current. */
  private static final class V1ApprovalAdapter implements ApprovalAdapter {
    private static final int SCHEMA_VERSION = RequirementIrV1WireDocument.SCHEMA_VERSION;
    private static final String COMPILER_FINGERPRINT =
        RequirementIrV1WireDocument.COMPILER_FINGERPRINT;
    private final RequirementIrV1WireCodec codec;
    private final CompatibilityKey key = new CompatibilityKey(SCHEMA_VERSION, COMPILER_FINGERPRINT);

    private V1ApprovalAdapter(ObjectMapper objectMapper) {
      this.codec = new RequirementIrV1WireCodec(objectMapper);
    }

    @Override
    public CompatibilityKey key() {
      return key;
    }

    @Override
    public FrozenProjection project(RunRequirementContract contract, String exactSource) {
      if (exactSource == null) {
        throw incompatible(
            FailureKind.DOCUMENT_METADATA_MISMATCH, "RequirementIR V1 exact source is required");
      }
      RequirementIrV1WireDocument wireDocument;
      try {
        wireDocument = codec.read(contract.getCanonicalArtifactJson());
      } catch (RuntimeException invalid) {
        throw incompatible(
            FailureKind.DECODE_FAILED, "RequirementIR V1 artifact cannot be decoded");
      }
      String canonical = codec.writeCanonical(wireDocument);
      if (!Objects.equals(canonical, contract.getCanonicalArtifactJson())) {
        throw incompatible(FailureKind.NON_CANONICAL, "RequirementIR V1 artifact is not canonical");
      }
      String artifactDigest = RequirementContractDigests.artifactDigest(canonical);
      String boundDigest =
          RequirementContractDigests.boundDigest(
              contract.getExactPlanFingerprint(), contract.getSourceSha256(), artifactDigest);
      long blockingIssues =
          wireDocument.issues().stream().filter(issue -> issue.blocking()).count();
      boolean metadataExact =
          wireDocument.schemaVersion() == contract.getSchemaVersion()
              && Objects.equals(
                  wireDocument.compilerFingerprint(), contract.getCompilerFingerprint())
              && Objects.equals(wireDocument.sourceSha256(), contract.getSourceSha256())
              && Objects.equals(
                  RequirementIrV1ApprovalValidator.sha256(exactSource), contract.getSourceSha256())
              && Objects.equals(artifactDigest, contract.getArtifactDigest())
              && Objects.equals(boundDigest, contract.getBoundDigest());
      if (!metadataExact) {
        throw incompatible(
            FailureKind.DOCUMENT_METADATA_MISMATCH,
            "RequirementIR V1 persisted identity is inconsistent");
      }
      boolean summaryExact =
          contract.getCompleteness() != null
              && wireDocument.completeness() != null
              && Objects.equals(
                  contract.getCompleteness().name(), wireDocument.completeness().name())
              && contract.getRequirementCount() == wireDocument.requirements().size()
              && contract.getAcceptanceCriterionCount() == wireDocument.acceptanceCriteria().size()
              && contract.getTaskBindingCount() == wireDocument.taskBindings().size()
              && contract.getIssueCount() == wireDocument.issues().size()
              && contract.getBlockingIssueCount() == Math.toIntExact(blockingIssues);
      if (!summaryExact) {
        throw incompatible(
            FailureKind.PERSISTED_SUMMARY_MISMATCH,
            "RequirementIR V1 persisted summary is inconsistent");
      }
      RequirementIrV1ApprovalValidator.ApprovalDecision decision =
          RequirementIrV1ApprovalValidator.validate(wireDocument, exactSource);
      if (!decision.violations().isEmpty()) {
        throw incompatible(FailureKind.STRUCTURE_INVALID, "RequirementIR V1 structure is invalid");
      }
      Set<String> persistedBlockingCodes =
          wireDocument.issues().stream()
              .filter(RequirementIrV1WireDocument.RequirementIssue::blocking)
              .map(issue -> issue.code().name())
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      List<String> derivedBlockingCodes =
          decision.blockingCodes().stream()
              .filter(code -> !persistedBlockingCodes.contains(code))
              .sorted()
              .toList();
      return projection(wireDocument, decision.approvable(), derivedBlockingCodes);
    }

    private static FrozenProjection projection(
        RequirementIrV1WireDocument document,
        boolean approvalReady,
        List<String> derivedBlockingCodes) {
      List<IssueProjection> persistedIssues =
          document.issues().stream()
              .map(
                  issue ->
                      new IssueProjection(
                          name(issue.code()),
                          issue.blocking(),
                          issue.message(),
                          issue.requirementIds()))
              .toList();
      List<IssueProjection> visibleIssues = new java.util.ArrayList<>(persistedIssues);
      derivedBlockingCodes.forEach(
          code ->
              visibleIssues.add(
                  new IssueProjection(
                      code, true, "Approval is blocked by frozen validation: " + code, List.of())));
      return new FrozenProjection(
          document.schemaVersion(),
          document.compilerFingerprint(),
          document.sourceSha256(),
          document.completeness().name(),
          approvalReady,
          document.requirements().stream()
              .map(
                  requirement ->
                      new RequirementProjection(
                          requirement.id(),
                          requirement.text(),
                          name(requirement.kind()),
                          name(requirement.origin()),
                          name(requirement.state()),
                          requirement.inferenceRationale(),
                          requirement.deferredReason(),
                          requirement.acceptanceIds(),
                          requirement.taskIds()))
              .toList(),
          document.acceptanceCriteria().stream()
              .map(
                  criterion ->
                      new AcceptanceCriterionProjection(
                          criterion.id(),
                          criterion.requirementIds(),
                          name(criterion.kind()),
                          name(criterion.evidenceType()),
                          criterion.expected()))
              .toList(),
          document.taskBindings().stream()
              .map(task -> new TaskBindingProjection(task.taskId(), task.requirementIds()))
              .toList(),
          visibleIssues,
          persistedIssues.size(),
          Math.toIntExact(persistedIssues.stream().filter(IssueProjection::blocking).count()));
    }

    private static String name(Enum<?> value) {
      return value == null ? null : value.name();
    }

    private IncompatibleRequirementIrException incompatible(
        FailureKind failureKind, String reason) {
      return new IncompatibleRequirementIrException(key, failureKind, reason);
    }
  }

  public record FrozenProjection(
      int schemaVersion,
      String compilerFingerprint,
      String sourceSha256,
      String completeness,
      boolean approvalReady,
      List<RequirementProjection> requirements,
      List<AcceptanceCriterionProjection> acceptanceCriteria,
      List<TaskBindingProjection> taskBindings,
      List<IssueProjection> issues,
      int persistedIssueCount,
      int persistedBlockingIssueCount) {
    public FrozenProjection {
      requirements = immutable(requirements);
      acceptanceCriteria = immutable(acceptanceCriteria);
      taskBindings = immutable(taskBindings);
      issues = immutable(issues);
    }
  }

  public record RequirementProjection(
      String id,
      String text,
      String kind,
      String origin,
      String state,
      String inferenceRationale,
      String deferredReason,
      List<String> acceptanceIds,
      List<String> taskIds) {
    public RequirementProjection {
      acceptanceIds = immutable(acceptanceIds);
      taskIds = immutable(taskIds);
    }
  }

  public record AcceptanceCriterionProjection(
      String id, List<String> requirementIds, String kind, String evidenceType, String expected) {
    public AcceptanceCriterionProjection {
      requirementIds = immutable(requirementIds);
    }
  }

  public record TaskBindingProjection(String taskId, List<String> requirementIds) {
    public TaskBindingProjection {
      requirementIds = immutable(requirementIds);
    }
  }

  public record IssueProjection(
      String code, boolean blocking, String message, List<String> requirementIds) {
    public IssueProjection {
      requirementIds = immutable(requirementIds);
    }
  }

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
