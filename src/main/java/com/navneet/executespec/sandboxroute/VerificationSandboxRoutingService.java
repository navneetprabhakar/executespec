package com.navneet.executespec.sandboxroute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.navneet.executespec.sandbox.RunSandbox;
import com.navneet.executespec.sandbox.RunSandboxLifecycleService;
import com.navneet.executespec.sandbox.RunSandboxRuntimeDispatcher;
import com.navneet.executespec.sandbox.RunSandboxStore;
import com.navneet.executespec.sandbox.SandboxCommandJournalStore;
import com.navneet.executespec.sandbox.SandboxCommandJournalStore.Admission;
import com.navneet.executespec.sandbox.SandboxCommandJournalStore.AdmissionRequest;
import com.navneet.executespec.sandbox.SandboxCommandJournalStore.TerminalOutcome;
import com.navneet.executespec.sandbox.SandboxExecRequest;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandbox.SandboxNetworkPolicy;
import com.navneet.executespec.sandbox.SandboxProperties;
import com.navneet.executespec.sandbox.SandboxPurpose;
import com.navneet.executespec.sandbox.SandboxRuntimeSpec;
import com.navneet.executespec.sandbox.SandboxSecretPolicy;
import com.navneet.executespec.sandbox.SandboxState;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

import lombok.extern.slf4j.Slf4j;

/** Capability registry and physical runtime router for disposable verification sandboxes. */
@Slf4j
final class VerificationSandboxRoutingService {

  private static final Duration LEASE_HEADROOM = Duration.ofMinutes(2);
  private static final String OWNER =
      "verification-jvm:" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();

  private final RunSandboxLifecycleService lifecycle;
  private final RunSandboxRuntimeDispatcher dispatcher;
  private final RunSandboxStore store;
  private final SandboxProperties properties;
  private final PipelineRunRepository pipelineRuns;
  private final SandboxCommandJournalStore commandJournal;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  VerificationSandboxRoutingService(
      RunSandboxLifecycleService lifecycle,
      RunSandboxRuntimeDispatcher dispatcher,
      RunSandboxStore store,
      SandboxProperties properties,
      PipelineRunRepository pipelineRuns,
      SandboxCommandJournalStore commandJournal) {
    this.lifecycle = lifecycle;
    this.dispatcher = dispatcher;
    this.store = store;
    this.properties = properties;
    this.pipelineRuns = pipelineRuns;
    this.commandJournal = commandJournal;
  }

  VerificationSandboxAuthority open(
      String runId, String generation, WorkspaceGenerationDispatchAuthority.Phase phase) {
    return open(runId, null, generation, phase);
  }

  VerificationSandboxAuthority open(
      String runId,
      UUID attemptId,
      String generation,
      WorkspaceGenerationDispatchAuthority.Phase phase) {
    if (runId == null || runId.isBlank() || generation == null || generation.isBlank()) {
      throw failure("VERIFICATION_SANDBOX_IDENTITY_REQUIRED");
    }
    if (phase != WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION) {
      throw failure("VERIFICATION_SANDBOX_PHASE_REFUSED");
    }
    if (store.findActiveForRun(runId, SandboxPurpose.VERIFICATION).isPresent()) {
      throw failure("VERIFICATION_SANDBOX_ALREADY_ACTIVE");
    }
    PipelineRun run = requireAdmissibleRun(runId);
    UUID nonce = UUID.randomUUID();
    RunSandbox sandbox =
        lifecycle.create(
            new RunSandboxLifecycleService.SandboxAdmission(
                runId,
                run.getProjectId(),
                null,
                run.getCreatedByUserId(),
                run.getOrgId(),
                generation,
                "[]",
                SandboxPurpose.VERIFICATION,
                "verify-exec:" + runId + ":" + phase + ":" + nonce),
            specFor(Path.of("/verification-owned-at-provision"), Duration.ofMinutes(10)));
    Duration leaseDuration = max(properties.getLeaseDuration(), LEASE_HEADROOM);
    RunSandbox.Lease lease = store.tryAcquire(sandbox.id(), OWNER, leaseDuration).orElse(null);
    if (lease == null) {
      lifecycle.cancel(sandbox.id());
      throw failure("VERIFICATION_SANDBOX_LEASE_UNAVAILABLE");
    }
    Path workspace = workspace(sandbox.id());
    try {
      Files.createDirectories(workspace);
    } catch (IOException cause) {
      lifecycle.cancel(sandbox.id());
      throw new SandboxCommandRoutingService.VerificationSandboxException(
          "VERIFICATION_SANDBOX_WORKSPACE_CREATE_FAILED", cause);
    }
    VerificationSandboxAuthority authority =
        new VerificationSandboxAuthority(
            nonce, sandbox.id(), runId, attemptId, generation, phase, workspace, this::assertLive);
    sessions.put(nonce, new Session(authority, sandbox, lease, leaseDuration));
    return authority;
  }

  void activate(VerificationSandboxAuthority authority, String materializedGeneration) {
    Session session = requireSession(authority);
    assertLive(authority);
    if (!authority.generation().equals(materializedGeneration)) {
      throw failure("VERIFICATION_SANDBOX_GENERATION_MISMATCH");
    }
    if (!Files.isDirectory(authority.workspaceRoot())) {
      throw failure("VERIFICATION_SANDBOX_WORKSPACE_MISSING");
    }
    session.active = true;
  }

  SandboxExecResult execute(
      VerificationSandboxAuthority authority, List<String> argv, Duration timeout) {
    if (argv == null || argv.isEmpty()) {
      return SandboxExecResult.refused("VERIFICATION_COMMAND_REQUIRED");
    }
    Session session;
    try {
      session = requireSession(authority);
    } catch (SandboxCommandRoutingService.VerificationSandboxException refused) {
      return SandboxExecResult.refused(refused.getMessage());
    }
    if (!session.active) {
      return SandboxExecResult.refused("VERIFICATION_SANDBOX_NOT_ACTIVATED");
    }
    Duration bounded = SandboxCommandRoutingService.clamp(timeout);
    Admission admission = null;
    boolean dispatched = false;
    String beforeSnapshot = null;
    RunSandbox.Lease lease = null;
    try {
      lease = leaseFor(session, bounded);
      RunSandbox current = store.get(session.sandbox.id()).orElse(session.sandbox);
      if (current.purpose() != SandboxPurpose.VERIFICATION) {
        return SandboxExecResult.refused("VERIFICATION_SANDBOX_PURPOSE_MISMATCH");
      }
      if (current.state() == SandboxState.REQUESTED
          || current.state() == SandboxState.PROVISIONING) {
        current = dispatcher.provision(current, lease, specFor(authority.workspaceRoot(), bounded));
      }
      beforeSnapshot = VerificationWorkspaceDigester.digest(authority.workspaceRoot());
      admission =
          commandJournal.admit(
              new AdmissionRequest(
                  authority.runId(),
                  authority.attemptId(),
                  authority.generation(),
                  SandboxCommandJournalStore.mountDigest(
                      authority.workspaceRoot().toAbsolutePath().normalize().toString(),
                      SandboxCommandRoutingService.CONTAINER_WORKDIR),
                  commandClass(argv),
                  SandboxCommandJournalStore.argvDigest(argv),
                  bounded,
                  authority.nextCommandIdempotencyKey(),
                  beforeSnapshot),
              current,
              lease,
              current.runtimeEpoch());
      commandJournal.markDispatched(admission, lease);
      dispatched = true;
      SandboxExecResult result =
          dispatcher.exec(
              current,
              lease,
              new SandboxExecRequest(
                  List.copyOf(argv), SandboxCommandRoutingService.CONTAINER_WORKDIR, bounded));
      String afterSnapshot = VerificationWorkspaceDigester.digest(authority.workspaceRoot());
      UUID receiptId =
          commandJournal.settle(
              admission, lease, outcome(result), result, afterSnapshot, result.detail());
      session.commandReceiptIds.add(receiptId);
      if (result.status() == SandboxExecResult.Status.TIMED_OUT) {
        dispatcher.failAndRelease(
            current, lease, "VERIFICATION_COMMAND_TIMEOUT", "command exceeded " + bounded);
        session.active = false;
        session.failure = failure("VERIFICATION_SANDBOX_COMMAND_TIMED_OUT");
      }
      return result;
    } catch (RuntimeException cause) {
      if (admission != null && lease != null) {
        try {
          SandboxExecResult unexecuted =
              SandboxExecResult.unavailable(
                  "verification dispatch failed before a trustworthy outcome");
          UUID receiptId =
              commandJournal.settle(
                  admission,
                  lease,
                  dispatched ? TerminalOutcome.INDETERMINATE : TerminalOutcome.UNAVAILABLE,
                  dispatched ? null : unexecuted,
                  dispatched ? null : beforeSnapshot,
                  "verification dispatch outcome unknown: " + cause.getClass().getSimpleName());
          session.commandReceiptIds.add(receiptId);
        } catch (RuntimeException settlementFailure) {
          cause.addSuppressed(settlementFailure);
        }
      }
      log.error(
          "verification sandbox execution failed for run {}; fallback forbidden",
          authority.runId(),
          cause);
      return SandboxExecResult.unavailable(
          "verification sandbox unavailable: " + cause.getMessage());
    }
  }

  List<UUID> commandReceiptIds(VerificationSandboxAuthority authority) {
    return List.copyOf(requireSession(authority).commandReceiptIds);
  }

  void close(VerificationSandboxAuthority authority) {
    Session session = sessions.get(authority.capabilityId());
    if (session == null || session.authority != authority) return;
    synchronized (session) {
      if (session.closed) return;
      session.closed = true;
      sessions.remove(authority.capabilityId(), session);
      RunSandbox current = store.get(session.sandbox.id()).orElse(session.sandbox);
      dispatcher.release(current, session.lease, SandboxState.TERMINATED);
    }
  }

  /** Covers materialization, model calls and reads, not only command execution. */
  void renewSessions() {
    for (Session session : sessions.values()) {
      synchronized (session) {
        if (session.closed || session.failure != null) continue;
        try {
          renew(session);
        } catch (RuntimeException failure) {
          session.failure = failure;
          log.warn("verification session lease lost for sandbox {}", session.sandbox.id(), failure);
        }
      }
    }
  }

  private void assertLive(VerificationSandboxAuthority authority) {
    Session session = requireSession(authority);
    synchronized (session) {
      if (session.closed) throw failure("VERIFICATION_SANDBOX_AUTHORITY_STALE");
      if (session.failure != null) throw session.failure;
      try {
        renew(session);
      } catch (RuntimeException failure) {
        session.failure = failure;
        throw failure;
      }
    }
  }

  private void renew(Session session) {
    if ((session.sandbox.expiresAt() != null
            && !session.sandbox.expiresAt().isAfter(java.time.Instant.now()))
        || store.cancellationRequested(session.lease)
        || !store.heartbeat(session.lease, session.leaseDuration)) {
      throw failure("VERIFICATION_SANDBOX_LEASE_LOST");
    }
  }

  private Session requireSession(VerificationSandboxAuthority authority) {
    if (authority == null) throw failure("VERIFICATION_SANDBOX_AUTHORITY_REQUIRED");
    Session session = sessions.get(authority.capabilityId());
    if (session == null || session.authority != authority) {
      throw failure("VERIFICATION_SANDBOX_AUTHORITY_STALE");
    }
    return session;
  }

  private PipelineRun requireAdmissibleRun(String runId) {
    PipelineRun run =
        pipelineRuns
            .findById(runId)
            .orElseThrow(() -> new IllegalStateException("run " + runId + " is not persisted"));
    if (run.getCreatedByUserId() == null || run.getOrgId() == null) {
      throw new IllegalStateException("run " + runId + " has no owner/org");
    }
    return run;
  }

  private RunSandbox.Lease leaseFor(Session session, Duration timeout) {
    synchronized (session) {
      session.leaseDuration = max(session.leaseDuration, timeout.plus(LEASE_HEADROOM));
      assertLive(session.authority);
      return session.lease;
    }
  }

  private SandboxRuntimeSpec specFor(Path root, Duration timeout) {
    return new SandboxRuntimeSpec(
        properties.getImage(),
        SandboxCommandRoutingService.CONTAINER_WORKDIR,
        List.of(
            new SandboxRuntimeSpec.WritableMount(
                root.toString(), SandboxCommandRoutingService.CONTAINER_WORKDIR)),
        new SandboxRuntimeSpec.SandboxRuntimeLimits(
            properties.getCpus(),
            properties.getMemoryBytes(),
            properties.getPids(),
            max(properties.getWallClock(), timeout),
            properties.getTmpfsBytes()),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  private Path workspace(UUID sandboxId) {
    Path root = Path.of(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
    if (root.getNameCount() == 0) throw failure("VERIFICATION_SANDBOX_ROOT_REFUSED");
    return root.resolve(sandboxId.toString()).resolve("workspace").normalize();
  }

  private static Duration max(Duration left, Duration right) {
    return left.compareTo(right) >= 0 ? left : right;
  }

  private static SandboxCommandRoutingService.VerificationSandboxException failure(String code) {
    return new SandboxCommandRoutingService.VerificationSandboxException(code);
  }

  private static String commandClass(List<String> argv) {
    String executable = argv.getFirst().toLowerCase(java.util.Locale.ROOT);
    if (executable.endsWith("mvn")
        || executable.endsWith("gradle")
        || executable.endsWith("gradlew")) {
      return "JVM_BUILD_OR_TEST";
    }
    if (executable.endsWith("npm") || executable.endsWith("pnpm") || executable.endsWith("yarn")) {
      return "NODE_BUILD_OR_TEST";
    }
    if (executable.endsWith("pytest")
        || executable.endsWith("python")
        || executable.endsWith("python3")) {
      return "PYTHON_BUILD_OR_TEST";
    }
    if (executable.endsWith("sh") || executable.endsWith("bash")) return "SHELL_CONTRACT";
    return "VERIFICATION_COMMAND";
  }

  private static TerminalOutcome outcome(SandboxExecResult result) {
    return switch (result.status()) {
      case COMPLETED -> result.exitCode() == 0 ? TerminalOutcome.SUCCEEDED : TerminalOutcome.FAILED;
      case TIMED_OUT -> TerminalOutcome.TIMED_OUT;
      case REFUSED -> TerminalOutcome.REFUSED;
      case UNAVAILABLE -> TerminalOutcome.UNAVAILABLE;
    };
  }

  private static final class Session {
    private final VerificationSandboxAuthority authority;
    private final RunSandbox sandbox;
    private final List<UUID> commandReceiptIds = new CopyOnWriteArrayList<>();
    private final RunSandbox.Lease lease;
    private Duration leaseDuration;
    private volatile RuntimeException failure;
    private boolean closed;
    private volatile boolean active;

    private Session(
        VerificationSandboxAuthority authority,
        RunSandbox sandbox,
        RunSandbox.Lease lease,
        Duration leaseDuration) {
      this.authority = authority;
      this.sandbox = sandbox;
      this.lease = lease;
      this.leaseDuration = leaseDuration;
    }
  }
}
