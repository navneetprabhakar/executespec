package com.navneet.executespec.sandboxroute;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandbox.RunSandbox;
import com.navneet.executespec.sandbox.RunSandboxLifecycleService;
import com.navneet.executespec.sandbox.RunSandboxRuntimeDispatcher;
import com.navneet.executespec.sandbox.RunSandboxStore;
import com.navneet.executespec.sandbox.SandboxExecRequest;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandbox.SandboxNetworkPolicy;
import com.navneet.executespec.sandbox.SandboxProperties;
import com.navneet.executespec.sandbox.SandboxRuntimeSpec;
import com.navneet.executespec.sandbox.SandboxSecretPolicy;
import com.navneet.executespec.sandbox.SandboxState;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Routes a build, test or lint command into this run's sandbox instead of onto the application host
 * (RR-119, RR-282, RR-1674).
 *
 * <p>The seam it drives is {@code sandbox/} (RR-1694). This class is the caller-side adapter: it
 * resolves the run's sandbox, keeps this JVM's lease on it, and hands one argument vector to {@link
 * RunSandboxRuntimeDispatcher#exec}. It executes nothing itself and it never falls back to host
 * execution.
 *
 * <h2>The one rule</h2>
 *
 * <p>{@link #routingEnabled()} is the ONLY question a caller may ask before choosing a path. When
 * it answers false the caller keeps its existing host behaviour byte-for-byte; when it answers true
 * the caller must use {@link #execute} and must surface {@link SandboxExecResult#unexecuted()} as a
 * typed failure. "The sandbox could not run this" is never licence to run it on the host — that is
 * the entire point of the containment, and {@link SandboxExecResult} models unavailability as a
 * value precisely so a {@code catch} cannot quietly restore the defect.
 *
 * <h2>Lease handling</h2>
 *
 * <p>A run issues many commands (per-task build smokes, the reviewer's final build, the workspace
 * test run). Acquiring a fresh lease per command would fail on the second one, because the first
 * lease is still live. This class therefore caches the lease it won per sandbox and heartbeats it
 * before reuse; a heartbeat that fails means another JVM took over, and the next call re-acquires
 * or reports unavailable. The lease it requests is at least as long as the command's own timeout,
 * so an ordinary long build does not lapse mid-command.
 *
 * <h2>Known limitation, stated rather than implied away</h2>
 *
 * <p>{@code SandboxContainerCommand} admits a writable mount only when its host path resolves
 * inside {@code executespec.sandbox.workspace-root/<sandboxId>} (RR-821). A run workspace lives
 * under {@code agent.workspace/<runId>} and is therefore NOT admissible today, so with the flags on
 * and a real run workspace, provisioning is refused and every routed command reports {@link
 * SandboxExecResult.Status#UNAVAILABLE}. That is the correct failure — a build that cannot be
 * contained does not run — but it means the flags are not yet operable end to end. Closing it needs
 * a change inside {@code sandbox/}, which this lane does not own; see the handoff note in the
 * remediation report.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SandboxCommandRoutingService {

  /** Fallback wall-clock when a caller supplies no usable timeout. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

  /** Extra lease headroom over the command's own timeout. */
  private static final Duration LEASE_HEADROOM = Duration.ofMinutes(2);

  /** Where the run workspace is mounted inside the sandbox. */
  public static final String CONTAINER_WORKDIR = "/workspace";

  /** Above this many cached leases, lapsed entries are swept. */
  private static final int MAX_CACHED_LEASES = 256;

  private final RunSandboxLifecycleService lifecycle;
  private final RunSandboxRuntimeDispatcher dispatcher;
  private final RunSandboxStore store;
  private final SandboxProperties properties;
  private final FeatureFlagService featureFlags;
  private final PipelineRunRepository pipelineRuns;

  private final Map<UUID, RunSandbox.Lease> heldLeases = new ConcurrentHashMap<>();

  /**
   * Whether build/test/lint execution must be routed into a sandbox right now.
   *
   * <p>Both flags are required and both are seeded false. Lifecycle admits the sandbox; dispatch
   * admits the command. With either off this returns false and every caller keeps its pre-existing
   * host path unchanged.
   */
  public boolean routingEnabled() {
    return featureFlags.isEnabled(RunSandboxLifecycleService.FLAG_LIFECYCLE)
        && dispatcher.dispatchEnabled();
  }

  /**
   * Runs one argument vector inside the sandbox belonging to {@code runId}.
   *
   * <p>Never throws for an ordinary failure and never executes on the host. Anything that prevents
   * the sandbox from running the command — routing switched off mid-flight, no run scope, no
   * workspace, a lost lease, a refused mount, an absent container engine — comes back as {@link
   * SandboxExecResult.Status#UNAVAILABLE} or {@link SandboxExecResult.Status#REFUSED}, both of
   * which report {@link SandboxExecResult#unexecuted()}.
   *
   * @param runId the run whose sandbox executes this; required, because a sandbox has an
   *     accountable owner and a scope
   * @param workspaceRoot the host workspace to expose at {@value #CONTAINER_WORKDIR}
   * @param argv the command as a vector; a shell is reachable only by naming it explicitly
   * @param timeout wall-clock ceiling, clamped to {@link SandboxExecRequest#MAX_TIMEOUT}
   */
  public SandboxExecResult execute(
      String runId, Path workspaceRoot, List<String> argv, Duration timeout) {
    if (!routingEnabled()) {
      // Defensive: a caller that reached here without asking must still not touch the host.
      return SandboxExecResult.unavailable("sandbox routing is off; nothing was executed");
    }
    if (runId == null || runId.isBlank()) {
      return SandboxExecResult.unavailable(
          "no run scope for this command; refusing to execute outside a sandbox");
    }
    if (workspaceRoot == null || !workspaceRoot.isAbsolute()) {
      return SandboxExecResult.unavailable("no absolute workspace root for run " + runId);
    }
    if (argv == null || argv.isEmpty()) {
      return SandboxExecResult.unavailable("refusing to execute an empty command vector");
    }

    Duration bounded = clamp(timeout);
    try {
      SandboxRuntimeSpec spec = specFor(workspaceRoot, bounded);
      RunSandbox sandbox = resolveSandbox(runId, spec);
      RunSandbox.Lease lease = leaseFor(sandbox, bounded);
      if (lease == null) {
        return SandboxExecResult.unavailable(
            "sandbox " + sandbox.id() + " is owned by another runtime");
      }
      RunSandbox current = store.get(sandbox.id()).orElse(sandbox);
      if (current.state() == SandboxState.REQUESTED
          || current.state() == SandboxState.PROVISIONING) {
        current = dispatcher.provision(current, lease, spec);
      }
      return dispatcher.exec(
          current, lease, new SandboxExecRequest(argv, CONTAINER_WORKDIR, bounded));
    } catch (RuntimeException failure) {
      // Logged, not swallowed: the caller still receives an explicit "did not execute" status and
      // must fail closed on it. Converting this to a host run is the defect being closed.
      log.error("sandbox routing failed for run {}; command was NOT executed", runId, failure);
      return SandboxExecResult.unavailable(
          "sandbox unavailable for run " + runId + ": " + failure.getMessage());
    }
  }

  /** Convenience for the callers whose command is genuinely a shell script. */
  public SandboxExecResult executeShell(
      String runId, Path workspaceRoot, String script, Duration timeout) {
    return execute(
        runId, workspaceRoot, List.of("sh", "-c", script == null ? "" : script), timeout);
  }

  /**
   * Merged stdout/stderr, matching what the host paths returned via {@code redirectErrorStream}.
   */
  public static String combinedOutput(SandboxExecResult result) {
    String out = result.stdout() == null ? "" : result.stdout();
    String err = result.stderr() == null ? "" : result.stderr();
    if (err.isEmpty()) {
      return out;
    }
    return out.isEmpty() ? err : out + "\n" + err;
  }

  private RunSandbox resolveSandbox(String runId, SandboxRuntimeSpec spec) {
    Optional<RunSandbox> active = store.findActiveForRun(runId);
    if (active.isPresent()) {
      return active.get();
    }
    PipelineRun run =
        pipelineRuns
            .findById(runId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "run " + runId + " has no persisted row; cannot admit a sandbox for it"));
    if (run.getCreatedByUserId() == null || run.getOrgId() == null) {
      // A sandbox without an accountable owner is not admissible; the column is NOT NULL for
      // exactly that reason and guessing a value here would forge the accountability.
      throw new IllegalStateException("run " + runId + " has no owner/org; cannot admit a sandbox");
    }
    return lifecycle.create(
        new RunSandboxLifecycleService.SandboxAdmission(
            runId,
            run.getProjectId(),
            null,
            run.getCreatedByUserId(),
            run.getOrgId(),
            "run:" + runId,
            "[]",
            "run-exec:" + runId + ":" + spec.configDigest()),
        spec);
  }

  /**
   * The live lease this JVM holds for the sandbox, re-acquiring when it lapsed and returning null
   * when another runtime owns it.
   */
  private RunSandbox.Lease leaseFor(RunSandbox sandbox, Duration commandTimeout) {
    Duration leaseDuration =
        max(properties.getLeaseDuration(), commandTimeout.plus(LEASE_HEADROOM));
    RunSandbox.Lease held = heldLeases.get(sandbox.id());
    if (held != null && store.heartbeat(held, leaseDuration)) {
      return held;
    }
    heldLeases.remove(sandbox.id());
    evictLapsedLeases();
    Optional<RunSandbox.Lease> acquired = store.tryAcquire(sandbox.id(), owner(), leaseDuration);
    acquired.ifPresent(lease -> heldLeases.put(sandbox.id(), lease));
    return acquired.orElse(null);
  }

  /**
   * Drops leases this JVM can no longer be holding. The cache is keyed by sandbox and a run's
   * sandbox is never revisited once it terminalises, so without this the map would grow for the
   * lifetime of the process.
   */
  private void evictLapsedLeases() {
    if (heldLeases.size() <= MAX_CACHED_LEASES) {
      return;
    }
    java.time.Instant now = java.time.Instant.now();
    heldLeases
        .values()
        .removeIf(lease -> lease.expiresAt() == null || lease.expiresAt().isBefore(now));
  }

  private SandboxRuntimeSpec specFor(Path workspaceRoot, Duration commandTimeout) {
    Duration wallClock = max(properties.getWallClock(), commandTimeout);
    return new SandboxRuntimeSpec(
        properties.getImage(),
        CONTAINER_WORKDIR,
        List.of(new SandboxRuntimeSpec.WritableMount(workspaceRoot.toString(), CONTAINER_WORKDIR)),
        new SandboxRuntimeSpec.SandboxRuntimeLimits(
            properties.getCpus(),
            properties.getMemoryBytes(),
            properties.getPids(),
            wallClock,
            properties.getTmpfsBytes()),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  private static String owner() {
    return "jvm:" + ProcessHandle.current().pid();
  }

  static Duration clamp(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      return DEFAULT_TIMEOUT;
    }
    return timeout.compareTo(SandboxExecRequest.MAX_TIMEOUT) > 0
        ? SandboxExecRequest.MAX_TIMEOUT
        : timeout;
  }

  private static Duration max(Duration a, Duration b) {
    return a.compareTo(b) >= 0 ? a : b;
  }
}
