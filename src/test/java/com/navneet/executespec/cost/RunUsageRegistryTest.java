package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RunUsageRegistryTest {
  @Test
  void finalizationAndLateCallsKeepOneRunAndTaskSpendPopulation() {
    var registry = new RunUsageRegistry<AtomicLong>();
    var run = registry.recordRun("run", AtomicLong::new);
    run.addAndGet(100);
    var task = registry.recordTask("run", "T1", AtomicLong::new);
    task.addAndGet(100);
    assertThat(registry.retire("run").get()).isEqualTo(100);
    registry.recordRun("run", AtomicLong::new).addAndGet(30);
    registry.recordTask("run", "T1", AtomicLong::new).addAndGet(30);
    assertThat(registry.retire("run").get()).isEqualTo(130);
    assertThat(registry.task("run", "T1").get()).isEqualTo(130);
  }

  @Test
  void cleanupRemovesOnlyRetiredInactivePopulations() {
    var registry = new RunUsageRegistry<AtomicLong>();
    var active = registry.recordRun("active", AtomicLong::new);
    registry.recordRun("terminal", AtomicLong::new);
    registry.recordTask("terminal", "T1", AtomicLong::new);
    registry.retire("terminal");
    registry.cleanBefore(Instant.now().plusSeconds(1));
    assertThat(registry.run("active")).isSameAs(active);
    assertThat(registry.run("terminal")).isNull();
    assertThat(registry.task("terminal", "T1")).isNull();
  }
}
