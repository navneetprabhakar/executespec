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
  void propagatesAndFencesExactBuildGenerationLease() throws Exception {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-build");
    AtomicReference<RunWorkspaceAuthorityService.Lease> observed = new AtomicReference<>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      RunWorkspaceAuthorityService.withWorkspaceGenerationToolLease(
          lease,
          () -> {
            try {
              WorkerCorrectionToolFence.submitCurrent(
                      executor,
                      () ->
                          observed.set(
                              RunWorkspaceAuthorityService.currentWorkspaceGenerationToolLease()))
                  .get();
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
            return null;
          });
    }

    assertThat(observed.get()).isSameAs(lease);
    assertThat(RunWorkspaceAuthorityService.currentWorkspaceGenerationToolLease()).isNull();
    ExecutionContext matching = new ExecutionContext("run-build", "T1", Path.of("/tmp/run-build"));
    ExecutionContext other = new ExecutionContext("run-other", "T1", Path.of("/tmp/run-other"));
    RunWorkspaceAuthorityService.withWorkspaceGenerationToolLease(
        lease,
        () -> {
          WorkerCorrectionToolFence.assertDispatch(matching);
          assertThatThrownBy(() -> WorkerCorrectionToolFence.assertDispatch(other))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("WORKSPACE_GENERATION_TOOL_LEASE_RUN_MISMATCH");
          return null;
        });
    verify(lease, org.mockito.Mockito.atLeastOnce()).assertOwned();
  }
}
