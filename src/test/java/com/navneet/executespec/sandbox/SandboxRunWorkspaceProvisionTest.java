package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1703 — the flags-ON proof that the sandbox exec seam is now operable for a real run workspace.
 *
 * <p>Before this lane, {@code SandboxContainerCommand} admitted a writable mount only under {@code
 * executespec.sandbox.workspace-root/<sandboxId>}, but a run's workspace lives at {@code
 * agent.workspace/<runId>}. Provisioning was therefore refused, the sandbox row went {@code
 * FAILED}, and every routed build/test/lint reported {@link SandboxExecResult.Status#UNAVAILABLE}.
 * These tests script the container engine (no live daemon) and prove that with both flags on,
 * {@link RunSandboxRuntimeDispatcher#provision} now SUCCEEDS for a run workspace and binds the
 * derived {@code agent.workspace/<runId>} path, and that a subsequent command reaches the sandbox
 * and returns COMPLETED rather than UNAVAILABLE.
 *
 * <p>These assertions do NOT prove what a running container can do — the live security matrix and
 * two-JVM takeover against real containers remain unexecuted; see {@link ContainerStrictV1Runtime}.
 */
class SandboxRunWorkspaceProvisionTest {

  private static final UUID SANDBOX_ID = UUID.randomUUID();
  private static final String RUN_ID = "run-flagson-1";
  private static final String CONTAINER = "es-sbx-" + SANDBOX_ID.toString().replace("-", "");

  @TempDir Path agentWorkspaceRoot;

  private RunSandboxStore store;
  private FeatureFlagService flags;
  private ScriptedContainerRuntime runtime;
  private RunSandboxRuntimeDispatcher dispatcher;
  private Path runWorkspace;

  @BeforeEach
  void setUp() throws IOException {
    store = mock(RunSandboxStore.class);
    flags = mock(FeatureFlagService.class);
    // Both flags ON — this is the whole point of the test.
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_DISPATCH)).thenReturn(true);
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)).thenReturn(true);

    // The real run workspace exists on disk under the configured agent.workspace root.
    runWorkspace =
        Files.createDirectories(agentWorkspaceRoot.toAbsolutePath().normalize().resolve(RUN_ID));

    runtime = new ScriptedContainerRuntime(new SandboxProperties(), flags, agentWorkspaceRoot);
    dispatcher =
        new RunSandboxRuntimeDispatcher(
            store, flags, List.of(new NotEnabledSandboxRuntime(), runtime));
  }

  @Test
  void provisionSucceedsForARunWorkspaceAndBindsItsDerivedPath() {
    SandboxRuntimeSpec spec = spec();
    RunSandbox sandbox = sandbox(SandboxState.REQUESTED, spec.configDigest());
    RunSandbox.Lease lease = lease();
    when(store.markReady(eq(lease), anyString(), anyString()))
        .thenReturn(sandbox(SandboxState.READY, spec.configDigest()));

    RunSandbox provisioned = dispatcher.provision(sandbox, lease, spec);

    // It did not fail the row — before RR-1703 the derived mount was refused and this path threw.
    assertThat(provisioned.state()).isEqualTo(SandboxState.READY);
    verify(store).markReady(eq(lease), anyString(), anyString());

    // The container was actually launched, and the writable mount is the DERIVED run workspace —
    // the path that was previously unmountable — not a caller-supplied one.
    List<List<String>> runs = runtime.issued("run");
    assertThat(runs).hasSize(1);
    assertThat(String.join(" ", runs.get(0)))
        .contains("--volume " + runWorkspace + ":/workspace:rw");
  }

  @Test
  void aRoutedCommandReachesTheSandboxInsteadOfReportingUnavailable() {
    SandboxRuntimeSpec spec = spec();
    RunSandbox ready = sandbox(SandboxState.READY, spec.configDigest());
    RunSandbox.Lease lease = lease();
    when(store.authorizeExec(any()))
        .thenReturn(RunSandboxStore.SandboxExecAuthorization.AUTHORIZED);

    SandboxExecResult result =
        dispatcher.exec(
            ready,
            lease,
            new SandboxExecRequest(
                List.of("mvn", "-q", "verify"), "/workspace", Duration.ofMinutes(5)));

    // Not UNAVAILABLE, not REFUSED — the sandbox ran the command.
    assertThat(result.status()).isEqualTo(SandboxExecResult.Status.COMPLETED);
    assertThat(result.unexecuted()).isFalse();

    // And the command reached the container-exec boundary as a docker exec argv.
    assertThat(runtime.execArgvs).hasSize(1);
    assertThat(runtime.execArgvs.get(0)).startsWith("docker", "exec").contains(CONTAINER);
    assertThat(runtime.execArgvs.get(0)).containsSequence("mvn", "-q", "verify");
  }

  private static SandboxRuntimeSpec spec() {
    return new SandboxRuntimeSpec(
        "img",
        "/workspace",
        // Host path here is deliberately bogus: RR-1703 ignores it and derives the mount source.
        List.of(new SandboxRuntimeSpec.WritableMount("/caller/says/this", "/workspace")),
        new SandboxRuntimeSpec.SandboxRuntimeLimits(
            2.0, 1024L * 1024 * 1024, 128, Duration.ofMinutes(15), 64L * 1024 * 1024),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  private static RunSandbox.Lease lease() {
    return new RunSandbox.Lease(SANDBOX_ID, "jvm-a", 1L, Instant.now().plusSeconds(60));
  }

  private static RunSandbox sandbox(SandboxState state, String configDigest) {
    return new RunSandbox(
        SANDBOX_ID,
        RUN_ID,
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
        state == SandboxState.READY ? CONTAINER : null,
        state == SandboxState.READY ? "img@sha256:abc" : null,
        configDigest,
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(3600),
        null,
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

  /**
   * The container engine, scripted. Docker CLI invocations (provision path) are answered through
   * the overridden {@code exec(List,int)} seam. The command-in-container response is scripted
   * through the public {@code exec}, because running the command in a real container is the
   * live-daemon concern this test deliberately does not cover — it records the exact {@code docker
   * exec} argv the seam would issue and returns a scripted COMPLETED.
   */
  private static final class ScriptedContainerRuntime extends ContainerStrictV1Runtime {

    private final List<List<String>> issued = new ArrayList<>();
    private final List<List<String>> execArgvs = new ArrayList<>();
    private final Map<String, ExecResult> responses = new LinkedHashMap<>();

    ScriptedContainerRuntime(
        SandboxProperties properties, FeatureFlagService featureFlags, Path agentWorkspaceRoot) {
      super(properties, featureFlags, agentWorkspaceRoot);
      responses.put("version", new ExecResult(0, "27.0.0"));
      // No such container yet — a fresh provision, not a reattach.
      responses.put("state", new ExecResult(1, "Error: No such object"));
      responses.put("digest", new ExecResult(0, "img@sha256:abc"));
      responses.put("run", new ExecResult(0, ""));
    }

    List<List<String>> issued(String dockerVerb) {
      return issued.stream()
          .filter(argv -> argv.size() > 1 && argv.get(1).equals(dockerVerb))
          .toList();
    }

    @Override
    ExecResult exec(List<String> argv, int timeoutSeconds) {
      issued.add(argv);
      String joined = String.join(" ", argv);
      if (joined.contains("version")) {
        return responses.get("version");
      }
      if (joined.contains(".State.Running")) {
        return responses.get("state");
      }
      if (joined.contains("RepoDigests")) {
        return responses.get("digest");
      }
      return responses.get("run");
    }

    @Override
    public SandboxExecResult exec(RunSandbox sandbox, SandboxExecRequest request) {
      String name =
          sandbox.runtimeHandle() == null
              ? ContainerStrictV1Runtime.containerName(sandbox)
              : sandbox.runtimeHandle();
      execArgvs.add(SandboxContainerCommand.exec(name, request));
      return SandboxExecResult.completed(0, "BUILD SUCCESS", "", false, Duration.ofSeconds(2));
    }
  }
}
