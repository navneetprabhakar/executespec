package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;

import org.junit.jupiter.api.Test;

class WorkerCorrectionToolFenceTest {

  @Test
  void propagatesExactCorrectionLeaseIntoParallelDispatchAndRestoresCallerContext()
      throws Exception {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    AtomicReference<RunWorkspaceAuthorityService.Lease> observed = new AtomicReference<>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      RunWorkspaceAuthorityService.withCorrectionToolLease(
          lease,
          () -> {
            try {
              WorkerCorrectionToolFence.submitCurrent(
                      executor,
                      () -> observed.set(RunWorkspaceAuthorityService.currentCorrectionToolLease()))
                  .get();
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
            return null;
          });
    }

    assertThat(observed.get()).isSameAs(lease);
    assertThat(RunWorkspaceAuthorityService.currentCorrectionToolLease()).isNull();
  }

  @Test
  void dispatchFenceRechecksOwnershipAndRejectsCrossRunUse() {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-1");
    ExecutionContext matching = new ExecutionContext("run-1", "T1", Path.of("/tmp/run-1"));
    ExecutionContext other = new ExecutionContext("run-2", "T1", Path.of("/tmp/run-2"));

    RunWorkspaceAuthorityService.withCorrectionToolLease(
        lease,
        () -> {
          WorkerCorrectionToolFence.assertDispatch(matching);
          assertThatThrownBy(() -> WorkerCorrectionToolFence.assertDispatch(other))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("CORRECTION_TOOL_LEASE_RUN_MISMATCH");
          return null;
        });

    verify(lease).assertOwned();
  }

  @Test
  void explicitBuildCapabilityFencesParallelDispatchWithoutThreadLocalPropagation()
      throws Exception {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-build");
    WorkspaceGenerationLifecycle lifecycle = mock(WorkspaceGenerationLifecycle.class);
    WorkspaceGenerationLifecycle.Prepared prepared =
        new WorkspaceGenerationLifecycle.Prepared(java.util.UUID.randomUUID(), null, lease);
    WorkspaceGenerationDispatchAuthority authority =
        new WorkspaceGenerationDispatchAuthority(
            "run-build",
            "T1",
            prepared,
            WorkspaceGenerationDispatchAuthority.Phase.MUTATION,
            lifecycle);
    ExecutionContext matching =
        new ExecutionContext("run-build", "T1", Path.of("/tmp/run-build"))
            .withWorkspaceGenerationAuthority(authority);
    WorkerTaskRequest request =
        WorkerTaskRequest.builder()
            .provider("anthropic")
            .model("sonnet")
            .userPrompt("fix")
            .context(matching)
            .changeMode(CodeChangeMode.BUILD)
            .build();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      WorkerCorrectionToolFence.submitCurrent(
              executor, () -> WorkerCorrectionToolFence.assertAttemptAdmission(request))
          .get();
    }

    verify(lease).assertOwned();
    verify(lifecycle).assertMutationDispatch(prepared);
  }

  @Test
  void buildAttemptAdmissionFailsBeforeProviderOrToolWorkWithoutGenerationFence() {
    WorkerTaskRequest request =
        WorkerTaskRequest.builder()
            .provider("anthropic")
            .model("sonnet")
            .userPrompt("fix")
            .context(new ExecutionContext("run-build", "T1", Path.of("/tmp/run-build")))
            .changeMode(CodeChangeMode.BUILD)
            .build();

    assertThatThrownBy(() -> WorkerCorrectionToolFence.assertAttemptAdmission(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_GENERATION_AUTHORITY_REQUIRED");
  }
}
