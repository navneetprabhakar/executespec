package com.navneet.executespec.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the exact argument vector for a {@link SandboxIsolationProfile#CONTAINER_STRICT_V1}
 * container.
 *
 * <p>Deliberately pure and free of I/O so the hardening flags are asserted directly rather than
 * inferred from a running container. Arguments are produced as a list and never as a shell string,
 * so no path or image name can be interpreted as shell syntax.
 *
 * <p>What the profile applies, and what each flag is actually for:
 *
 * <ul>
 *   <li>{@code --network none} — no network namespace access at all. This is the default and the
 *       only implemented policy.
 *   <li>{@code --user} a fixed non-root uid:gid, plus {@code --security-opt no-new-privileges}, so
 *       a setuid binary inside the image cannot regain root.
 *   <li>{@code --read-only} root filesystem, with writes possible only through explicit mounts and
 *       a size-capped {@code tmpfs} at {@code /tmp}.
 *   <li>{@code --cap-drop ALL} — no capabilities are added back.
 *   <li>{@code --cpus}, {@code --memory} (with {@code --memory-swap} pinned equal so swap cannot be
 *       used to exceed it) and {@code --pids-limit} bound resource bombs.
 *   <li>No {@code /var/run/docker.sock}, no host credential path, no {@code --privileged}, no
 *       {@code --cap-add}, no host PID or network namespace. Enforced by {@code
 *       SandboxRuntimeDispatchBoundaryTest}.
 * </ul>
 *
 * <p>Wall-clock is enforced by the caller rather than by an argument, because a container flag
 * cannot express it; {@link #limitsExceededTimeout} exposes the value the runtime must apply.
 */
public final class SandboxContainerCommand {

  /** Fixed unprivileged identity. Chosen high to avoid colliding with image system accounts. */
  public static final String NON_ROOT_USER = "65532:65532";

  private static final String TMPFS_MOUNT = "/tmp";

  private SandboxContainerCommand() {}

  /**
   * The argv that starts a strict sandbox container detached.
   *
   * <p>RR-821 — mount admission is scoped to the sandbox's OWN directory, derived here from the
   * global root plus the sandbox's immutable id. The previous contract admitted every path starting
   * with the configured global root, so the global root itself and any sibling tenant's directory
   * were both bindable read-write. The caller cannot supply the admission root directly; it can
   * only supply the identity the root is derived from.
   *
   * @param globalWorkspaceRoot the configured {@code sandbox.runtime.workspace-root}
   * @param sandboxId this sandbox's immutable identifier
   */
  public static List<String> create(
      String containerName, SandboxRuntimeSpec spec, Path globalWorkspaceRoot, String sandboxId) {
    if (containerName == null || !containerName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")) {
      throw new IllegalArgumentException("unsafe container name: " + containerName);
    }
    Path sandboxRoot = ownedWorkspaceRoot(globalWorkspaceRoot, sandboxId);
    var limits = spec.limits();
    List<String> argv = new ArrayList<>(List.of("docker", "run", "--detach"));

    argv.addAll(List.of("--name", containerName));

    // Isolation.
    argv.addAll(List.of("--network", "none"));
    argv.addAll(List.of("--user", NON_ROOT_USER));
    argv.addAll(List.of("--security-opt", "no-new-privileges"));
    argv.addAll(List.of("--cap-drop", "ALL"));
    argv.add("--read-only");

    // Resource ceilings. memory-swap equals memory so swap cannot be used to exceed the cap.
    argv.addAll(List.of("--cpus", String.valueOf(limits.cpus())));
    argv.addAll(List.of("--memory", limits.memoryBytes() + "b"));
    argv.addAll(List.of("--memory-swap", limits.memoryBytes() + "b"));
    argv.addAll(List.of("--pids-limit", String.valueOf(limits.pids())));

    // The only writable surfaces: a size-capped noexec tmpfs and the explicit mounts.
    argv.addAll(
        List.of(
            "--tmpfs", "%s:rw,noexec,nosuid,size=%d".formatted(TMPFS_MOUNT, limits.tmpfsBytes())));
    for (SandboxRuntimeSpec.WritableMount mount : spec.writableMounts()) {
      requireAbsolute(mount.hostPath(), "writable mount host path");
      requireAbsolute(mount.containerPath(), "writable mount container path");
      requireContained(mount.hostPath(), sandboxRoot);
      argv.addAll(List.of("--volume", mount.hostPath() + ":" + mount.containerPath() + ":rw"));
    }

    argv.addAll(List.of("--workdir", spec.workdir()));
    argv.add(spec.image());
    argv.addAll(List.of("sleep", String.valueOf(limits.wallClock().toSeconds())));
    return List.copyOf(argv);
  }

  /**
   * The argv that runs one command inside an already-running strict sandbox.
   *
   * <p>The isolation of the exec'd process is inherited from the container, not re-declared here:
   * network namespace, read-only root, dropped capabilities and the CPU/memory/PID ceilings all
   * belong to the container and apply to anything running in it. The two properties this builder is
   * responsible for are the two that {@code docker exec} would otherwise let a caller weaken —
   * identity and privilege escalation — so {@code --user} is pinned to the same unprivileged
   * identity as {@link #create} and {@code --privileged} is never emitted.
   *
   * <p>No {@code --env} is passed. The exec'd process sees the container's own environment and
   * nothing of the host's, which is what keeps ambient host credentials out of customer code.
   *
   * <p>Non-interactive by construction: no {@code --tty}, no {@code --interactive}, no {@code
   * --detach}. The caller waits for the command and owns the wall-clock bound, because no exec flag
   * expresses one.
   */
  public static List<String> exec(String containerName, SandboxExecRequest request) {
    if (containerName == null || !containerName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")) {
      throw new IllegalArgumentException("unsafe container name: " + containerName);
    }
    List<String> argv = new ArrayList<>(List.of("docker", "exec"));
    argv.addAll(List.of("--user", NON_ROOT_USER));
    if (request.workdir() != null) {
      requireAbsolute(request.workdir(), "exec workdir");
      argv.addAll(List.of("--workdir", request.workdir()));
    }
    // Container name last among the options: everything after it is the command, never a flag.
    argv.add(containerName);
    argv.addAll(request.argv());
    return List.copyOf(argv);
  }

  /** Idempotent teardown. Succeeds for a container that is already gone. */
  public static List<String> destroy(String containerName) {
    return List.of("docker", "rm", "--force", "--volumes", containerName);
  }

  /** Liveness probe used by reconciliation to find orphaned containers. */
  public static List<String> inspectState(String containerName) {
    return List.of("docker", "inspect", "--format", "{{.State.Running}}", containerName);
  }

  /** The wall-clock ceiling the runtime must enforce; no container flag expresses it. */
  public static long limitsExceededTimeout(SandboxRuntimeSpec spec) {
    return spec.limits().wallClock().toSeconds();
  }

  private static void requireAbsolute(String path, String label) {
    if (path == null || !path.startsWith("/") || path.contains("..")) {
      throw new IllegalArgumentException(
          label + " must be absolute and free of traversal: " + path);
    }
  }

  /**
   * Derives the ownership-scoped workspace root for one sandbox from the configured global root and
   * the sandbox's own immutable id (RR-821).
   *
   * <p>The id is validated as a single opaque path segment so a caller-influenced value can never
   * traverse out of, or sideways within, the global root.
   *
   * @param globalWorkspaceRoot the configured {@code sandbox.runtime.workspace-root}
   * @param sandboxId the sandbox's immutable identifier
   */
  static Path ownedWorkspaceRoot(Path globalWorkspaceRoot, String sandboxId) {
    if (globalWorkspaceRoot == null || !globalWorkspaceRoot.isAbsolute()) {
      throw new IllegalArgumentException("sandbox workspace root must be an absolute path");
    }
    if (sandboxId == null || !sandboxId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException("unsafe sandbox id: " + sandboxId);
    }
    Path root = globalWorkspaceRoot.normalize();
    if (root.getNameCount() == 0) {
      throw new IllegalArgumentException("refusing to treat the host root as a sandbox root");
    }
    return root.resolve(sandboxId).normalize();
  }

  /**
   * Requires a writable mount to resolve inside THIS sandbox's own workspace root, which is the
   * property that keeps the container engine socket, ambient credential stores, other tenants'
   * workspaces, the global sandbox root and the host root unbindable.
   *
   * <p>Containment is proved by normalising both paths — traversal segments are already rejected by
   * {@link #requireAbsolute}. This is a bind-argument guard: it does not resolve symlinks on the
   * host, so a symlink planted inside the workspace root is out of scope here and must be handled
   * where the workspace is populated.
   */
  private static void requireContained(String hostPath, Path sandboxRoot) {
    if (sandboxRoot == null || !sandboxRoot.isAbsolute()) {
      throw new IllegalArgumentException("sandbox workspace root must be an absolute path");
    }
    Path root = sandboxRoot.normalize();
    if (root.getNameCount() == 0) {
      throw new IllegalArgumentException("refusing to treat the host root as a sandbox root");
    }
    Path candidate = Path.of(hostPath).normalize();
    if (!candidate.startsWith(root) || candidate.equals(root.getRoot())) {
      throw new IllegalArgumentException(
          "refusing to mount a host path outside this sandbox's workspace root: " + hostPath);
    }
  }
}
