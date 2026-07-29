package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.BindingStatus;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.ReasonCode;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequirementGateBindingException;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequirementGateBindingServiceTest {
  private static final String RUN_ID = "run-bind-1";
  private static final String SOURCE = "Return 404 when an order is absent.";

  @Mock RunRequirementContractRepository repository;

  private final ObjectMapper mapper =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private final RequirementIrCompiler compiler = new RequirementIrCompiler();
  private final RequirementIrCodec codec = new RequirementIrCodec(mapper);
  private final RequirementIrApprovalCompatibilityRegistry compatibilityRegistry =
      new RequirementIrApprovalCompatibilityRegistry(mapper);
  private RequirementGateBindingService service;
  private String exactPlanJson;

  @BeforeEach
  void setUp() throws Exception {
    service = new RequirementGateBindingService(repository, mapper, compatibilityRegistry);
    exactPlanJson = mapper.writeValueAsString(plan(SOURCE));
  }

  @Test
  void legacyIsExplicitAndNeverConsultsArtifactStorage() {
    var binding = service.select(RUN_ID, null, null, RequestedMode.LEGACY);

    assertThat(binding.status()).isEqualTo(BindingStatus.LEGACY);
    assertThat(binding.approvable()).isTrue();
    assertThat(binding.reasonCode()).isEqualTo(ReasonCode.LEGACY_MODE);
    verifyNoInteractions(repository);
  }

  @Test
  void approvalCompatibilityRegistryRetainsV1AndFailsClosedForUnknownPair() {
    assertThat(compatibilityRegistry.current().schemaVersion())
        .isEqualTo(RequirementIrDocument.CURRENT_SCHEMA_VERSION);
    assertThat(compatibilityRegistry.current().compilerFingerprint())
        .isEqualTo(RequirementIrCompiler.COMPILER_FINGERPRINT);

    assertThatThrownBy(() -> compatibilityRegistry.requireSupported(2, "f".repeat(64)))
        .isInstanceOf(
            RequirementIrApprovalCompatibilityRegistry.IncompatibleRequirementIrException.class)
        .hasMessageContaining("unsupported RequirementIR approval compatibility pair");
  }

  @Test
  void selectsOnlyExactCurrentSchemaCompilerArtifactAndStrictlyRevalidatesIt() {
    RunRequirementContract row = validRow(SOURCE, exactPlanJson);
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            codec.exactPlanFingerprint(exactPlanJson),
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT))
        .thenReturn(Optional.of(row));

    var binding = service.select(RUN_ID, SOURCE, exactPlanJson, RequestedMode.ENFORCED);

    assertThat(binding.status()).isEqualTo(BindingStatus.BOUND);
    assertThat(binding.approvable()).isTrue();
    assertThat(binding.contractId()).isEqualTo(row.getId());
    assertThat(binding.boundDigest()).isEqualTo(row.getBoundDigest());
    verify(repository)
        .findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            codec.exactPlanFingerprint(exactPlanJson),
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT);
  }

  @Test
  void byteDifferentPlanNeverFallsBackToAnotherArtifactForTheRun() throws Exception {
    String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan(SOURCE));
    String prettyFingerprint = codec.exactPlanFingerprint(pretty);
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            prettyFingerprint,
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT))
        .thenReturn(Optional.empty());

    var shadow = service.select(RUN_ID, SOURCE, pretty, RequestedMode.SHADOW);

    assertThat(shadow.status()).isEqualTo(BindingStatus.MISSING);
    assertThat(shadow.exactPlanFingerprint()).isEqualTo(prettyFingerprint);
    assertThat(prettyFingerprint).isNotEqualTo(codec.exactPlanFingerprint(exactPlanJson));
  }

  @Test
  void enforcedModeFailsClosedWithTypedReasonWhenArtifactIsMissing() {
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            codec.exactPlanFingerprint(exactPlanJson),
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.select(RUN_ID, SOURCE, exactPlanJson, RequestedMode.ENFORCED))
        .isInstanceOfSatisfying(
            RequirementGateBindingException.class,
            error -> {
              assertThat(error.reasonCode()).isEqualTo(ReasonCode.ARTIFACT_MISSING);
              assertThat(error.binding().status()).isEqualTo(BindingStatus.MISSING);
            });
  }

  @Test
  void shadowReturnsTypedInvalidWhenPersistedDigestOrSummaryWasTampered() {
    RunRequirementContract valid = validRow(SOURCE, exactPlanJson);
    RunRequirementContract tampered = copy(valid, "0".repeat(64), valid.getRequirementCount());
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            valid.getExactPlanFingerprint(),
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT))
        .thenReturn(Optional.of(tampered));

    var digestFailure = service.select(RUN_ID, SOURCE, exactPlanJson, RequestedMode.SHADOW);

    assertThat(digestFailure.status()).isEqualTo(BindingStatus.INVALID);
    assertThat(digestFailure.reasonCode()).isEqualTo(ReasonCode.ARTIFACT_DIGEST_MISMATCH);

    RunRequirementContract wrongCount =
        copy(valid, valid.getArtifactDigest(), valid.getRequirementCount() + 1);
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            valid.getExactPlanFingerprint(),
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT))
        .thenReturn(Optional.of(wrongCount));

    var countFailure = service.select(RUN_ID, SOURCE, exactPlanJson, RequestedMode.SHADOW);
    assertThat(countFailure.reasonCode()).isEqualTo(ReasonCode.PERSISTED_SUMMARY_MISMATCH);
  }

  @Test
  void nonCanonicalStoredJsonIsRejectedEvenWhenItsDigestMatchesThoseBytes() throws Exception {
    RunRequirementContract valid = validRow(SOURCE, exactPlanJson);
    RequirementIrDocument document = codec.read(valid.getCanonicalArtifactJson());
    String prettyArtifact = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document);
    String artifactDigest = RequirementContractDigests.artifactDigest(prettyArtifact);
    RunRequirementContract nonCanonical =
        RunRequirementContract.builder()
            .id(valid.getId())
            .runId(valid.getRunId())
            .schemaVersion(valid.getSchemaVersion())
            .compilerFingerprint(valid.getCompilerFingerprint())
            .sourceSha256(valid.getSourceSha256())
            .exactPlanFingerprint(valid.getExactPlanFingerprint())
            .canonicalArtifactJson(prettyArtifact)
            .artifactDigest(artifactDigest)
            .boundDigest(
                RequirementContractDigests.boundDigest(
                    valid.getExactPlanFingerprint(), valid.getSourceSha256(), artifactDigest))
            .completeness(valid.getCompleteness())
            .requirementCount(valid.getRequirementCount())
            .acceptanceCriterionCount(valid.getAcceptanceCriterionCount())
            .taskBindingCount(valid.getTaskBindingCount())
            .issueCount(valid.getIssueCount())
            .blockingIssueCount(valid.getBlockingIssueCount())
            .build();
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            valid.getExactPlanFingerprint(),
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT))
        .thenReturn(Optional.of(nonCanonical));

    var binding = service.select(RUN_ID, SOURCE, exactPlanJson, RequestedMode.SHADOW);

    assertThat(binding.reasonCode()).isEqualTo(ReasonCode.NON_CANONICAL_ARTIFACT);
  }

  @Test
  void approvalBlockedArtifactIsObservableAndCanOpenAnEnforcedPendingGate() throws Exception {
    String blockedSource = "Do the right thing when appropriate.";
    String blockedPlan = mapper.writeValueAsString(plan(blockedSource));
    RunRequirementContract blocked = validRow(blockedSource, blockedPlan);
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            RUN_ID,
            blocked.getExactPlanFingerprint(),
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT))
        .thenReturn(Optional.of(blocked));

    var shadow = service.select(RUN_ID, blockedSource, blockedPlan, RequestedMode.SHADOW);
    assertThat(shadow.status()).isEqualTo(BindingStatus.BLOCKED);
    assertThat(shadow.reasonCode()).isEqualTo(ReasonCode.APPROVAL_BLOCKED);

    var enforced = service.select(RUN_ID, blockedSource, blockedPlan, RequestedMode.ENFORCED);
    assertThat(enforced.status()).isEqualTo(BindingStatus.BLOCKED);
    assertThat(enforced.approvable()).isFalse();
    assertThat(enforced.contractId()).isEqualTo(blocked.getId());
  }

  @Test
  void invalidInputInEnforcedModeFailsClosedBeforeRepositoryAccess() {
    assertThatThrownBy(() -> service.select(RUN_ID, SOURCE, " ", RequestedMode.ENFORCED))
        .isInstanceOfSatisfying(
            RequirementGateBindingException.class,
            error -> assertThat(error.reasonCode()).isEqualTo(ReasonCode.INVALID_INPUT));
    verify(repository, never())
        .findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString());
  }

  private RunRequirementContract validRow(String source, String planJson) {
    RequirementIrDocument document = compiler.compile(source, plan(source));
    String canonicalJson = codec.writeCanonical(document);
    String planFingerprint = codec.exactPlanFingerprint(planJson);
    String artifactDigest = RequirementContractDigests.artifactDigest(canonicalJson);
    long blocking = document.issues().stream().filter(issue -> issue.blocking()).count();
    return RunRequirementContract.builder()
        .id(UUID.randomUUID())
        .runId(RUN_ID)
        .schemaVersion(document.schemaVersion())
        .compilerFingerprint(document.compilerFingerprint())
        .sourceSha256(document.sourceSha256())
        .exactPlanFingerprint(planFingerprint)
        .canonicalArtifactJson(canonicalJson)
        .artifactDigest(artifactDigest)
        .boundDigest(
            RequirementContractDigests.boundDigest(
                planFingerprint, document.sourceSha256(), artifactDigest))
        .completeness(document.completeness())
        .requirementCount(document.requirements().size())
        .acceptanceCriterionCount(document.acceptanceCriteria().size())
        .taskBindingCount(document.taskBindings().size())
        .issueCount(document.issues().size())
        .blockingIssueCount(Math.toIntExact(blocking))
        .build();
  }

  private static RunRequirementContract copy(
      RunRequirementContract source, String artifactDigest, int requirementCount) {
    return RunRequirementContract.builder()
        .id(source.getId())
        .runId(source.getRunId())
        .schemaVersion(source.getSchemaVersion())
        .compilerFingerprint(source.getCompilerFingerprint())
        .sourceSha256(source.getSourceSha256())
        .exactPlanFingerprint(source.getExactPlanFingerprint())
        .canonicalArtifactJson(source.getCanonicalArtifactJson())
        .artifactDigest(artifactDigest)
        .boundDigest(source.getBoundDigest())
        .completeness(source.getCompleteness())
        .requirementCount(requirementCount)
        .acceptanceCriterionCount(source.getAcceptanceCriterionCount())
        .taskBindingCount(source.getTaskBindingCount())
        .issueCount(source.getIssueCount())
        .blockingIssueCount(source.getBlockingIssueCount())
        .build();
  }

  private static Plan plan(String requirementText) {
    return Plan.builder()
        .requirements(
            List.of(
                new RequirementSpec(
                    "R1", requirementText, RequirementSpec.RequirementKind.FUNCTIONAL)))
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Implement order lookup")
                    .description(requirementText)
                    .meetsRequirements(List.of("R1"))
                    .build()))
        .build();
  }
}
