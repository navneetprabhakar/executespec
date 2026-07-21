package com.navneet.executespec.sandboxroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandbox.RunSandboxLifecycleService;
import com.navneet.executespec.sandbox.RunSandboxRuntimeDispatcher;
import com.navneet.executespec.sandbox.RunSandboxStore;
import com.navneet.executespec.sandbox.SandboxExecRequest;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandbox.SandboxProperties;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The caller-side contract for routing build/test/lint execution into a sandbox.
 *
 * <p>Every assertion here exists because the alternative outcome is running customer-generated code
 * on the application host as the service account (RR-119, RR-282, RR-1674). "Could not execute"
 * must therefore be a value the caller has to handle, and it must never be reachable as a zero exit
 * code.
 */
class SandboxCommandRoutingServiceTest {

  private RunSandboxLifecycleService lifecycle;
  private RunSandboxRuntimeDispatcher dispatcher;
  private RunSandboxStore store;
  private FeatureFlagService flags;
  private PipelineRunRepository runs;
  private SandboxCommandRoutingService routing;

  @BeforeEach
  void setUp() {
    lifecycle = mock(RunSandboxLifecycleService.class);
    dispatcher = mock(RunSandboxRuntimeDispatcher.class);
    store = mock(RunSandboxStore.class);
    flags = mock(FeatureFlagService.class);
    runs = mock(PipelineRunRepository.class);
    routing =
        new SandboxCommandRoutingService(
            lifecycle, dispatcher, store, new SandboxProperties(), flags, runs);
  }

  @Test
  void routingIsOffUntilBothTheLifecycleAndDispatchFlagsAreOn() {
    assertThat(routing.routingEnabled()).isFalse();

    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    assertThat(routing.routingEnabled()).as("lifecycle alone must not route").isFalse();

    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(false);
    when(dispatcher.dispatchEnabled()).thenReturn(true);
    assertThat(routing.routingEnabled()).as("dispatch alone must not route").isFalse();

    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    assertThat(routing.routingEnabled()).isTrue();
  }

  @Test
  void withRoutingOffNothingIsAdmittedProvisionedOrExecuted() {
    SandboxExecResult result =
        routing.execute("run-1", Path.of("/ws/run-1"), List.of("mvn", "-q", "test"), null);

    assertThat(result.unexecuted()).isTrue();
    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.UNAVAILABLE);
    verify(lifecycle, never()).create(any(), any());
    verify(dispatcher, never()).exec(any(), any(), any());
  }

  @Test
  void aCommandWithNoRunScopeIsUnavailableRatherThanUncontained() {
    routingOn();

    SandboxExecResult result = routing.execute("  ", Path.of("/ws"), List.of("eslint"), null);

    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.UNAVAILABLE);
    assertThat(result.detail()).contains("no run scope");
    verify(dispatcher, never()).exec(any(), any(), any());
  }

  @Test
  void anEmptyCommandVectorIsRefusedBeforeAnySandboxIsTouched() {
    routingOn();

    assertThat(routing.execute("run-1", Path.of("/ws"), List.of(), null).unexecuted()).isTrue();
    verify(store, never()).findActiveForRun(anyString());
  }

  @Test
  void aFailureResolvingTheSandboxBecomesUnavailableAndNeverEscapesAsAnException() {
    routingOn();
    when(store.findActiveForRun("run-1")).thenReturn(Optional.empty());
    when(runs.findById("run-1")).thenReturn(Optional.empty());

    SandboxExecResult result =
        routing.execute("run-1", Path.of("/ws/run-1"), List.of("mvn", "test"), null);

    // The caller must be able to write one expression that handles this; an exception invites a
    // catch that falls back to the host, which is the defect being closed.
    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.UNAVAILABLE);
    assertThat(result.unexecuted()).isTrue();
    assertThat(result.exitCode()).isEqualTo(-1);
  }

  @Test
  void aTimeoutBeyondTheHardCeilingIsClampedRatherThanRejected() {
    assertThat(SandboxCommandRoutingService.clamp(Duration.ofHours(9)))
        .isEqualTo(SandboxExecRequest.MAX_TIMEOUT);
    assertThat(SandboxCommandRoutingService.clamp(null))
        .isEqualTo(SandboxCommandRoutingService.DEFAULT_TIMEOUT);
    assertThat(SandboxCommandRoutingService.clamp(Duration.ofSeconds(-5)))
        .isEqualTo(SandboxCommandRoutingService.DEFAULT_TIMEOUT);
    assertThat(SandboxCommandRoutingService.clamp(Duration.ofMinutes(3)))
        .isEqualTo(Duration.ofMinutes(3));
  }

  @Test
  void combinedOutputMirrorsTheHostPathsMergedStream() {
    assertThat(
            SandboxCommandRoutingService.combinedOutput(
                SandboxExecResult.completed(0, "out", "", false, Duration.ZERO)))
        .isEqualTo("out");
    assertThat(
            SandboxCommandRoutingService.combinedOutput(
                SandboxExecResult.completed(1, "out", "err", false, Duration.ZERO)))
        .isEqualTo("out\nerr");
    assertThat(
            SandboxCommandRoutingService.combinedOutput(
                SandboxExecResult.completed(1, "", "err", false, Duration.ZERO)))
        .isEqualTo("err");
  }

  private void routingOn() {
    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    when(dispatcher.dispatchEnabled()).thenReturn(true);
  }
}
