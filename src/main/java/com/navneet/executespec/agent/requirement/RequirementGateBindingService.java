package com.navneet.executespec.agent.requirement;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;

import org.springframework.stereotype.Service;

/** Selects and revalidates the exact RequirementIR artifact that a future plan gate may bind. */
@Service
public class RequirementGateBindingService {

  private final RunRequirementContractRepository repository;
  private final RequirementIrApprovalCompatibilityRegistry compatibilityRegistry;

  public RequirementGateBindingService(
      RunRequirementContractRepository repository,
      ObjectMapper objectMapper,
      RequirementIrApprovalCompatibilityRegistry compatibilityRegistry) {
    this.repository = repository;
    if (objectMapper == null) throw new IllegalArgumentException("objectMapper is required");
    this.compatibilityRegistry = compatibilityRegistry;
  }

  /**
   * Select the current compiler/schema artifact for the exact source and exact persisted plan
   * bytes. No "latest artifact" fallback is permitted.
   *
   * <p>LEGACY never consults RequirementIR. SHADOW returns typed diagnostic outcomes. ENFORCED
   * returns an approval-ready exact binding or a structurally valid BLOCKED binding that may open a
   * visible pending gate but cannot be approved. Missing, invalid, and unavailable bindings throw a
   * typed, HTTP-neutral exception.
   */
  public RequirementGateBinding select(
      String runId, String exactSource, String exactPlanJson, RequestedMode requestedMode) {
    if (requestedMode == null) {
      throw new RequirementGateBindingException(ReasonCode.INVALID_MODE, null);
    }
    if (requestedMode == RequestedMode.LEGACY) {
      return RequirementGateBinding.legacy();
    }

    RequirementGateBinding result =
        selectNonLegacy(runId, exactSource, exactPlanJson, requestedMode);
    if (requestedMode == RequestedMode.ENFORCED
        && !result.approvable()
        && result.status() != BindingStatus.BLOCKED) {
      throw new RequirementGateBindingException(result.reasonCode(), result);
    }
    return result;
  }

  private RequirementGateBinding selectNonLegacy(
      String runId, String exactSource, String exactPlanJson, RequestedMode mode) {
    if (!hasText(runId) || exactSource == null || !hasText(exactPlanJson)) {
      return failure(mode, BindingStatus.INVALID, ReasonCode.INVALID_INPUT, null, null);
    }

    String planFingerprint = ExactPlanFingerprint.sha256(exactPlanJson);
    String sourceSha256 = RequirementIrV1ApprovalValidator.sha256(exactSource);
    var compatibility = compatibilityRegistry.current();
    RunRequirementContract row;
    try {
      row =
          repository
              .findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
                  runId,
                  planFingerprint,
                  compatibility.schemaVersion(),
                  compatibility.compilerFingerprint())
              .orElse(null);
    } catch (RuntimeException repositoryFailure) {
      return failure(
          mode,
          BindingStatus.UNAVAILABLE,
          ReasonCode.REPOSITORY_UNAVAILABLE,
          planFingerprint,
          sourceSha256);
    }
    if (row == null) {
      return failure(
          mode, BindingStatus.MISSING, ReasonCode.ARTIFACT_MISSING, planFingerprint, sourceSha256);
    }
    return validateRow(mode, runId, exactSource, planFingerprint, sourceSha256, row);
  }

  private RequirementGateBinding validateRow(
      RequestedMode mode,
      String runId,
      String exactSource,
      String planFingerprint,
      String sourceSha256,
      RunRequirementContract row) {
    if (!Objects.equals(runId, row.getRunId())
        || !Objects.equals(planFingerprint, row.getExactPlanFingerprint())
        || row.getSchemaVersion() != compatibilityRegistry.current().schemaVersion()
        || !Objects.equals(
            compatibilityRegistry.current().compilerFingerprint(), row.getCompilerFingerprint())) {
      return failure(
          mode, BindingStatus.INVALID, ReasonCode.IDENTITY_MISMATCH, planFingerprint, sourceSha256);
    }
    if (!Objects.equals(sourceSha256, row.getSourceSha256())) {
      return failure(
          mode, BindingStatus.INVALID, ReasonCode.SOURCE_MISMATCH, planFingerprint, sourceSha256);
    }
    String artifactDigest =
        RequirementContractDigests.artifactDigest(row.getCanonicalArtifactJson());
    if (!Objects.equals(artifactDigest, row.getArtifactDigest())) {
      return failureWithRow(
          mode, row, ReasonCode.ARTIFACT_DIGEST_MISMATCH, "stored artifact digest mismatch");
    }
    String boundDigest =
        RequirementContractDigests.boundDigest(planFingerprint, sourceSha256, artifactDigest);
    if (!Objects.equals(boundDigest, row.getBoundDigest())) {
      return failureWithRow(
          mode, row, ReasonCode.BOUND_DIGEST_MISMATCH, "stored bound digest mismatch");
    }

    RequirementIrApprovalCompatibilityRegistry.FrozenProjection projection;
    try {
      projection = compatibilityRegistry.projectFrozenVersion(row, exactSource);
    } catch (
        RequirementIrApprovalCompatibilityRegistry.IncompatibleRequirementIrException
            incompatible) {
      return failureWithRow(
          mode,
          row,
          mapCompatibilityFailure(incompatible.failureKind()),
          incompatible.getMessage());
    }
    if (!projection.approvalReady()) {
      String details =
          projection.issues().stream()
              .filter(RequirementIrApprovalCompatibilityRegistry.IssueProjection::blocking)
              .map(RequirementIrApprovalCompatibilityRegistry.IssueProjection::code)
              .filter(Objects::nonNull)
              .distinct()
              .sorted()
              .collect(java.util.stream.Collectors.joining(","));
      return binding(mode, BindingStatus.BLOCKED, ReasonCode.APPROVAL_BLOCKED, row, false, details);
    }
    return binding(mode, BindingStatus.BOUND, ReasonCode.NONE, row, true, null);
  }

  private static ReasonCode mapCompatibilityFailure(
      RequirementIrApprovalCompatibilityRegistry.FailureKind failureKind) {
    return switch (failureKind) {
      case DECODE_FAILED -> ReasonCode.DECODE_FAILED;
      case NON_CANONICAL -> ReasonCode.NON_CANONICAL_ARTIFACT;
      case DOCUMENT_METADATA_MISMATCH -> ReasonCode.DOCUMENT_METADATA_MISMATCH;
      case PERSISTED_SUMMARY_MISMATCH -> ReasonCode.PERSISTED_SUMMARY_MISMATCH;
      case STRUCTURE_INVALID -> ReasonCode.STRUCTURE_INVALID;
      case APPROVAL_BLOCKED -> ReasonCode.APPROVAL_BLOCKED;
      case UNSUPPORTED -> ReasonCode.APPROVAL_COMPATIBILITY_FAILED;
    };
  }

  private static RequirementGateBinding failureWithRow(
      RequestedMode mode, RunRequirementContract row, ReasonCode reason, String details) {
    return binding(mode, BindingStatus.INVALID, reason, row, false, details);
  }

  private static RequirementGateBinding binding(
      RequestedMode mode,
      BindingStatus status,
      ReasonCode reason,
      RunRequirementContract row,
      boolean approvable,
      String details) {
    return new RequirementGateBinding(
        mode,
        status,
        row.getId(),
        row.getSchemaVersion(),
        row.getCompilerFingerprint(),
        row.getSourceSha256(),
        row.getExactPlanFingerprint(),
        row.getArtifactDigest(),
        row.getBoundDigest(),
        row.getCompleteness(),
        row.getBlockingIssueCount(),
        approvable,
        reason,
        details);
  }

  private static RequirementGateBinding failure(
      RequestedMode mode,
      BindingStatus status,
      ReasonCode reason,
      String planFingerprint,
      String sourceSha256) {
    return new RequirementGateBinding(
        mode,
        status,
        null,
        RequirementIrDocument.CURRENT_SCHEMA_VERSION,
        RequirementIrCompiler.COMPILER_FINGERPRINT,
        sourceSha256,
        planFingerprint,
        null,
        null,
        null,
        0,
        false,
        reason,
        null);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public enum RequestedMode {
    LEGACY,
    SHADOW,
    ENFORCED
  }

  public enum BindingStatus {
    LEGACY,
    BOUND,
    BLOCKED,
    MISSING,
    INVALID,
    UNAVAILABLE
  }

  public enum ReasonCode {
    NONE,
    LEGACY_MODE,
    INVALID_MODE,
    INVALID_INPUT,
    ARTIFACT_MISSING,
    REPOSITORY_UNAVAILABLE,
    IDENTITY_MISMATCH,
    SOURCE_MISMATCH,
    DECODE_FAILED,
    NON_CANONICAL_ARTIFACT,
    DOCUMENT_METADATA_MISMATCH,
    ARTIFACT_DIGEST_MISMATCH,
    BOUND_DIGEST_MISMATCH,
    PERSISTED_SUMMARY_MISMATCH,
    STRUCTURE_INVALID,
    APPROVAL_BLOCKED,
    APPROVAL_COMPATIBILITY_FAILED
  }

  public record RequirementGateBinding(
      RequestedMode mode,
      BindingStatus status,
      UUID contractId,
      Integer schemaVersion,
      String compilerFingerprint,
      String sourceSha256,
      String exactPlanFingerprint,
      String artifactDigest,
      String boundDigest,
      RequirementIrDocument.Completeness completeness,
      int blockingIssueCount,
      boolean approvable,
      ReasonCode reasonCode,
      String reasonDetails) {
    static RequirementGateBinding legacy() {
      return new RequirementGateBinding(
          RequestedMode.LEGACY,
          BindingStatus.LEGACY,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          0,
          true,
          ReasonCode.LEGACY_MODE,
          null);
    }
  }

  public static final class RequirementGateBindingException extends IllegalStateException {
    private final ReasonCode reasonCode;
    private final RequirementGateBinding binding;

    RequirementGateBindingException(ReasonCode reasonCode, RequirementGateBinding binding) {
      super("RequirementIR gate binding failed: " + reasonCode);
      this.reasonCode = reasonCode;
      this.binding = binding;
    }

    public ReasonCode reasonCode() {
      return reasonCode;
    }

    public RequirementGateBinding binding() {
      return binding;
    }
  }
}
