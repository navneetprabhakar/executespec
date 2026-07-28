package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunOrchestratorIterationPolicyTest {

  @Test
  void exactRouteIterationPreservesOnlyExplicitModelPin() {
    PipelineRun pinned =
        PipelineRun.builder().routePinned(true).requestedModel("claude-sonnet-4-6").build();
    PipelineRun providerPreferred =
        PipelineRun.builder().routePinned(false).requestedModel("stale-derived-model").build();

    assertThat(RunOrchestrator.iterationPinnedModel(pinned)).isEqualTo("claude-sonnet-4-6");
    assertThat(RunOrchestrator.iterationPinnedModel(providerPreferred)).isNull();
  }

  @Test
  void iterationExecutionModeFailsClosedToProduction() {
    assertThat(RunOrchestrator.normalizeIterationExecutionMode("PROTOTYPE")).isEqualTo("PROTOTYPE");
    assertThat(RunOrchestrator.normalizeIterationExecutionMode("REALTIME")).isEqualTo("PRODUCTION");
    assertThat(RunOrchestrator.normalizeIterationExecutionMode(null)).isEqualTo("PRODUCTION");
  }

  @Test
  void iterationPolicyPreservesRouteRepositoryAndExecutionControls() {
    UUID repoId = UUID.randomUUID();
    PipelineRun source =
        PipelineRun.builder()
            .requestedProvider("ANTHROPIC")
            .routePinned(true)
            .requestedModel("claude-sonnet-4-6")
            .projectId("project-1")
            .selectedRepoIds("[\"" + repoId + "\"]")
            .executionMode("PROTOTYPE")
            .tddMode(true)
            .userCostCap(4.5)
            .skipAutoPush(true)
            .orgId("host-org")
            .billingOrgId("payer-org")
            .build();

    RunOrchestrator.IterationPolicy policy = RunOrchestrator.iterationPolicy(source);

    assertThat(policy.provider()).isEqualTo("ANTHROPIC");
    assertThat(policy.pinnedModel()).isEqualTo("claude-sonnet-4-6");
    assertThat(policy.projectId()).isEqualTo("project-1");
    assertThat(policy.selectedRepoIds()).containsExactly(repoId);
    assertThat(policy.executionMode()).isEqualTo("PROTOTYPE");
    assertThat(policy.tddMode()).isTrue();
    assertThat(policy.userCostCap()).isEqualTo(4.5);
    assertThat(policy.skipAutoPush()).isTrue();
    assertThat(policy.tenantOrgId()).isEqualTo("host-org");

    RunOrchestrator.SubmitPolicy submitPolicy =
        IterationSubmitPolicyFactory.from(policy, "current-writer-org");
    assertThat(submitPolicy.tenantOrgId()).isEqualTo("host-org");
    assertThat(submitPolicy.billingOrgId()).isEqualTo("current-writer-org");
    assertThat(submitPolicy.billingOrgId()).isNotEqualTo(source.getBillingOrgId());
  }
}
