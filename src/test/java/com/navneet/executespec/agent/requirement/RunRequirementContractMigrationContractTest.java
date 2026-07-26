package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RunRequirementContractMigrationContractTest {
  private static final Path MIGRATION =
      Path.of("src/main/resources/db/migration/V345__run_requirement_contract.sql");

  @Test
  void migrationIsAdditiveExactBytePreservingAndPlanGenerationScoped() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("CREATE TABLE run_requirement_contract")
        .contains("canonical_artifact_json     TEXT NOT NULL")
        .contains("FOREIGN KEY (run_id) REFERENCES pipeline_run(run_id) ON DELETE CASCADE")
        .contains("UNIQUE (run_id, exact_plan_fingerprint, schema_version, compiler_fingerprint)")
        .contains("CHECK (jsonb_typeof(canonical_artifact_json::jsonb) = 'object')")
        .contains("BEFORE UPDATE ON run_requirement_contract")
        .contains("('requirement.ir.shadow.enabled', 'false', 'BOOLEAN', 'PLATFORM'")
        .doesNotContain("ALTER TABLE pipeline_run")
        .doesNotContain("plan_requirements_json")
        .doesNotContain("meets_requirements_json");
  }

  @Test
  void migrationPinsAllHashesCompletenessAndNonNegativeCounts() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("compiler_fingerprint        VARCHAR(64) NOT NULL")
        .contains("source_sha256               VARCHAR(64) NOT NULL")
        .contains("exact_plan_fingerprint      VARCHAR(64) NOT NULL")
        .contains("artifact_digest             VARCHAR(64) NOT NULL")
        .contains("bound_digest                VARCHAR(64) NOT NULL")
        .contains("CHECK (completeness IN ('READY', 'BLOCKED'))")
        .contains("blocking_issue_count <= issue_count");
  }
}
