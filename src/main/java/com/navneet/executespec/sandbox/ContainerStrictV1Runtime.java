package com.navneet.executespec.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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

  /**
   * Runs one command inside this sandbox's container and waits for it under a wall-clock bound.
   *
   * <p>Isolation is the container's, not this method's: the exec'd process inherits the network
   * namespace ({@code none}), the read-only root, the dropped capabilities and the CPU/memory/PID
   * ceilings that {@link SandboxContainerCommand#create} applied. What this method adds is a
   * reachable timeout, bounded capture and an honest status.
   *
   * <p><strong>Timeout semantics, stated exactly.</strong> Expiry destroys the {@code docker exec}
   * client and its descendants on the host. The process inside the container is a child of the
   * container's init, not of that client, so it is not proven dead — it stays bounded by the
   * container's own ceilings and is destroyed with the container by {@link #destroy}. A timed-out
   * sandbox should be released, not reused. This is stated rather than papered over because a
   * container-side kill would depend on a binary being present in the customer-supplied image, and
   * a containment guarantee that depends on the contents of an untrusted image is not a guarantee.
   */
  @Override
  public SandboxExecResult exec(RunSandbox sandbox, SandboxExecRequest request) {
    if (!featureFlags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)) {
      return SandboxExecResult.unavailable(
          RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1 + " is off; nothing was executed");
    }
    String name =
        sandbox.runtimeHandle() == null ? containerName(sandbox) : sandbox.runtimeHandle();

    SandboxProcess.Outcome outcome =
        SandboxProcess.run(SandboxContainerCommand.exec(name, request), request.timeout());

    if (!outcome.finished()) {
      log.warn(
          "sandbox {} command {} did not finish: {}",
          sandbox.id(),
          request.programLabel(),
          outcome.detail());
      return SandboxExecResult.timedOut(
          outcome.stdout(), outcome.stderr(), outcome.truncated(), outcome.duration());
    }
    // A container-engine failure is not a command failure. The engine reports these on stderr with
    // stable prefixes; classification is textual and therefore best-effort, so it is used only to
    // widen "unavailable" — a command that genuinely exits non-zero is still reported as COMPLETED.
    String engineFailure = engineFailure(outcome.exitCode(), outcome.stderr());
    if (engineFailure != null) {
      return SandboxExecResult.unavailable(engineFailure);
    }
    return SandboxExecResult.completed(
        outcome.exitCode(),
        outcome.stdout(),
        outcome.stderr(),
        outcome.truncated(),
        outcome.duration());
  }

  /** The engine could not run the command at all, as opposed to the command exiting non-zero. */
  private static String engineFailure(int exitCode, String stderr) {
    if (exitCode == 0 || stderr == null) {
      return null;
    }
    String lower = stderr.toLowerCase();
    if (lower.contains("no such container")) {
      return "sandbox container is gone";
    }
    if (lower.contains("cannot connect to the docker daemon")
        || lower.contains("is not running")
        || lower.contains("container is paused")) {
      return "container engine unavailable";
    }
    return null;
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
   *
   * <p>Routed through {@link SandboxProcess} so the declared {@code timeoutSeconds} is actually
   * reachable. The previous implementation read the child's stdout to EOF before calling {@code
   * waitFor}, and stdout only closes when the child exits — so the wait was always reached on an
   * already-dead process and a wedged docker CLI pinned this thread indefinitely. It also destroyed
   * only the direct child, leaving CLI helper processes alive.
   */
  private ExecResult exec(List<String> argv, int timeoutSeconds) {
    SandboxProcess.Outcome outcome = SandboxProcess.run(argv, Duration.ofSeconds(timeoutSeconds));
    if (!outcome.finished()) {
      log.warn(
          "sandbox docker invocation did not finish: {} ({})", argv.getFirst(), outcome.detail());
      return new ExecResult(-1, outcome.detail() == null ? "did not finish" : outcome.detail());
    }
    // These call sites parse a single combined blob (a state flag, a digest, a "no such container"
    // message), so the two streams are joined here rather than at the source.
    return new ExecResult(outcome.exitCode(), outcome.stdout() + outcome.stderr());
  }

  private record ExecResult(int exitCode, String output) {}
}
