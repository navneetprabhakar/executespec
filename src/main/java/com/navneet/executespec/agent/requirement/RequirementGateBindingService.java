package com.navneet.executespec.agent.requirement;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;

import org.springframework.stereotype.Service;

/** Selects and revalidates the exact RequirementIR artifact that a future plan gate may bind. */
@Service
public class RequirementGateBindingService {

  private final RunRequirementContractRepository repository;
  private final RequirementIrCodec codec;
  private final RequirementIrApprovalCompatibilityRegistry compatibilityRegistry;

  public RequirementGateBindingService(
      RunRequirementContractRepository repository,
      ObjectMapper objectMapper,
      RequirementIrApprovalCompatibilityRegistry compatibilityRegistry) {
    this.repository = repository;
    this.codec = new RequirementIrCodec(objectMapper);
    this.compatibilityRegistry = compatibilityRegistry;
  }

  /**
   * Select the current compiler/schema artifact for the exact source and exact persisted plan
   * bytes. No "latest artifact" fallback is permitted.
   *
   * <p>LEGACY never consults RequirementIR. SHADOW returns typed diagnostic outcomes. ENFORCED
   * returns only an approval-ready exact binding and otherwise throws a typed, HTTP-neutral
   * exception.
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
    if (requestedMode == RequestedMode.ENFORCED && !result.approvable()) {
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
    String sourceSha256 = RequirementIrValidator.sha256(exactSource);
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

    RequirementIrDocument document;
    try {
      document = codec.read(row.getCanonicalArtifactJson());
    } catch (RuntimeException invalidJson) {
      return failure(
          mode, BindingStatus.INVALID, ReasonCode.DECODE_FAILED, planFingerprint, sourceSha256);
    }
    if (!Objects.equals(codec.writeCanonical(document), row.getCanonicalArtifactJson())) {
      return failure(
          mode,
          BindingStatus.INVALID,
          ReasonCode.NON_CANONICAL_ARTIFACT,
          planFingerprint,
          sourceSha256);
    }
    if (document.schemaVersion() != row.getSchemaVersion()
        || !Objects.equals(document.compilerFingerprint(), row.getCompilerFingerprint())
        || !Objects.equals(document.sourceSha256(), row.getSourceSha256())) {
      return failure(
          mode,
          BindingStatus.INVALID,
          ReasonCode.DOCUMENT_METADATA_MISMATCH,
          planFingerprint,
          sourceSha256);
    }

    String artifactDigest =
        RequirementContractDigests.artifactDigest(row.getCanonicalArtifactJson());
    if (!Objects.equals(artifactDigest, row.getArtifactDigest())) {
      return failure(
          mode,
          BindingStatus.INVALID,
          ReasonCode.ARTIFACT_DIGEST_MISMATCH,
          planFingerprint,
          sourceSha256);
    }
    String boundDigest =
        RequirementContractDigests.boundDigest(planFingerprint, sourceSha256, artifactDigest);
    if (!Objects.equals(boundDigest, row.getBoundDigest())) {
      return failure(
          mode,
          BindingStatus.INVALID,
          ReasonCode.BOUND_DIGEST_MISMATCH,
          planFingerprint,
          sourceSha256);
    }

    int blockingIssueCount =
        Math.toIntExact(document.issues().stream().filter(issue -> issue.blocking()).count());
    if (row.getCompleteness() != document.completeness()
        || row.getRequirementCount() != document.requirements().size()
        || row.getAcceptanceCriterionCount() != document.acceptanceCriteria().size()
        || row.getTaskBindingCount() != document.taskBindings().size()
        || row.getIssueCount() != document.issues().size()
        || row.getBlockingIssueCount() != blockingIssueCount) {
      return failure(
          mode,
          BindingStatus.INVALID,
          ReasonCode.PERSISTED_SUMMARY_MISMATCH,
          planFingerprint,
          sourceSha256);
    }

    RequirementIrValidator.ValidationResult structure =
        RequirementIrValidator.validateStructure(document, exactSource);
    if (!structure.valid()) {
      String details =
          structure.violations().stream()
              .map(violation -> violation.code().name())
              .distinct()
              .sorted()
              .collect(Collectors.joining(","));
      return binding(
          mode, BindingStatus.INVALID, ReasonCode.STRUCTURE_INVALID, row, false, details);
    }

    RequirementIrValidator.CompletenessDecision approval =
        RequirementIrValidator.validateForApproval(document, exactSource);
    if (!approval.approvable()) {
      String details =
          approval.blockingCodes().stream()
              .map(Enum::name)
              .sorted()
              .collect(Collectors.joining(","));
      return binding(mode, BindingStatus.BLOCKED, ReasonCode.APPROVAL_BLOCKED, row, false, details);
    }
    try {
      compatibilityRegistry.verifyApprovalCompatible(row, exactSource);
    } catch (
        RequirementIrApprovalCompatibilityRegistry.IncompatibleRequirementIrException
            incompatible) {
      return binding(
          mode,
          BindingStatus.INVALID,
          ReasonCode.APPROVAL_COMPATIBILITY_FAILED,
          row,
          false,
          incompatible.getMessage());
    }
    return binding(mode, BindingStatus.BOUND, ReasonCode.NONE, row, true, null);
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
