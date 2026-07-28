package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;

import org.junit.jupiter.api.Test;

class RequirementIrV1ApprovalValidatorTest {
  private static final String SOURCE = "Implement the order lookup.";

  private final RequirementIrCompiler compiler = new RequirementIrCompiler();
  private final RequirementIrCodec domainCodec = new RequirementIrCodec(new ObjectMapper());
  private final RequirementIrV1WireCodec wireCodec =
      new RequirementIrV1WireCodec(new ObjectMapper());

  @Test
  void frozenV1DecisionMatchesTheOriginalV1RulesForReadyAndBlockedCompilerArtifacts() {
    RequirementIrDocument ready =
        compiler.compile(
            SOURCE,
            Plan.builder()
                .requirements(
                    List.of(
                        new RequirementSpec(
                            "R1", SOURCE, RequirementSpec.RequirementKind.FUNCTIONAL)))
                .tasks(
                    List.of(
                        PlannedTask.builder()
                            .id("T1")
                            .title("Implement order lookup")
                            .description(SOURCE)
                            .meetsRequirements(List.of("R1"))
                            .build()))
                .build());
    RequirementIrDocument blocked =
        compiler.compile(SOURCE, Plan.builder().requirements(List.of()).tasks(List.of()).build());

    for (RequirementIrDocument document : List.of(ready, blocked)) {
      RequirementIrV1WireDocument wire = wireCodec.read(domainCodec.writeCanonical(document));

      assertThat(RequirementIrV1ApprovalValidator.validate(wire, SOURCE).approvable())
          .isEqualTo(RequirementIrValidator.validateForApproval(document, SOURCE).approvable());
    }
  }

  @Test
  void frozenV1ValidatorRejectsBrokenReciprocalTaskBinding() {
    RequirementIrV1WireDocument.SourceSpan span =
        new RequirementIrV1WireDocument.SourceSpan(
            0, SOURCE.length(), 1, 1, 1, SOURCE.length() + 1);
    RequirementIrV1WireDocument wire =
        new RequirementIrV1WireDocument(
            RequirementIrV1WireDocument.SCHEMA_VERSION,
            RequirementIrV1WireDocument.COMPILER_FINGERPRINT,
            RequirementIrV1ApprovalValidator.sha256(SOURCE),
            List.of(
                new RequirementIrV1WireDocument.RequirementNode(
                    "R1",
                    SOURCE,
                    RequirementIrV1WireDocument.RequirementKind.FUNCTIONAL,
                    RequirementIrV1WireDocument.Origin.USER_STATED,
                    RequirementIrV1WireDocument.RequirementState.EXPLICIT,
                    span,
                    null,
                    null,
                    List.of(),
                    List.of("T1"))),
            List.of(),
            List.of(new RequirementIrV1WireDocument.TaskBinding("T1", List.of())),
            List.of(),
            RequirementIrV1WireDocument.Completeness.READY);

    var decision = RequirementIrV1ApprovalValidator.validate(wire, SOURCE);

    assertThat(decision.approvable()).isFalse();
    assertThat(decision.blockingCodes()).contains("OWNERLESS_TASK");
    assertThat(decision.violations()).contains("NON_RECIPROCAL_TASK_BINDING");
  }

  @Test
  void retainedV1RuntimeHasNoDependencyOnRollingDomainDocumentOrValidator() throws Exception {
    String wireSource =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/agent/requirement/"
                    + "RequirementIrV1WireDocument.java"));
    String validatorSource =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/agent/requirement/"
                    + "RequirementIrV1ApprovalValidator.java"));
    String registrySource =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/agent/requirement/"
                    + "RequirementIrApprovalCompatibilityRegistry.java"));

    assertThat(wireSource).doesNotContain("RequirementIrDocument");
    assertThat(validatorSource).doesNotContain("RequirementIrDocument", "RequirementIrValidator");
    assertThat(registrySource).doesNotContain("RequirementIrDocument", "RequirementIrValidator");
  }
}
