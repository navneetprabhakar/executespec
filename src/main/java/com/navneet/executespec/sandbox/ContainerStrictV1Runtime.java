package com.navneet.executespec.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * container instead of leaking a second one. Because that name is therefore NOT an ownership proof,
 * every container is stamped at creation with {@link SandboxContainerCommand#LABEL_SANDBOX_ID} and
 * {@link SandboxContainerCommand#LABEL_SANDBOX_EPOCH}, and reattach, liveness and destroy all
 * require an exact label match before touching it (RR-327).
 */
@Component
@Slf4j
public class ContainerStrictV1Runtime implements RunSandboxRuntime {

  private static final String NAME_PREFIX = "es-sbx-";

  private final SandboxProperties properties;
  private final FeatureFlagService featureFlags;

  /**
   * The configured {@code agent.workspace} root. A run's workspace lives at {@code
   * agent.workspace/<runId>}, and RR-1703 derives the writable mount source from it plus the
   * sandbox's own run id rather than from any caller-supplied path.
   */
  private final Path agentWorkspaceRoot;

  @Autowired
  public ContainerStrictV1Runtime(
      SandboxProperties properties,
      FeatureFlagService featureFlags,
      @Value("${agent.workspace}") String agentWorkspaceRoot) {
    this(properties, featureFlags, Path.of(agentWorkspaceRoot));
  }

  /** Test seam: the workspace root is supplied directly rather than resolved from configuration. */
  ContainerStrictV1Runtime(
      SandboxProperties properties, FeatureFlagService featureFlags, Path agentWorkspaceRoot) {
    this.properties = properties;
    this.featureFlags = featureFlags;
    this.agentWorkspaceRoot = agentWorkspaceRoot;
  }

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
    // reattach rather than starting a second one — but only after proving it is OUR container.
    // A name is not an identity (RR-327): reattaching to a container that carries a different
    // sandbox id or a different runtime epoch would execute customer code in someone else's
    // isolation boundary and then report it as this sandbox's runtime.
    if (running(name)) {
      Fence fence = readFence(sandbox, name);
      if (fence != Fence.MATCHES) {
        throw new IllegalStateException(
            "refusing to reattach to container %s: fencing labels %s".formatted(name, fence));
      }
      requireWorkspaceOwnership(sandbox);
      log.info("sandbox {} reattaching to existing container {}", sandbox.id(), name);
      return new Provisioned(name, imageDigest(spec.image()));
    }

    requireWorkspaceOwnership(sandbox);
    ExecResult started =
        exec(
            // RR-821 — mount admission is scoped to THIS sandbox's own directory, derived from
            // the sandbox id. Passing only the global workspace root admitted the root itself
            // and every sibling tenant's workspace.
            // RR-1703 — the writable run-workspace mount source is DERIVED here from the configured
            // agent.workspace root and this sandbox's own immutable run id, never taken from the
            // caller's spec, so a sandbox for run A can only ever bind agent.workspace/A.
            SandboxContainerCommand.create(
                name,
                spec,
                Path.of(properties.getWorkspaceRoot()),
                String.valueOf(sandbox.id()),
                sandbox.runtimeEpoch(),
                agentWorkspaceRoot,
                sandbox.runId(),
                runtimeUser()),
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
        SandboxProcess.run(
            SandboxContainerCommand.exec(name, request, runtimeUser()), request.timeout());

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

  /**
   * Removes this sandbox's container, and only this sandbox's container.
   *
   * <p><strong>RR-327.</strong> This used to resolve the target from the sandbox-id-derived name
   * and issue an unfenced {@code docker rm --force}. That name is stable across a takeover by
   * design — it is what lets a restarted JVM reattach — so a JVM that had just lost the lease could
   * delete the live container the winner had reattached to, in exactly the race the database fence
   * exists to arbitrate. Destroy now reads the container's own fencing labels and removes it only
   * on an exact match of sandbox id AND runtime epoch. Every other answer is a refusal, never a
   * guess: an unlabelled container, a foreign container that answers to this name, or an engine
   * that cannot be read all mean "this is not provably mine", and removing something on that basis
   * is the defect.
   *
   * <p>The consequence is deliberate: a container this runtime cannot prove it owns will not be
   * cleaned up by this method, and the failure surfaces as {@code TEARDOWN_FAILED}. A leaked
   * container an operator must remove is strictly better than a live tenant's container removed by
   * a stale process. No container predating this fence exists — all three sandbox flags are seeded
   * false and no caller outside this package has ever provisioned one.
   *
   * <p>Ownership is the dispatcher's half of the fence, not this method's: labels prove WHICH
   * container, the lease proves WHO may act on it.
   */
  @Override
  public void destroy(RunSandbox sandbox) {
    String name =
        sandbox.runtimeHandle() == null ? containerName(sandbox) : sandbox.runtimeHandle();
    Fence fence = readFence(sandbox, name);
    if (fence == Fence.ABSENT) {
      // Already gone. Teardown is idempotent, and reconciliation of an orphan whose container
      // died with its JVM is the common case.
      return;
    }
    if (fence != Fence.MATCHES) {
      throw new IllegalStateException(
          "refusing to destroy container %s: fencing labels %s".formatted(name, fence));
    }
    ExecResult removed =
        exec(SandboxContainerCommand.destroy(name), properties.getCommandTimeoutSeconds());
    // `docker rm --force` can still race a concurrent removal between the inspect and here, so an
    // absent container remains a success. Any other non-zero exit means it may still be live.
    if (removed.exitCode() != 0 && !removed.output().toLowerCase().contains("no such container")) {
      throw new IllegalStateException(
          "sandbox container teardown failed (exit %d)".formatted(removed.exitCode()));
    }
  }

  /** Whether a container is provably the one this sandbox generation created. */
  enum Fence {
    /** Labels carry this sandbox's exact id and runtime epoch. */
    MATCHES,
    /** No such container. */
    ABSENT,
    /** The container exists but carries different, missing or empty fencing labels. */
    MISMATCHED,
    /** The container engine could not be read, so nothing is proven either way. */
    UNREADABLE
  }

  /**
   * Reads a container's fencing labels (RR-327).
   *
   * <p>Absence is distinguished from unreadability by the engine's own "no such container" text.
   * That classification is textual and therefore best-effort — which is why it is only ever used to
   * widen refusal: an ambiguous answer resolves to {@link Fence#UNREADABLE}, which refuses, and
   * never to {@link Fence#MATCHES}.
   */
  private Fence readFence(RunSandbox sandbox, String name) {
    ExecResult inspected = exec(SandboxContainerCommand.inspectFence(name), 30);
    if (inspected.exitCode() != 0) {
      return inspected.output().toLowerCase().contains("no such") ? Fence.ABSENT : Fence.UNREADABLE;
    }
    String observed = inspected.output().trim();
    String expected =
        SandboxContainerCommand.expectedFence(String.valueOf(sandbox.id()), sandbox.runtimeEpoch());
    return observed.equals(expected) ? Fence.MATCHES : Fence.MISMATCHED;
  }

  /**
   * Whether THIS sandbox's container is still live. A foreign or stale-generation container that
   * happens to answer to the same name is not this sandbox being alive (RR-327).
   */
  @Override
  public boolean alive(RunSandbox sandbox) {
    String name =
        sandbox.runtimeHandle() == null ? containerName(sandbox) : sandbox.runtimeHandle();
    return running(name) && readFence(sandbox, name) == Fence.MATCHES;
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

  private String runtimeUser() {
    return properties.getRuntimeUid() + ":" + properties.getRuntimeGid();
  }

  private void requireWorkspaceOwnership(RunSandbox sandbox) {
    Path runWorkspace =
        SandboxContainerCommand.requireRunWorkspace(agentWorkspaceRoot, sandbox.runId());
    SandboxWorkspaceOwnership.requireWritableTree(
        runWorkspace, properties.getRuntimeUid(), properties.getRuntimeGid());
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
   *
   * <p>Package-private and overridable so the fencing logic above can be exercised against scripted
   * engine responses. It is the ONLY place this class starts a process; a test that replaces it
   * replaces the container engine entirely and nothing else.
   */
  ExecResult exec(List<String> argv, int timeoutSeconds) {
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

  record ExecResult(int exitCode, String output) {}
}
