package com.navneet.executespec.agent.requirement;

import java.util.Map;
import java.util.Objects;

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
    adapter(contract.getSchemaVersion(), contract.getCompilerFingerprint())
        .verify(contract, exactSource);
  }

  private ApprovalAdapter adapter(int schemaVersion, String compilerFingerprint) {
    CompatibilityKey key = new CompatibilityKey(schemaVersion, compilerFingerprint);
    ApprovalAdapter adapter = adapters.get(key);
    if (adapter == null) {
      throw new IncompatibleRequirementIrException(
          key, "unsupported RequirementIR approval compatibility pair");
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

    public IncompatibleRequirementIrException(CompatibilityKey key, String reason) {
      super(
          reason
              + " schemaVersion="
              + key.schemaVersion()
              + " compilerFingerprint="
              + key.compilerFingerprint());
      this.key = key;
    }

    public CompatibilityKey key() {
      return key;
    }
  }

  private interface ApprovalAdapter {
    CompatibilityKey key();

    void verify(RunRequirementContract contract, String exactSource);
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
    public void verify(RunRequirementContract contract, String exactSource) {
      if (exactSource == null) {
        throw incompatible("RequirementIR V1 exact source is required");
      }
      RequirementIrV1WireDocument wireDocument;
      try {
        wireDocument = codec.read(contract.getCanonicalArtifactJson());
      } catch (RuntimeException invalid) {
        throw incompatible("RequirementIR V1 artifact cannot be decoded");
      }
      String canonical = codec.writeCanonical(wireDocument);
      String artifactDigest = RequirementContractDigests.artifactDigest(canonical);
      String boundDigest =
          RequirementContractDigests.boundDigest(
              contract.getExactPlanFingerprint(), contract.getSourceSha256(), artifactDigest);
      long blockingIssues =
          wireDocument.issues().stream().filter(issue -> issue.blocking()).count();
      boolean exact =
          Objects.equals(canonical, contract.getCanonicalArtifactJson())
              && wireDocument.schemaVersion() == contract.getSchemaVersion()
              && Objects.equals(
                  wireDocument.compilerFingerprint(), contract.getCompilerFingerprint())
              && Objects.equals(wireDocument.sourceSha256(), contract.getSourceSha256())
              && Objects.equals(
                  RequirementIrV1ApprovalValidator.sha256(exactSource), contract.getSourceSha256())
              && Objects.equals(artifactDigest, contract.getArtifactDigest())
              && Objects.equals(boundDigest, contract.getBoundDigest())
              && contract.getCompleteness() != null
              && wireDocument.completeness() != null
              && Objects.equals(
                  contract.getCompleteness().name(), wireDocument.completeness().name())
              && contract.getRequirementCount() == wireDocument.requirements().size()
              && contract.getAcceptanceCriterionCount() == wireDocument.acceptanceCriteria().size()
              && contract.getTaskBindingCount() == wireDocument.taskBindings().size()
              && contract.getIssueCount() == wireDocument.issues().size()
              && contract.getBlockingIssueCount() == Math.toIntExact(blockingIssues);
      if (!exact) throw incompatible("RequirementIR V1 persisted identity is inconsistent");
      if (!RequirementIrV1ApprovalValidator.validate(wireDocument, exactSource).approvable()) {
        throw incompatible("RequirementIR V1 artifact is not approval compatible");
      }
    }

    private IncompatibleRequirementIrException incompatible(String reason) {
      return new IncompatibleRequirementIrException(key, reason);
    }
  }
}
