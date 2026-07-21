package com.navneet.executespec.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-1590 — the single classifier that both {@code LlmExecutionPolicyFactory} (no-failover) and
 * {@code CodeGenEngineImpl} (test-gate off) consult so the two controlled-worker behaviours cannot
 * drift. Terminal-Bench was previously excluded because each site carried its own literal
 * benchmark/swebench prefix test.
 */
class BenchmarkSourceTypeTest {

  @Test
  void classifiesEveryBenchmarkAdapterSourceTypeAsControlled() {
    // Actually written today.
    assertThat(BenchmarkSourceType.isControlledWorkerSource("benchmark")).isTrue();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("TERMINAL_BENCH")).isTrue();
    // Prefix + case tolerance and defensive/future spellings.
    assertThat(BenchmarkSourceType.isControlledWorkerSource("benchmark-pro")).isTrue();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("swebench")).isTrue();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("SWEBENCH-LITE")).isTrue();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("terminal_bench")).isTrue();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("terminalbench")).isTrue();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("programbench")).isTrue();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("program_bench")).isTrue();
  }

  @Test
  void doesNotClassifyCustomerOrProductSourceTypesAsControlled() {
    assertThat(BenchmarkSourceType.isControlledWorkerSource("web")).isFalse();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("MANUAL")).isFalse();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("AGENT_TURN")).isFalse();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("finding:CI_FAILURE")).isFalse();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("jira")).isFalse();
    assertThat(BenchmarkSourceType.isControlledWorkerSource(null)).isFalse();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("")).isFalse();
    assertThat(BenchmarkSourceType.isControlledWorkerSource("   ")).isFalse();
  }
}
