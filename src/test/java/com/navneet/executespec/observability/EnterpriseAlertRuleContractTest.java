package com.navneet.executespec.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class EnterpriseAlertRuleContractTest {

  @Test
  void canonicalUsagePersistenceFailureHasRoutableAlertAndRunbook() throws Exception {
    String prometheus = Files.readString(Path.of("observability/prometheus.yml"));
    String rules =
        Files.readString(Path.of("observability/alerts/executespec-enterprise-alerts.yml"));

    assertThat(prometheus).contains("/etc/prometheus/rules/*.yml");
    assertThat(rules)
        .contains("CanonicalLlmUsagePersistenceFailure")
        .contains("CanonicalLlmUsageTerminalMissing")
        .contains("CanonicalLlmUsageRecoveryBacklog")
        .contains("CanonicalLlmUsageRecoveryDeadLetter")
        .contains("executespec_v2_llm_usage_persistence_total{result=\"failed\"}")
        .contains("executespec_v2_llm_usage_stale_intents > 0")
        .contains("executespec_v2_llm_usage_recovery_pending > 0")
        .contains("executespec_v2_llm_usage_recovery_dead > 0")
        .contains("severity: critical")
        .contains("owner: platform")
        .contains("runbook_url:")
        .contains("test_procedure:")
        .contains("silence_policy:");
  }
}
