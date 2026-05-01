package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Verifies that TenantContext is auto-populated via {@code @PrePersist}. */
@DisplayName("Tenant isolation (Integration)")
class TenantIsolationIntegrationTest extends PostgresIntegrationBase {

  @Autowired private StateStore stateStore;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  @DisplayName("PipelineRun created with explicit tenant carries that tenant id")
  void explicitTenantId() {
    TenantContext.setTenantId("acme-corp");

    PipelineRun run =
        stateStore.createRun(
            "Build API",
            "MANUAL",
            null,
            null,
            "claude-opus-4-6",
            "claude-sonnet-4-5",
            "claude-opus-4-6");

    PipelineRun found = stateStore.findRun(run.getRunId()).get();
    assertThat(found.getTenantId()).isEqualTo("acme-corp");
  }

  @Test
  @DisplayName("PipelineRun created without tenant context defaults to 'default'")
  void defaultTenantId() {
    TenantContext.clear(); // ensure no tenant set

    PipelineRun run =
        stateStore.createRun(
            "Fix bug",
            "MANUAL",
            null,
            null,
            "claude-opus-4-6",
            "claude-sonnet-4-5",
            "claude-opus-4-6");

    PipelineRun found = stateStore.findRun(run.getRunId()).get();
    assertThat(found.getTenantId()).isEqualTo("default");
  }

  @Test
  @DisplayName("runs from different tenants are created independently")
  void multiTenantCreation() {
    TenantContext.setTenantId("tenant-a");
    PipelineRun runA =
        stateStore.createRun(
            "Task A",
            "MANUAL",
            null,
            null,
            "claude-opus-4-6",
            "claude-sonnet-4-5",
            "claude-opus-4-6");

    TenantContext.setTenantId("tenant-b");
    PipelineRun runB =
        stateStore.createRun(
            "Task B",
            "MANUAL",
            null,
            null,
            "claude-opus-4-6",
            "claude-sonnet-4-5",
            "claude-opus-4-6");

    assertThat(stateStore.findRun(runA.getRunId()).get().getTenantId()).isEqualTo("tenant-a");
    assertThat(stateStore.findRun(runB.getRunId()).get().getTenantId()).isEqualTo("tenant-b");
    assertThat(runA.getRunId()).isNotEqualTo(runB.getRunId());
  }
}
