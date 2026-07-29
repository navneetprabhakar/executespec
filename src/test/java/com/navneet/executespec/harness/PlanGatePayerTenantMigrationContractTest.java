package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PlanGatePayerTenantMigrationContractTest {

  @Test
  void v349RemovesOnlyTheAccidentalPayerEqualsTenantCoherenceClause() throws Exception {
    String sql =
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/"
                    + "V349__separate_plan_gate_payer_from_tenant.sql"));

    assertThat(sql)
        .contains("enforce_plan_gate_decision_coherence")
        .contains("OR gate_billing_org IS DISTINCT FROM run_org_id")
        .contains("occurrence_count IS DISTINCT FROM 1")
        .contains("(gate_evidence ->> ''billingOrgId'') IS DISTINCT FROM gate_billing_org")
        .contains("gate_billing_owner IS DISTINCT FROM run_owner_user_id")
        .contains("definition := replace(definition, forbidden_clause, '')")
        .contains("EXECUTE definition")
        .contains("position(forbidden_clause IN definition) <> 0")
        .contains("gate_record.billing_org_id");
  }
}
