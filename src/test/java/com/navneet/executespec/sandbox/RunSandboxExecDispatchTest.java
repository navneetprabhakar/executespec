package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gates in front of sandbox command execution.
 *
 * <p>Each one is here because the alternative to a refusal is running customer-generated code on
 * the application host as the service account, so "refused" must be unmistakable at the call site
 * and must never be reachable as a zero exit code.
 */
class RunSandboxExecDispatchTest {

  private RunSandboxStore store;
  private FeatureFlagService flags;
  private RecordingRuntime strict;
  private RunSandboxRuntimeDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    store = mock(RunSandboxStore.class);
    flags = mock(FeatureFlagService.class);
    strict = new RecordingRuntime();
    dispatcher =
        new RunSandboxRuntimeDispatcher(
            store, flags, List.of(new NotEnabledSandboxRuntime(), strict));
  }

  @Test
  void anAuthorizedReadySandboxRunsTheCommandAndReturnsItsExitStatus() {
    dispatchOn();
    authorize(RunSandboxStore.SandboxExecAuthorization.AUTHORIZED);
    RunSandbox sandbox = sandbox(SandboxState.READY);

    SandboxExecResult result = dispatcher.exec(sandbox, lease(sandbox), request());

    assertThat(result.completed()).isTrue();
    assertThat(result.succeeded()).isTrue();
    assertThat(result.unexecuted()).isFalse();
    assertThat(strict.executed).containsExactly("mvn");
  }

  @Test
  void aNonZeroExitIsACompletedCommandAndNotAnUnavailableSandbox() {
    dispatchOn();
    authorize(RunSandboxStore.SandboxExecAuthorization.AUTHORIZED);
    strict.result = SandboxExecResult.completed(1, "", "boom", false, Duration.ofSeconds(1));

    SandboxExecResult result = dispatcher.exec(sandbox(SandboxState.READY), lease(), request());

    // The distinction the whole result type exists for: the sandbox worked, the command failed.
    assertThat(result.completed()).isTrue();
    assertThat(result.succeeded()).isFalse();
    assertThat(result.unexecuted()).isFalse();
  }

  @Test
  void turningDispatchOffStopsNewCommandsWithoutExecutingAnything() {
    authorize(RunSandboxStore.SandboxExecAuthorization.AUTHORIZED);

    SandboxExecResult result = dispatcher.exec(sandbox(SandboxState.READY), lease(), request());

    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.UNAVAILABLE);
    assertThat(result.unexecuted()).isTrue();
    assertThat(result.exitCode()).isEqualTo(-1);
    assertThat(strict.executed).isEmpty();
  }

  @Test
  void aStaleOwnerCannotExecIntoTheNewOwnersContainer() {
    dispatchOn();
    authorize(RunSandboxStore.SandboxExecAuthorization.FENCED);
    RunSandbox sandbox = sandbox(SandboxState.READY);

    // Thrown, not returned: losing ownership is not a command outcome and must not be something a
    // caller can miss by reading an exit code.
    assertThatThrownBy(() -> dispatcher.exec(sandbox, lease(sandbox), request()))
        .isInstanceOf(SandboxLeaseFencedException.class);
    assertThat(strict.executed).isEmpty();
  }

  @Test
  void aSandboxThatIsNotReadyIsRefusedRatherThanExecutedInto() {
    dispatchOn();
    authorize(RunSandboxStore.SandboxExecAuthorization.NOT_READY);

    SandboxExecResult result =
        dispatcher.exec(sandbox(SandboxState.CANCEL_REQUESTED), lease(), request());

    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.REFUSED);
    assertThat(result.unexecuted()).isTrue();
    assertThat(strict.executed).isEmpty();
  }

  @Test
  void anUnavailableRuntimeBecomesAStatusAndDoesNotFailTheSandbox() {
    dispatchOn();
    authorize(RunSandboxStore.SandboxExecAuthorization.AUTHORIZED);
    strict.failure =
        new RunSandboxRuntime.RuntimeUnavailableException("container engine unavailable");

    SandboxExecResult result = dispatcher.exec(sandbox(SandboxState.READY), lease(), request());

    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.UNAVAILABLE);
    // A command that could not run is not evidence the sandbox is broken; failing the row here
    // would discard a live workspace over a transient engine hiccup.
    org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
        .fail(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void theNoOpRuntimeReportsUnavailableInsteadOfPretendingTheCommandSucceeded() {
    SandboxExecResult result =
        new NotEnabledSandboxRuntime().exec(sandbox(SandboxState.READY), request());

    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.UNAVAILABLE);
    assertThat(result.succeeded()).isFalse();
    assertThat(result.exitCode()).isNotZero();
  }

  @Test
  void aTimeoutIsNeverReportedAsASuccessfulCommand() {
    SandboxExecResult timedOut =
        SandboxExecResult.timedOut("partial", "", false, Duration.ofMinutes(5));

    assertThat(timedOut.completed()).isFalse();
    assertThat(timedOut.succeeded()).isFalse();
    // A timeout is not "unexecuted" either — something did run, and the sandbox is now tainted.
    assertThat(timedOut.unexecuted()).isFalse();
    assertThat(timedOut.detail()).isEqualTo(SandboxExecResult.TIMED_OUT_IN_CONTAINER_CAVEAT);
  }

  private void dispatchOn() {
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
  }

  private void authorize(RunSandboxStore.SandboxExecAuthorization authorization) {
    when(store.authorizeExec(org.mockito.ArgumentMatchers.any())).thenReturn(authorization);
  }

  private static SandboxExecRequest request() {
    return SandboxExecRequest.of(List.of("mvn", "-q", "verify"), Duration.ofMinutes(5));
  }

  private static final UUID ID = UUID.randomUUID();

  private static RunSandbox.Lease lease() {
    return new RunSandbox.Lease(ID, "jvm-a", 1L, Instant.now().plusSeconds(60));
  }

  private static RunSandbox.Lease lease(RunSandbox sandbox) {
    return new RunSandbox.Lease(sandbox.id(), "jvm-a", 1L, Instant.now().plusSeconds(60));
  }

  private static RunSandbox sandbox(SandboxState state) {
    return new RunSandbox(
        ID,
        "run-1",
        "project-1",
        null,
        "user-1",
        "org-1",
        "abc1234",
        "[]",
        state,
        SandboxIsolationProfile.CONTAINER_STRICT_V1,
        1,
        "jvm-a",
        1L,
        Instant.now().plusSeconds(60),
        1L,
        Instant.now(),
        "es-sbx-1",
        "sha256:image",
        "sha256:config",
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(3600),
        null,
        null,
        null,
        null,
        null,
        0,
        null,
        SandboxRetentionState.RETAINED,
        null,
        "idem-1",
        Instant.now(),
        null,
        null,
        null,
        null);
  }

  /** Records what reached the runtime instead of touching a container engine. */
  private static final class RecordingRuntime implements RunSandboxRuntime {
    private final List<String> executed = new ArrayList<>();
    private SandboxExecResult result =
        SandboxExecResult.completed(0, "ok", "", false, Duration.ofSeconds(1));
    private RuntimeException failure;

    @Override
    public SandboxIsolationProfile profile() {
      return SandboxIsolationProfile.CONTAINER_STRICT_V1;
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public Provisioned provision(RunSandbox sandbox, SandboxRuntimeSpec spec) {
      return new Provisioned("es-sbx-1", "sha256:image");
    }

    @Override
    public SandboxExecResult exec(RunSandbox sandbox, SandboxExecRequest request) {
      if (failure != null) {
        throw failure;
      }
      executed.add(request.programLabel());
      return result;
    }

    @Override
    public void destroy(RunSandbox sandbox) {}

    @Override
    public boolean alive(RunSandbox sandbox) {
      return true;
    }
  }
}
