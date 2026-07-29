package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.requirement.RequirementIrApprovalCompatibilityRegistry.IncompatibleRequirementIrException;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;

import org.junit.jupiter.api.Test;

class RequirementIrApprovalCompatibilityRegistryTest {
  private static final String SOURCE = "Implement the order lookup.";
  private static final String PLAN_JSON = "{\"plan\":\"v1\"}";

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final RequirementIrCompiler compiler = new RequirementIrCompiler();
  private final RequirementIrCodec currentCodec = new RequirementIrCodec(objectMapper);
  private final RequirementIrApprovalCompatibilityRegistry registry =
      new RequirementIrApprovalCompatibilityRegistry(objectMapper);

  @Test
  void retainedV1WireArtifactRemainsApprovalCompatible() {
    RunRequirementContract contract = contract(currentCodec.writeCanonical(document()));

    assertThatNoException().isThrownBy(() -> registry.verifyApprovalCompatible(contract, SOURCE));
  }

  @Test
  void frozenProjectionAcceptsBlockedCanonicalEvidenceWithoutMakingItApprovalCompatible() {
    String blockedSource = "Handle the UI error appropriately.";
    RequirementIrDocument blockedDocument =
        compiler.compile(
            blockedSource,
            Plan.builder()
                .requirements(
                    List.of(
                        new RequirementSpec(
                            "R1", blockedSource, RequirementSpec.RequirementKind.FUNCTIONAL)))
                .tasks(List.of())
                .build());
    RunRequirementContract blocked =
        contract(blockedDocument, currentCodec.writeCanonical(blockedDocument));

    var projection = registry.projectFrozenVersion(blocked, blockedSource);

    assertThat(projection.completeness()).isEqualTo("BLOCKED");
    assertThat(projection.approvalReady()).isFalse();
    assertThat(projection.requirements()).extracting(r -> r.kind()).containsExactly("ERROR");
    assertThat(projection.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.code()).isEqualTo("MISSING_TASK");
              assertThat(issue.blocking()).isTrue();
              assertThat(issue.message()).isNotBlank();
              assertThat(issue.requirementIds()).contains("R1");
            });
    assertThatThrownBy(() -> registry.verifyApprovalCompatible(blocked, blockedSource))
        .isInstanceOf(IncompatibleRequirementIrException.class)
        .hasMessageContaining("not approval compatible");
  }

  @Test
  void retainedV1WireShapeRejectsAnUnregisteredFutureFieldEvenWithReboundDigests() {
    String canonical = currentCodec.writeCanonical(document());
    String changed = canonical.substring(0, canonical.length() - 1) + ",\"futureField\":true}";
    RunRequirementContract contract = contract(changed);

    assertThatThrownBy(() -> registry.verifyApprovalCompatible(contract, SOURCE))
        .isInstanceOf(IncompatibleRequirementIrException.class)
        .hasMessageContaining("cannot be decoded");
  }

  @Test
  void frozenProjectionExposesDerivedBlockersMissingFromPersistedIssueLedger() {
    RequirementIrDocument incompleteLedger =
        new RequirementIrDocument(
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            RequirementIrCompiler.COMPILER_FINGERPRINT,
            RequirementIrValidator.sha256(SOURCE),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            RequirementIrDocument.Completeness.BLOCKED);
    String canonical = currentCodec.writeCanonical(incompleteLedger);

    var projection = registry.projectFrozenVersion(contract(incompleteLedger, canonical), SOURCE);

    assertThat(projection.approvalReady()).isFalse();
    assertThat(projection.persistedIssueCount()).isZero();
    assertThat(projection.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.code()).isEqualTo("UNCOVERED_SOURCE_CLAUSE");
              assertThat(issue.blocking()).isTrue();
              assertThat(issue.message()).contains("frozen validation");
              assertThat(issue.requirementIds()).isEmpty();
            });
  }

  @Test
  void retainedV1WireShapeRejectsNestedUnknownFieldsAndTrailingDocuments() {
    String canonical = currentCodec.writeCanonical(document());
    String nestedUnknown =
        canonical.replaceFirst(
            "\\{\\\"acceptanceIds\\\"", "{\\\"futureNodeField\\\":true,\\\"acceptanceIds\\\"");

    assertThatThrownBy(() -> registry.verifyApprovalCompatible(contract(nestedUnknown), SOURCE))
        .isInstanceOf(IncompatibleRequirementIrException.class)
        .hasMessageContaining("cannot be decoded");
    assertThatThrownBy(() -> registry.verifyApprovalCompatible(contract(canonical + "{}"), SOURCE))
        .isInstanceOf(IncompatibleRequirementIrException.class)
        .hasMessageContaining("cannot be decoded");
  }

  @Test
  void retainedV1WireCodecIgnoresRollingApplicationMapperConfiguration() {
    String canonical = currentCodec.writeCanonical(document());
    ObjectMapper rollingMapper =
        new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    var registryWithChangedApplicationMapper =
        new RequirementIrApprovalCompatibilityRegistry(rollingMapper);

    assertThatNoException()
        .isThrownBy(
            () ->
                registryWithChangedApplicationMapper.verifyApprovalCompatible(
                    contract(canonical), SOURCE));
  }

  @Test
  void retainedV1WireIdentityRejectsArtifactVersionDriftEvenWhenDigestsAreRebound() {
    String canonical = currentCodec.writeCanonical(document());
    String changedSchema = canonical.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
    String changedCompiler =
        canonical.replace(RequirementIrV1WireDocument.COMPILER_FINGERPRINT, "f".repeat(64));

    assertThatThrownBy(() -> registry.verifyApprovalCompatible(contract(changedSchema), SOURCE))
        .isInstanceOf(IncompatibleRequirementIrException.class)
        .hasMessageContaining("cannot be decoded");
    assertThatThrownBy(() -> registry.verifyApprovalCompatible(contract(changedCompiler), SOURCE))
        .isInstanceOf(IncompatibleRequirementIrException.class)
        .hasMessageContaining("cannot be decoded");
  }

  @Test
  void missingContractNeverPassesApprovalCompatibility() {
    assertThatThrownBy(() -> registry.verifyApprovalCompatible(null, SOURCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contract is required");
  }

  @Test
  void retainedV1CanonicalBytesArePinnedIndependentlyFromTheDomainCodec() {
    RequirementIrV1WireDocument wire =
        new RequirementIrV1WireDocument(
            RequirementIrV1WireDocument.SCHEMA_VERSION,
            RequirementIrV1WireDocument.COMPILER_FINGERPRINT,
            "0".repeat(64),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            RequirementIrV1WireDocument.Completeness.BLOCKED);
    String expected =
        "{\"acceptanceCriteria\":[],\"compilerFingerprint\":\""
            + RequirementIrV1WireDocument.COMPILER_FINGERPRINT
            + "\",\"completeness\":\"BLOCKED\",\"issues\":[],\"requirements\":[],"
            + "\"schemaVersion\":1,\"sourceSha256\":\""
            + "0".repeat(64)
            + "\",\"taskBindings\":[]}";

    RequirementIrV1WireCodec codec = new RequirementIrV1WireCodec(new ObjectMapper());

    assertThat(codec.writeCanonical(wire)).isEqualTo(expected);
    assertThat(codec.writeCanonical(codec.read(expected))).isEqualTo(expected);
  }

  private RequirementIrDocument document() {
    return compiler.compile(
        SOURCE,
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec("R1", SOURCE, RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Implement order lookup")
                        .description(SOURCE)
                        .meetsRequirements(List.of("R1"))
                        .build()))
            .build());
  }

  private RunRequirementContract contract(String canonical) {
    return contract(document(), canonical);
  }

  private RunRequirementContract contract(RequirementIrDocument document, String canonical) {
    String artifactDigest = RequirementContractDigests.artifactDigest(canonical);
    String planFingerprint = currentCodec.exactPlanFingerprint(PLAN_JSON);
    long blocking = document.issues().stream().filter(issue -> issue.blocking()).count();
    return RunRequirementContract.builder()
        .id(UUID.randomUUID())
        .runId("run-v1")
        .schemaVersion(document.schemaVersion())
        .compilerFingerprint(document.compilerFingerprint())
        .sourceSha256(document.sourceSha256())
        .exactPlanFingerprint(planFingerprint)
        .canonicalArtifactJson(canonical)
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
}
