package com.navneet.executespec.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.security.SafeWorkspacePath;

/**
 * Builds the exact argument vector for a {@link SandboxIsolationProfile#CONTAINER_STRICT_V1}
 * container.
 *
 * <p>Arguments are produced as a list and never as a shell string, so no path or image name can be
 * interpreted as shell syntax. Everything here is pure except writable-mount admission, which
 * deliberately touches the filesystem: RR-328 established that a purely lexical containment check
 * cannot decide whether a host path is inside the sandbox root, because a symbolic link planted in
 * that root resolves elsewhere and Docker binds the link target, not the link.
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

  /**
   * Labels that carry the fencing identity of the container itself (RR-327).
   *
   * <p>The container name is derived from the sandbox id and is therefore stable across a takeover,
   * which is exactly what made an unfenced {@code docker rm --force} able to delete a container
   * this JVM does not own. These labels are the resource-side half of the fence: a destroy proves
   * it is removing the container that THIS sandbox generation created, not merely one that happens
   * to answer to the same name. The ownership half — proving the caller still holds the lease —
   * belongs to {@link RunSandboxRuntimeDispatcher}, because only the database knows who owns the
   * sandbox.
   */
  public static final String LABEL_SANDBOX_ID = "com.executespec.sandbox.id";

  /**
   * Runtime generation of the sandbox this container was created for. See {@link
   * #LABEL_SANDBOX_ID}.
   */
  public static final String LABEL_SANDBOX_EPOCH = "com.executespec.sandbox.epoch";

  private static final String TMPFS_MOUNT = "/tmp";

  private SandboxContainerCommand() {}

  /**
   * The argv that starts a strict sandbox container detached, admitting mounts only under the
   * sandbox's own derived directory (route (a) — used for tests and any future staged workspace).
   *
   * <p>Equivalent to {@link #create(String, SandboxRuntimeSpec, Path, String, long, Path, String)}
   * with no run scope, so no run workspace is admissible through it.
   */
  public static List<String> create(
      String containerName,
      SandboxRuntimeSpec spec,
      Path globalWorkspaceRoot,
      String sandboxId,
      long runtimeEpoch) {
    return create(containerName, spec, globalWorkspaceRoot, sandboxId, runtimeEpoch, null, null);
  }

  /**
   * The argv that starts a strict sandbox container detached.
   *
   * <p>RR-821 — mount admission is scoped to the sandbox's OWN directory, derived here from the
   * global root plus the sandbox's immutable id. The previous contract admitted every path starting
   * with the configured global root, so the global root itself and any sibling tenant's directory
   * were both bindable read-write. The caller cannot supply the admission root directly; it can
   * only supply the identity the root is derived from.
   *
   * <p>RR-1703 — derived-source run-workspace admission. A run's workspace lives at {@code
   * agent.workspace/<runId>}, NOT under {@code globalWorkspaceRoot}, so the RR-821 rule alone
   * refuses every real run and every routed build/test/lint reports UNAVAILABLE. Route (b): when a
   * run scope is supplied, the writable mount's HOST path is <em>derived here</em> from THIS
   * sandbox's own immutable {@code runId} — {@code agentWorkspaceRoot/<runId>} — and the caller's
   * spec supplies only the container path. The derived path is then proved through {@link
   * #requireRunWorkspace}: a real, non-symlinked directory canonically contained beneath {@code
   * agentWorkspaceRoot}. Because the mount source is a function of the sandbox's own run id and
   * never a caller-supplied path, a sandbox for run A can only ever mount {@code
   * agentWorkspaceRoot/A}; it cannot reach run B's workspace, a sibling tenant's, the workspace
   * root itself, or anything outside it. This is as strict as staging into {@code
   * globalWorkspaceRoot/<sandboxId>} (route (a)) — both bind a per-owner unique subtree — without
   * the copy cost or the broken write-back, and the container's own filesystem namespace means a
   * symlink INSIDE the mounted tree resolves inside the container, not on the host.
   *
   * <p>RR-327 — the container is stamped with {@link #LABEL_SANDBOX_ID} and {@link
   * #LABEL_SANDBOX_EPOCH} so a later destroy can prove it is removing the resource this sandbox
   * generation created rather than trusting a name.
   *
   * @param globalWorkspaceRoot the configured {@code sandbox.runtime.workspace-root}
   * @param sandboxId this sandbox's immutable identifier
   * @param runtimeEpoch the sandbox's runtime generation at creation time
   * @param agentWorkspaceRoot the configured {@code agent.workspace} root, or null for no run scope
   * @param runId this sandbox's immutable run id, or null for no run scope; when both this and
   *     {@code agentWorkspaceRoot} are present, the writable mount host path is DERIVED from them
   *     and the caller's spec host path is ignored
   */
  public static List<String> create(
      String containerName,
      SandboxRuntimeSpec spec,
      Path globalWorkspaceRoot,
      String sandboxId,
      long runtimeEpoch,
      Path agentWorkspaceRoot,
      String runId) {
    return create(
        containerName,
        spec,
        globalWorkspaceRoot,
        sandboxId,
        runtimeEpoch,
        agentWorkspaceRoot,
        runId,
        NON_ROOT_USER);
  }

  static List<String> create(
      String containerName,
      SandboxRuntimeSpec spec,
      Path globalWorkspaceRoot,
      String sandboxId,
      long runtimeEpoch,
      Path agentWorkspaceRoot,
      String runId,
      String runtimeUser) {
    if (containerName == null || !containerName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")) {
      throw new IllegalArgumentException("unsafe container name: " + containerName);
    }
    requireRuntimeUser(runtimeUser);
    Path sandboxRoot = ownedWorkspaceRoot(globalWorkspaceRoot, sandboxId);
    Path runWorkspaceRoot =
        (agentWorkspaceRoot != null && runId != null)
            ? requireRunWorkspace(agentWorkspaceRoot, runId)
            : null;
    var limits = spec.limits();
    List<String> argv = new ArrayList<>(List.of("docker", "run", "--detach"));

    argv.addAll(List.of("--name", containerName));

    // Fencing identity. Emitted before any policy flag so it is impossible to build a sandbox
    // container that carries no owner stamp.
    argv.addAll(List.of("--label", LABEL_SANDBOX_ID + "=" + sandboxId));
    argv.addAll(List.of("--label", LABEL_SANDBOX_EPOCH + "=" + runtimeEpoch));

    // Isolation.
    argv.addAll(List.of("--network", "none"));
    argv.addAll(List.of("--user", runtimeUser));
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
      requireAbsolute(mount.containerPath(), "writable mount container path");
      String hostPath;
      if (runWorkspaceRoot != null) {
        // Derived-source (b): the host path is a function of this sandbox's own run id, never the
        // caller's spec. The spec supplies only the container path.
        hostPath = runWorkspaceRoot.toString();
      } else {
        requireAbsolute(mount.hostPath(), "writable mount host path");
        requireContained(mount.hostPath(), sandboxRoot);
        hostPath = mount.hostPath();
      }
      argv.addAll(List.of("--volume", hostPath + ":" + mount.containerPath() + ":rw"));
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
    return exec(containerName, request, NON_ROOT_USER);
  }

  static List<String> exec(String containerName, SandboxExecRequest request, String runtimeUser) {
    if (containerName == null || !containerName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")) {
      throw new IllegalArgumentException("unsafe container name: " + containerName);
    }
    requireRuntimeUser(runtimeUser);
    List<String> argv = new ArrayList<>(List.of("docker", "exec"));
    argv.addAll(List.of("--user", runtimeUser));
    if (request.workdir() != null) {
      requireAbsolute(request.workdir(), "exec workdir");
      argv.addAll(List.of("--workdir", request.workdir()));
    }
    // Container name last among the options: everything after it is the command, never a flag.
    argv.add(containerName);
    argv.addAll(request.argv());
    return List.copyOf(argv);
  }

  private static void requireRuntimeUser(String runtimeUser) {
    if (runtimeUser == null || !runtimeUser.matches("[1-9][0-9]{0,9}:[1-9][0-9]{0,9}")) {
      throw new IllegalArgumentException("sandbox runtime user must be a numeric non-root uid:gid");
    }
  }

  /**
   * Idempotent teardown. Succeeds for a container that is already gone.
   *
   * <p>This argv carries no fence of its own — {@code docker rm} has no compare-and-delete. The
   * caller MUST read {@link #inspectFence} and prove the container's labels match before issuing
   * it; {@link ContainerStrictV1Runtime#destroy} is the only permitted caller.
   */
  public static List<String> destroy(String containerName) {
    return List.of("docker", "rm", "--force", "--volumes", containerName);
  }

  /**
   * Reads the fencing labels off a container as {@code <sandboxId>|<epoch>} (RR-327).
   *
   * <p>{@code with} rather than a bare {@code index} so a container with no labels at all yields
   * empty output instead of a template error, which keeps "unlabelled" and "engine broken"
   * distinguishable at the call site. Both are refusals; only one is a bug.
   */
  public static List<String> inspectFence(String containerName) {
    return List.of(
        "docker",
        "inspect",
        "--format",
        "{{with .Config.Labels}}{{index . \"%s\"}}|{{index . \"%s\"}}{{end}}"
            .formatted(LABEL_SANDBOX_ID, LABEL_SANDBOX_EPOCH),
        containerName);
  }

  /** The expected {@link #inspectFence} output for a sandbox generation. */
  public static String expectedFence(String sandboxId, long runtimeEpoch) {
    return sandboxId + "|" + runtimeEpoch;
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
   * Derives and proves THIS sandbox's own run workspace as a mount source (RR-1703, route (b)).
   *
   * <p>The path is a function of the configured {@code agent.workspace} root and the sandbox's
   * immutable {@code runId} — {@code agentWorkspaceRoot/<runId>} — so it can never be a
   * caller-supplied path. The run id is validated as a single opaque path segment and the resolved
   * path is required to be a direct child of the workspace root, so a caller-influenced id can
   * never traverse out of, or sideways within, that root.
   *
   * <p>Containment is proved on disk exactly as {@link #requireContained} proves it for the sandbox
   * root: the target must already exist as a real directory (checked {@code NOFOLLOW_LINKS}), and
   * {@link SafeWorkspacePath#requireWithin} proves no symbolic link on any existing component and
   * canonical containment beneath the workspace root. A run workspace that is missing, a file, or
   * reached through a link is a refusal — which fails provisioning closed, exactly as the sandbox
   * seam requires. The result is the normalized path Docker will bind.
   *
   * @param agentWorkspaceRoot the configured {@code agent.workspace} root
   * @param runId this sandbox's immutable run id
   */
  static Path requireRunWorkspace(Path agentWorkspaceRoot, String runId) {
    if (agentWorkspaceRoot == null) {
      throw new IllegalArgumentException("agent workspace root must be provided");
    }
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException("unsafe run id: " + runId);
    }
    Path base = agentWorkspaceRoot.toAbsolutePath().normalize();
    if (base.getNameCount() == 0) {
      throw new IllegalArgumentException(
          "refusing to treat the host root as the run workspace base");
    }
    Path derived = base.resolve(runId).normalize();
    if (!base.equals(derived.getParent())) {
      // The validated single-segment run id guarantees this, but prove it rather than assume it:
      // the mount source must be a direct child of the workspace root, never a deeper or sibling
      // path.
      throw new IllegalArgumentException(
          "run workspace must be a direct child of the agent workspace root: " + runId);
    }
    try {
      if (!Files.isDirectory(derived, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            "run workspace is not an existing real directory: " + runId);
      }
      SafeWorkspacePath.requireWithin(base, derived);
    } catch (IOException | SecurityException notContained) {
      // The message names the run id, never what it resolved to: a rejection must not become a
      // probe for the host's filesystem layout.
      throw new IllegalArgumentException(
          "run workspace does not resolve inside the agent workspace root: " + runId, notContained);
    }
    return derived;
  }

  /**
   * Requires a writable mount to resolve inside THIS sandbox's own workspace root, which is the
   * property that keeps the container engine socket, ambient credential stores, other tenants'
   * workspaces, the global sandbox root and the host root unbindable.
   *
   * <p>RR-328 — this used to be lexical only, and its own javadoc admitted it did not resolve
   * symbolic links. A lexical check is not containment: {@code <root>/work} passes it while being a
   * link to {@code /var/run}, and Docker binds the link's target read-write into a container the
   * customer's code controls. Containment is now proved on disk through {@link SafeWorkspacePath},
   * the shared boundary the rest of the framework already uses, rather than a fourth private copy
   * of the same rules: relative-only resolution beneath a canonical root, no symbolic link in any
   * existing component, and refusal rather than redirection.
   *
   * <p>Deliberately stricter than {@code SafeWorkspacePath} alone on one point: the mount must
   * already exist as a real directory. A bind path Docker has to create is created root-owned and
   * outside the workspace-preparation path that would have been checked, so "not there yet" is a
   * refusal rather than a mount.
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
    try {
      if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            "refusing to mount a host path that is not an existing real directory: " + hostPath);
      }
      SafeWorkspacePath.requireWithin(root, candidate);
    } catch (IOException | SecurityException notContained) {
      // The message names the path, never what it resolved to: a rejection must not become a probe
      // for the host's filesystem layout.
      throw new IllegalArgumentException(
          "refusing to mount a host path that does not resolve inside this sandbox's workspace"
              + " root: "
              + hostPath,
          notContained);
    }
  }
}
