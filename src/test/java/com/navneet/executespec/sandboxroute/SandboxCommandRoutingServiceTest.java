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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandbox.RunSandbox;
import com.navneet.executespec.sandbox.RunSandboxLifecycleService;
import com.navneet.executespec.sandbox.RunSandboxRuntimeDispatcher;
import com.navneet.executespec.sandbox.RunSandboxStore;
import com.navneet.executespec.sandbox.SandboxExecRequest;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandbox.SandboxIsolationProfile;
import com.navneet.executespec.sandbox.SandboxNetworkPolicy;
import com.navneet.executespec.sandbox.SandboxProperties;
import com.navneet.executespec.sandbox.SandboxRetentionState;
import com.navneet.executespec.sandbox.SandboxSecretPolicy;
import com.navneet.executespec.sandbox.SandboxState;
import com.navneet.executespec.state.entity.PipelineRun;
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
  private SandboxProperties properties;

  @BeforeEach
  void setUp() {
    lifecycle = mock(RunSandboxLifecycleService.class);
    dispatcher = mock(RunSandboxRuntimeDispatcher.class);
    store = mock(RunSandboxStore.class);
    flags = mock(FeatureFlagService.class);
    runs = mock(PipelineRunRepository.class);
    properties = new SandboxProperties();
    properties.setImage(
        "registry.example/executespec-toolchain@sha256:"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    routing =
        new SandboxCommandRoutingService(lifecycle, dispatcher, store, properties, flags, runs);
  }

  @Test
  void routingIsOffUntilEveryGatePinnedImageAndRuntimeReadinessArePresent() {
    assertThat(routing.routingEnabled()).isFalse();

    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    assertThat(routing.routingEnabled()).as("lifecycle alone must not route").isFalse();

    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(false);
    when(dispatcher.dispatchEnabled()).thenReturn(true);
    assertThat(routing.routingEnabled()).as("dispatch alone must not route").isFalse();

    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    assertThat(routing.routingEnabled()).as("strict runtime must also be selected").isFalse();

    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)).thenReturn(true);
    assertThat(routing.routingEnabled()).as("toolchain readiness is an independent gate").isFalse();

    when(flags.isEnabled(SandboxCommandRoutingService.FLAG_TOOLCHAIN_READINESS)).thenReturn(true);
    assertThat(routing.routingEnabled()).as("runtime must be actually available").isFalse();

    when(dispatcher.selectedProfile()).thenReturn(SandboxIsolationProfile.CONTAINER_STRICT_V1);
    assertThat(routing.routingEnabled()).isTrue();
  }

  @Test
  void mutableImageTagCannotBecomeExecutionReady() {
    properties.setImage("registry.example/executespec-toolchain:latest");
    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)).thenReturn(true);
    when(flags.isEnabled(SandboxCommandRoutingService.FLAG_TOOLCHAIN_READINESS)).thenReturn(true);
    when(dispatcher.dispatchEnabled()).thenReturn(true);
    when(dispatcher.selectedProfile()).thenReturn(SandboxIsolationProfile.CONTAINER_STRICT_V1);

    assertThat(routing.readiness().enabled()).isFalse();
    assertThat(routing.readiness().imageDigestPinned()).isFalse();
    assertThat(routing.readiness().reason()).contains("not pinned");
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
  void terminalRunCannotResurrectASandboxAfterCleanup() {
    routingOn();
    PipelineRun run = mock(PipelineRun.class);
    when(run.getStatus()).thenReturn("COMPLETE");
    when(run.getCreatedByUserId()).thenReturn("user-1");
    when(run.getOrgId()).thenReturn("org-1");
    when(store.findActiveForRun("run-1")).thenReturn(Optional.empty());
    when(runs.findById("run-1")).thenReturn(Optional.of(run));

    SandboxExecResult result =
        routing.execute("run-1", Path.of("/ws/run-1"), List.of("mvn", "test"), null);

    assertThat(result.unexecuted()).isTrue();
    assertThat(result.detail()).contains("already terminal");
    verify(lifecycle, never()).create(any(), any());
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

  @Test
  void timedOutCommandDestroysAndFailsTheSandboxBeforeReturning() {
    routingOn();
    RunSandbox sandbox = sandbox(SandboxState.READY);
    RunSandbox.Lease lease =
        new RunSandbox.Lease(sandbox.id(), "owner", 1L, Instant.now().plusSeconds(60));
    when(store.findActiveForRun("run-1")).thenReturn(Optional.of(sandbox));
    when(store.tryAcquire(any(), anyString(), any())).thenReturn(Optional.of(lease));
    when(store.get(sandbox.id())).thenReturn(Optional.of(sandbox));
    when(dispatcher.exec(any(), any(), any()))
        .thenReturn(SandboxExecResult.timedOut("", "", false, Duration.ofMinutes(1)));

    SandboxExecResult result =
        routing.execute(
            "run-1", Path.of("/ws/run-1"), List.of("mvn", "test"), Duration.ofMinutes(1));

    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.TIMED_OUT);
    verify(dispatcher)
        .failAndRelease(sandbox, lease, "COMMAND_TIMEOUT", "sandbox command exceeded PT1M");
  }

  @Test
  void terminalRunReleasesItsOwnedSandboxEvenWhenDispatchIsOff() {
    PipelineRun run = mock(PipelineRun.class);
    when(run.getStatus()).thenReturn("COMPLETE");
    RunSandbox sandbox = sandbox(SandboxState.READY);
    RunSandbox.Lease lease =
        new RunSandbox.Lease(sandbox.id(), "owner", 1L, Instant.now().plusSeconds(60));
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(store.findActiveForRun("run-1")).thenReturn(Optional.of(sandbox));
    when(store.tryAcquire(any(), anyString(), any())).thenReturn(Optional.of(lease));
    when(store.get(sandbox.id())).thenReturn(Optional.of(sandbox));
    when(dispatcher.release(sandbox, lease, SandboxState.TERMINATED))
        .thenReturn(sandbox(SandboxState.TERMINATED));

    assertThat(routing.releaseForTerminalRun("run-1"))
        .get()
        .extracting(RunSandbox::state)
        .isEqualTo(SandboxState.TERMINATED);
  }

  @Test
  void cancelledRunRequestsCancellationWhenAnotherOwnerStillHoldsTheLease() {
    PipelineRun run = mock(PipelineRun.class);
    when(run.getStatus()).thenReturn("CANCELLED");
    RunSandbox sandbox = sandbox(SandboxState.READY);
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(store.findActiveForRun("run-1")).thenReturn(Optional.of(sandbox));
    when(store.tryAcquire(any(), anyString(), any())).thenReturn(Optional.empty());

    assertThat(routing.releaseForTerminalRun("run-1")).isEmpty();

    verify(lifecycle).cancel(sandbox.id());
    verify(dispatcher, never()).release(any(), any(), any());
  }

  private void routingOn() {
    when(flags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)).thenReturn(true);
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)).thenReturn(true);
    when(flags.isEnabled(SandboxCommandRoutingService.FLAG_TOOLCHAIN_READINESS)).thenReturn(true);
    when(dispatcher.dispatchEnabled()).thenReturn(true);
    when(dispatcher.selectedProfile()).thenReturn(SandboxIsolationProfile.CONTAINER_STRICT_V1);
  }

  private static RunSandbox sandbox(SandboxState state) {
    return new RunSandbox(
        UUID.randomUUID(),
        "run-1",
        "project-1",
        null,
        "user-1",
        "org-1",
        "revision",
        "[]",
        state,
        SandboxIsolationProfile.CONTAINER_STRICT_V1,
        1,
        null,
        0L,
        null,
        1L,
        null,
        "es-sbx-1",
        "sha256:image",
        "sha256:config",
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(600),
        null,
        null,
        state == SandboxState.RELEASING ? SandboxState.TERMINATED : null,
        null,
        null,
        0,
        null,
        SandboxRetentionState.RETAINED,
        null,
        "idem-" + UUID.randomUUID(),
        Instant.now(),
        null,
        null,
        null,
        null);
  }
}
