package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BlockedRequirementGateMigrationContractTest {
  @Test
  void v352AllowsOnlyExactBoundOrBlockedEnforcedEvidenceAndKeepsApprovalBoundOnly()
      throws Exception {
    String sql =
        Files.readString(
                Path.of(
                    "src/main/resources/db/migration/"
                        + "V352__allow_visible_blocked_requirement_gate.sql"))
            .replaceAll("\\s+", " ")
            .trim();

    assertThat(sql)
        .contains("CREATE OR REPLACE FUNCTION enforce_plan_gate_admission_receipt()")
        .contains("ENFORCED plan gate requires an exact RequirementIR")
        .contains("NEW.binding_status = 'BOUND'")
        .contains("requirement_completeness IS DISTINCT FROM 'READY'")
        .contains("requirement_blockers IS DISTINCT FROM 0")
        .contains("NEW.binding_status = 'BLOCKED'")
        .contains("requirement_completeness = 'BLOCKED' AND requirement_blockers > 0")
        .contains("NEW.binding_status NOT IN ('BOUND', 'BLOCKED')")
        .contains("NEW.status = 'APPROVED'")
        .contains("NEW.binding_status IS DISTINCT FROM 'BOUND'")
        .contains("ENFORCED plan approval requires a complete BOUND admission binding");
  }
}
