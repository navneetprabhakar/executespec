package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationDispatchAuthorityTest {

  @Test
  void exactCapabilityRechecksLeaseAndDurableStateEveryDispatch() {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-1");
    WorkspaceGenerationLifecycle lifecycle = mock(WorkspaceGenerationLifecycle.class);
    Fixture fixture =
        authority(lease, WorkspaceGenerationDispatchAuthority.Phase.MUTATION, lifecycle);
    ExecutionContext context =
        new ExecutionContext("run-1", "T1", Path.of("/tmp/run-1"))
            .withWorkspaceGenerationAuthority(fixture.authority());

    context.assertWorkspaceGenerationDispatch(WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
    context.assertWorkspaceGenerationDispatch(WorkspaceGenerationDispatchAuthority.Phase.MUTATION);

    verify(lease, org.mockito.Mockito.times(2)).assertOwned();
    verify(lifecycle, org.mockito.Mockito.times(2)).assertMutationDispatch(fixture.prepared());
  }

  @Test
  void refusesCrossTaskAndCrossPhaseReuse() {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-1");
    WorkspaceGenerationDispatchAuthority authority =
        authority(
                lease,
                WorkspaceGenerationDispatchAuthority.Phase.MUTATION,
                mock(WorkspaceGenerationLifecycle.class))
            .authority();

    assertThatThrownBy(
            () ->
                authority.assertDispatch(
                    "run-1", "T2", WorkspaceGenerationDispatchAuthority.Phase.MUTATION))
        .hasMessage("WORKSPACE_GENERATION_AUTHORITY_TASK_MISMATCH");
    assertThatThrownBy(
            () ->
                authority.assertDispatch(
                    "run-1", "T1", WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION))
        .hasMessage("WORKSPACE_GENERATION_AUTHORITY_PHASE_MISMATCH");
  }

  @Test
  void executionContextSerializationAndTextNeverExposeCapability() throws Exception {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-1");
    ExecutionContext context =
        new ExecutionContext("run-1", "T1", Path.of("/tmp/run-1"))
            .withWorkspaceGenerationAuthority(
                authority(
                        lease,
                        WorkspaceGenerationDispatchAuthority.Phase.MUTATION,
                        mock(WorkspaceGenerationLifecycle.class))
                    .authority());

    assertThat(new ObjectMapper().writeValueAsString(context))
        .doesNotContain("workspaceGenerationDispatchAuthority", "REDACTED");
    assertThat(context.toString()).doesNotContain("workspaceGeneration", "REDACTED");
    assertThat(
            java.io.Serializable.class.isAssignableFrom(
                context.workspaceGenerationDispatchAuthority().getClass()))
        .isFalse();
  }

  private static Fixture authority(
      RunWorkspaceAuthorityService.Lease lease,
      WorkspaceGenerationDispatchAuthority.Phase phase,
      WorkspaceGenerationLifecycle lifecycle) {
    WorkspaceGenerationLifecycle.Prepared prepared =
        new WorkspaceGenerationLifecycle.Prepared(java.util.UUID.randomUUID(), null, lease);
    return new Fixture(
        prepared,
        new WorkspaceGenerationDispatchAuthority("run-1", "T1", prepared, phase, lifecycle));
  }

  private record Fixture(
      WorkspaceGenerationLifecycle.Prepared prepared,
      WorkspaceGenerationDispatchAuthority authority) {}
}
