package com.navneet.executespec.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The {@link SandboxIsolationProfile#CONTAINER_STRICT_V1} runtime. Ships dark: it is only selected
 * when {@code sandbox.runtime.container-strict-v1.enabled} is on, and that flag is seeded false.
 *
 * <p><strong>Honest scope.</strong> The hardening arguments this class passes are asserted by
 * {@code SandboxContainerCommandTest} and {@code SandboxRuntimeDispatchBoundaryTest}. Those tests
 * prove the argument vector, not the behaviour of a running container. The security matrix the plan
 * requires — network denied in practice, host filesystem and Docker socket inaccessible, metadata
 * endpoint unreachable, other tenants' workspaces unreachable, CPU/memory/PID/time bombs actually
 * terminated, and a two-JVM ownership race against live containers — has NOT been executed. Do not
 * enable this runtime, and do not describe it as proven, until it has.
 *
 * <p>Container naming is derived from the sandbox id so a restarted JVM reattaches to the same
 * container instead of leaking a second one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContainerStrictV1Runtime implements RunSandboxRuntime {

  private static final String NAME_PREFIX = "es-sbx-";

  private final SandboxProperties properties;
  private final FeatureFlagService featureFlags;

  @Override
  public SandboxIsolationProfile profile() {
    return SandboxIsolationProfile.CONTAINER_STRICT_V1;
  }

  @Override
  public boolean available() {
    return featureFlags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)
        && exec(List.of("docker", "version", "--format", "{{.Server.Version}}"), 15).exitCode()
            == 0;
  }

  @Override
  public Provisioned provision(RunSandbox sandbox, SandboxRuntimeSpec spec) {
    if (!available()) {
      throw new RuntimeUnavailableException(
          "CONTAINER_STRICT_V1 is not available for sandbox " + sandbox.id());
    }
    String name = containerName(sandbox);

    // Crash-safe: if a previous attempt by this or another JVM already started the container,
    // reattach rather than starting a second one.
    if (running(name)) {
      log.info("sandbox {} reattaching to existing container {}", sandbox.id(), name);
      return new Provisioned(name, imageDigest(spec.image()));
    }

    ExecResult started =
        exec(
            // RR-821 — mount admission is scoped to THIS sandbox's own directory, derived from
            // the sandbox id. Passing only the global workspace root admitted the root itself
            // and every sibling tenant's workspace.
            SandboxContainerCommand.create(
                name, spec, Path.of(properties.getWorkspaceRoot()), String.valueOf(sandbox.id())),
            properties.getCommandTimeoutSeconds());
    if (started.exitCode() != 0) {
      throw new IllegalStateException(
          "sandbox container did not start (exit %d)".formatted(started.exitCode()));
    }
    return new Provisioned(name, imageDigest(spec.image()));
  }

  @Override
  public void destroy(RunSandbox sandbox) {
    String name =
        sandbox.runtimeHandle() == null ? containerName(sandbox) : sandbox.runtimeHandle();
    ExecResult removed =
        exec(SandboxContainerCommand.destroy(name), properties.getCommandTimeoutSeconds());
    // `docker rm --force` on an absent container exits non-zero with "No such container". That is
    // the expected outcome for reconciliation of an orphan whose container is already gone, so it
    // is a success. Any other non-zero exit means the container may still be live.
    if (removed.exitCode() != 0 && !removed.output().toLowerCase().contains("no such container")) {
      throw new IllegalStateException(
          "sandbox container teardown failed (exit %d)".formatted(removed.exitCode()));
    }
  }

  @Override
  public boolean alive(RunSandbox sandbox) {
    String name =
        sandbox.runtimeHandle() == null ? containerName(sandbox) : sandbox.runtimeHandle();
    return running(name);
  }

  /** The default strict spec for a sandbox, from configuration. */
  public SandboxRuntimeSpec defaultSpec(List<SandboxRuntimeSpec.WritableMount> writableMounts) {
    return new SandboxRuntimeSpec(
        properties.getImage(),
        "/workspace",
        writableMounts,
        new SandboxRuntimeSpec.SandboxRuntimeLimits(
            properties.getCpus(),
            properties.getMemoryBytes(),
            properties.getPids(),
            properties.getWallClock(),
            properties.getTmpfsBytes()),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  static String containerName(RunSandbox sandbox) {
    return NAME_PREFIX + sandbox.id().toString().replace("-", "");
  }

  private boolean running(String name) {
    ExecResult inspected = exec(SandboxContainerCommand.inspectState(name), 30);
    return inspected.exitCode() == 0 && inspected.output().trim().startsWith("true");
  }

  private String imageDigest(String image) {
    ExecResult inspected =
        exec(
            List.of("docker", "image", "inspect", "--format", "{{index .RepoDigests 0}}", image),
            30);
    return inspected.exitCode() == 0 && !inspected.output().isBlank()
        ? inspected.output().trim()
        : "unresolved:" + image;
  }

  /**
   * Runs a docker CLI invocation as an argument vector. Never a shell string, so no path or image
   * name can be interpreted as shell syntax. The child's streams are captured rather than
   * inherited, so container output cannot reach the server log unfiltered.
   */
  private ExecResult exec(List<String> argv, int timeoutSeconds) {
    Process process = null;
    try {
      process = new ProcessBuilder(argv).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new ExecResult(-1, "timed out after " + timeoutSeconds + "s");
      }
      return new ExecResult(process.exitValue(), output);
    } catch (IOException failure) {
      log.warn("sandbox docker invocation failed: {}", argv.getFirst(), failure);
      return new ExecResult(-1, failure.getMessage() == null ? "io error" : failure.getMessage());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      if (process != null) {
        process.destroyForcibly();
      }
      throw new IllegalStateException("sandbox docker invocation interrupted", interrupted);
    }
  }

  private record ExecResult(int exitCode, String output) {}
}
