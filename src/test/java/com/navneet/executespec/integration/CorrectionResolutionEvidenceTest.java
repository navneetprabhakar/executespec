package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.jupiter.api.Test;

class CorrectionResolutionEvidenceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void serializesBoundedPatchMetadataAndTypedValidationDelta() {
    ArrayNode actions = objectMapper.createArrayNode();
    var before =
        snapshot("COMPLETE", "a".repeat(64), true, 8, 8, "PASSED", "CLEAN", "COMPATIBLE", 70);
    var after =
        snapshot("COMPLETE", "b".repeat(64), true, 10, 10, "PASSED", "CLEAN", "COMPATIBLE", 92);

    CorrectionResolutionEvidence.append(
        objectMapper,
        actions,
        "ACCEPTED",
        "CORRECTION_ACCEPTED",
        "accepted",
        "11111111-1111-1111-1111-111111111111",
        "c".repeat(64),
        before.workspaceGeneration(),
        after.workspaceGeneration(),
        new CorrectionWorkspaceTransaction.DiffEvidence(
            List.of("0:src/App.java"),
            "d".repeat(64),
            "--- a/src/App.java\n",
            22,
            41L,
            true,
            false),
        CorrectionResolutionEvidence.delta(before, after),
        true);

    var resolution = actions.get(0);
    assertThat(resolution.path("approvalId").asText())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(resolution.path("proposalDigest").asText()).isEqualTo("c".repeat(64));
    assertThat(resolution.path("unifiedPatch").asText()).contains("src/App.java");
    assertThat(resolution.path("patchMetadata").path("fullBytes").asLong()).isEqualTo(41L);
    assertThat(resolution.path("patchMetadata").path("truncated").asBoolean()).isTrue();
    assertThat(resolution.path("validationDelta").path("verifiedScoreDelta").asInt()).isEqualTo(22);
    assertThat(
            resolution
                .path("validationDelta")
                .path("after")
                .path("contracts")
                .path("status")
                .asText())
        .isEqualTo("COMPATIBLE");
    assertThat(resolution.path("validationRegenerated").asBoolean()).isTrue();
  }

  @Test
  void rejectedAttemptPersistsAllGateTypesWithoutClaimingRegenerationOrScore() {
    ArrayNode actions = objectMapper.createArrayNode();
    var before =
        snapshot("COMPLETE", "a".repeat(64), true, 8, 8, "PASSED", "CLEAN", "COMPATIBLE", 85);
    var attempt =
        new CorrectionResolutionEvidence.ValidationSnapshot(
            "CORRECTION_ATTEMPT",
            "b".repeat(64),
            new CorrectionResolutionEvidence.BuildValidation("FAILED", false, 1),
            new CorrectionResolutionEvidence.TestValidation(
                "FAILED", 7, 8, 0, true, "WORKSPACE", 1),
            new CorrectionResolutionEvidence.WorkflowValidation("FAILED", 1, "invalid yaml"),
            new CorrectionResolutionEvidence.ReviewerValidation("UNRESOLVED_ISSUES"),
            new CorrectionResolutionEvidence.ContractValidation(
                "BREAKING", "base", "current", 1, 0),
            new CorrectionResolutionEvidence.ScoreValidation(null, null),
            "PARTIAL_WITH_WARNINGS",
            "NOT_REGENERATED",
            null);

    CorrectionResolutionEvidence.append(
        objectMapper,
        actions,
        "ROLLED_BACK",
        "CORRECTION_FINAL_TESTS_FAILED",
        "rejected",
        before.workspaceGeneration(),
        before.workspaceGeneration(),
        null,
        CorrectionResolutionEvidence.delta(before, attempt),
        true);

    var resolution = actions.get(0);
    var post = resolution.path("validationDelta").path("after");
    assertThat(post.path("build").path("verdict").asText()).isEqualTo("FAILED");
    assertThat(post.path("tests").path("passed").asInt()).isEqualTo(7);
    assertThat(post.path("workflow").path("status").asText()).isEqualTo("FAILED");
    assertThat(post.path("reviewer").path("verdict").asText()).isEqualTo("UNRESOLVED_ISSUES");
    assertThat(post.path("contracts").path("missingCount").asInt()).isEqualTo(1);
    assertThat(post.path("score").path("verifiedScore").isNull()).isTrue();
    assertThat(resolution.path("validationRegenerated").asBoolean()).isFalse();
  }

  private static CorrectionResolutionEvidence.ValidationSnapshot snapshot(
      String status,
      String generation,
      boolean build,
      int passed,
      int total,
      String workflow,
      String reviewer,
      String contracts,
      int score) {
    return new CorrectionResolutionEvidence.ValidationSnapshot(
        status,
        generation,
        new CorrectionResolutionEvidence.BuildValidation(build ? "PASSED" : "FAILED", build, 0),
        new CorrectionResolutionEvidence.TestValidation(
            passed == total ? "PASSED" : "FAILED", passed, total, 0, true, "WORKSPACE", 0),
        new CorrectionResolutionEvidence.WorkflowValidation(workflow, 1, null),
        new CorrectionResolutionEvidence.ReviewerValidation(reviewer),
        new CorrectionResolutionEvidence.ContractValidation(contracts, "base", "current", 0, 0),
        new CorrectionResolutionEvidence.ScoreValidation(score, score),
        "PASSED",
        "PASSED",
        null);
  }
}
