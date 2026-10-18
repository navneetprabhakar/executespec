package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Boots the migrated application and proves the frozen policy survives a database round trip. */
class BuildExecutionPolicyPostgresTest extends PostgresIntegrationBase {
  @Autowired BuildExecutionPolicyStore policies;
  @Autowired JdbcTemplate jdbc;

  @Test
  void immutablePolicySurvivesRestartReadAndRejectsDirectDatabaseReplacement() {
    String runId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version,plan_json)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2','{\"tasks\":[]}')",
        runId);
    var original = policies.freeze(runId, "Original design contract", true, true, "WORKHORSE");
    assertThat(policies.require(runId)).isEqualTo(original);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET build_execution_policy_json=NULL WHERE run_id=?",
                    runId))
        .rootCause()
        .hasMessageContaining("BUILD execution policy is immutable");
    assertThat(policies.require(runId)).isEqualTo(original);
  }
}
