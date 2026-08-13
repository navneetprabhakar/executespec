package com.navneet.executespec.sandboxroute;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

/**
 * Process-local capability for one exact-generation verification sandbox.
 *
 * <p>The constructor is package-private: durable ids and paths are evidence, not authority. Only
 * {@link SandboxCommandRoutingService} can mint an instance, and it validates object identity
 * against its live fenced session before every command.
 */
public class VerificationSandboxAuthority {

  private final UUID capabilityId;
  private final UUID sandboxId;
  private final String runId;
  private final UUID attemptId;
  private final String generation;
  private final WorkspaceGenerationDispatchAuthority.Phase phase;
  private final Path workspaceRoot;
  private final AtomicLong commandSequence = new AtomicLong();

  VerificationSandboxAuthority(
      UUID capabilityId,
      UUID sandboxId,
      String runId,
      UUID attemptId,
      String generation,
      WorkspaceGenerationDispatchAuthority.Phase phase,
      Path workspaceRoot) {
    this.capabilityId = capabilityId;
    this.sandboxId = sandboxId;
    this.runId = runId;
    this.attemptId = attemptId;
    this.generation = generation;
    this.phase = phase;
    this.workspaceRoot = workspaceRoot;
  }

  VerificationSandboxAuthority(
      UUID capabilityId,
      UUID sandboxId,
      String runId,
      String generation,
      WorkspaceGenerationDispatchAuthority.Phase phase,
      Path workspaceRoot) {
    this(capabilityId, sandboxId, runId, null, generation, phase, workspaceRoot);
  }

  UUID capabilityId() {
    return capabilityId;
  }

  public UUID sandboxId() {
    return sandboxId;
  }

  public String runId() {
    return runId;
  }

  public UUID attemptId() {
    return attemptId;
  }

  public String generation() {
    return generation;
  }

  public WorkspaceGenerationDispatchAuthority.Phase phase() {
    return phase;
  }

  public Path workspaceRoot() {
    return workspaceRoot;
  }

  String nextCommandIdempotencyKey() {
    return "verify-command:"
        + sandboxId
        + ":"
        + capabilityId
        + ":"
        + commandSequence.incrementAndGet();
  }

  @Override
  public String toString() {
    return "VerificationSandboxAuthority[REDACTED]";
  }
}
