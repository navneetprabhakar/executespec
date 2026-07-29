package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;

import jakarta.persistence.Table;

import com.navneet.executespec.agent.requirement.RequirementIrDocument.Completeness;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

class RunRequirementContractEntityTest {

  @Test
  void entityIsImmutableAndCarriesTheExactArtifactIdentity() {
    Instant compiledAt = Instant.parse("2026-07-27T00:00:00Z");
    RunRequirementContract contract =
        RunRequirementContract.builder()
            .runId("run-1")
            .schemaVersion(1)
            .compilerFingerprint("a".repeat(64))
            .sourceSha256("b".repeat(64))
            .exactPlanFingerprint("c".repeat(64))
            .canonicalArtifactJson("{\"schemaVersion\":1}")
            .artifactDigest("d".repeat(64))
            .boundDigest("e".repeat(64))
            .completeness(Completeness.BLOCKED)
            .requirementCount(3)
            .acceptanceCriterionCount(2)
            .taskBindingCount(2)
            .issueCount(1)
            .blockingIssueCount(1)
            .compiledAt(compiledAt)
            .build();

    assertThat(RunRequirementContract.class).hasAnnotation(Immutable.class);
    assertThat(RunRequirementContract.class.getAnnotation(Table.class).name())
        .isEqualTo("run_requirement_contract");
    assertThat(RunRequirementContract.class.getMethods())
        .filteredOn(method -> method.getName().startsWith("set"))
        .extracting(Method::getName)
        .isEmpty();
    assertThat(contract.getRunId()).isEqualTo("run-1");
    assertThat(contract.getExactPlanFingerprint()).isEqualTo("c".repeat(64));
    assertThat(contract.getCanonicalArtifactJson()).isEqualTo("{\"schemaVersion\":1}");
    assertThat(contract.getCompleteness()).isEqualTo(Completeness.BLOCKED);
    assertThat(contract.getBlockingIssueCount()).isEqualTo(1);
    assertThat(contract.getCompiledAt()).isEqualTo(compiledAt);
  }

  @Test
  void prePersistRejectsArtifactAndBindingDigestMismatch() throws Exception {
    String canonicalArtifact = "{\"schemaVersion\":1}";
    String artifactDigest = RequirementContractDigests.artifactDigest(canonicalArtifact);
    Method validation = RunRequirementContract.class.getDeclaredMethod("validateDigestBinding");
    validation.setAccessible(true);

    RunRequirementContract badArtifact =
        contractWithDigests(canonicalArtifact, "d".repeat(64), "e".repeat(64));
    assertThatThrownBy(() -> validation.invoke(badArtifact))
        .hasRootCauseMessage("RequirementIR artifact digest does not match canonical bytes");

    RunRequirementContract badBinding =
        contractWithDigests(canonicalArtifact, artifactDigest, "e".repeat(64));
    assertThatThrownBy(() -> validation.invoke(badBinding))
        .hasRootCauseMessage("RequirementIR binding digest does not match stored hashes");
  }

  private static RunRequirementContract contractWithDigests(
      String canonicalArtifact, String artifactDigest, String boundDigest) {
    return RunRequirementContract.builder()
        .runId("run-invalid")
        .schemaVersion(1)
        .compilerFingerprint("a".repeat(64))
        .sourceSha256("b".repeat(64))
        .exactPlanFingerprint("c".repeat(64))
        .canonicalArtifactJson(canonicalArtifact)
        .artifactDigest(artifactDigest)
        .boundDigest(boundDigest)
        .completeness(Completeness.BLOCKED)
        .requirementCount(1)
        .acceptanceCriterionCount(1)
        .taskBindingCount(1)
        .issueCount(1)
        .blockingIssueCount(1)
        .build();
  }
}
