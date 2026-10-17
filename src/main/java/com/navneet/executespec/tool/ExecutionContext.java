package com.navneet.executespec.tool;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.navneet.executespec.executioncontext.LlmExecutionOperationAuthority;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.sandboxroute.VerificationSandboxAuthority;
import com.navneet.executespec.workspacegeneration.BuildAcceptanceExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

/**
 * Per-task execution context passed to every tool invocation.
 *
 * <p>V2 is sequential — at any given moment, exactly one task is executing in a run, so the context
 * maps cleanly to a single workspace root. No worktrees, no per-task branches; tools operate
 * directly on the run's main workspace.
 *
 * @param runId the V2 run identifier
 * @param taskId the currently executing task's identifier (or {@code "__single_shot__"} for the
 *     SINGLE_SHOT classification)
 * @param workspaceRoot absolute path to the run's workspace root — all relative paths passed by the
 *     LLM are resolved against this
 * @param llmExecutionOperationAuthority optional process-local operation capability; never
 *     serialized, logged, or reconstructed from durable identity fields
 * @param workspaceGenerationDispatchAuthority optional process-local BUILD-generation capability;
 *     never serialized, logged, or reconstructed from durable identity fields
 * @param buildAcceptance immutable failure-first prompt/control/status evidence for this BUILD
 *     task; explicit execution context, never ambient thread state
 */
public record ExecutionContext(
    String runId,
    String taskId,
    Path workspaceRoot,
    String llmExecutionContextId,
    LlmExecutionScope llmExecutionScope,
    @JsonIgnore LlmExecutionOperationAuthority llmExecutionOperationAuthority,
    @JsonIgnore WorkspaceGenerationDispatchAuthority workspaceGenerationDispatchAuthority,
    @JsonIgnore VerificationSandboxAuthority verificationSandboxAuthority,
    @JsonIgnore BuildAcceptanceExecutionContext buildAcceptance) {

  /** Compatibility constructor retained for callers that bind the pre-acceptance authority set. */
  public ExecutionContext(
      String runId,
      String taskId,
      Path workspaceRoot,
      String llmExecutionContextId,
      LlmExecutionScope llmExecutionScope,
      LlmExecutionOperationAuthority llmExecutionOperationAuthority,
      WorkspaceGenerationDispatchAuthority workspaceGenerationDispatchAuthority,
      VerificationSandboxAuthority verificationSandboxAuthority) {
    this(
        runId,
        taskId,
        workspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        llmExecutionOperationAuthority,
        workspaceGenerationDispatchAuthority,
        verificationSandboxAuthority,
        null);
  }

  public ExecutionContext(String runId, String taskId, Path workspaceRoot) {
    this(runId, taskId, workspaceRoot, null, null, null, null, null, null);
  }

  /** Compatibility constructor for callers that carry only the durable context identity. */
  public ExecutionContext(
      String runId,
      String taskId,
      Path workspaceRoot,
      String llmExecutionContextId,
      LlmExecutionScope llmExecutionScope) {
    this(
        runId,
        taskId,
        workspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        null,
        null,
        null,
        null);
  }

  /** Compatibility constructor for tests/callers that bind only LLM operation authority. */
  public ExecutionContext(
      String runId,
      String taskId,
      Path workspaceRoot,
      String llmExecutionContextId,
      LlmExecutionScope llmExecutionScope,
      LlmExecutionOperationAuthority llmExecutionOperationAuthority) {
    this(
        runId,
        taskId,
        workspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        llmExecutionOperationAuthority,
        null,
        null,
        null);
  }

  public ExecutionContext(
      String runId, String taskId, Path workspaceRoot, LlmExecutionReference reference) {
    this(runId, taskId, workspaceRoot, reference, null);
  }

  /**
   * Creates a context carrying process-local operation authority.
   *
   * <p>The authority is deliberately accepted only alongside a durable reference. It cannot be
   * serialized and is never reconstructed from the reference.
   */
  public ExecutionContext(
      String runId,
      String taskId,
      Path workspaceRoot,
      LlmExecutionReference reference,
      LlmExecutionOperationAuthority llmExecutionOperationAuthority) {
    this(
        runId,
        taskId,
        workspaceRoot,
        reference == null ? null : reference.contextId(),
        reference == null ? null : reference.scope(),
        llmExecutionOperationAuthority,
        null,
        null,
        null);
  }

  public ExecutionContext {
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
    if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId required");
    if (workspaceRoot == null) throw new IllegalArgumentException("workspaceRoot required");
    if (!workspaceRoot.isAbsolute()) {
      throw new IllegalArgumentException("workspaceRoot must be absolute: " + workspaceRoot);
    }
    if ((llmExecutionContextId == null) != (llmExecutionScope == null)) {
      throw new IllegalArgumentException(
          "llmExecutionContextId and llmExecutionScope must be supplied together");
    }
    if (llmExecutionOperationAuthority != null && llmExecutionContextId == null) {
      throw new IllegalArgumentException(
          "llmExecutionOperationAuthority requires a durable execution reference");
    }
  }

  /** Binds an exact process-local BUILD capability without changing durable LLM authority. */
  public ExecutionContext withWorkspaceGenerationAuthority(
      WorkspaceGenerationDispatchAuthority authority) {
    return new ExecutionContext(
        runId,
        taskId,
        workspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        llmExecutionOperationAuthority,
        authority,
        verificationSandboxAuthority,
        buildAcceptance);
  }

  /**
   * Derives an execution view over a framework-owned disposable tree while preserving the exact
   * durable LLM and BUILD capabilities. Authority still fences the canonical generation; changing
   * the path does not create authority and is intentionally explicit at the call site.
   */
  public ExecutionContext withWorkspaceRoot(Path derivedWorkspaceRoot) {
    return new ExecutionContext(
        runId,
        taskId,
        derivedWorkspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        llmExecutionOperationAuthority,
        workspaceGenerationDispatchAuthority,
        verificationSandboxAuthority,
        buildAcceptance);
  }

  /** Binds a framework-minted physical verification capability and its exact owned workspace. */
  public ExecutionContext withVerificationSandboxAuthority(VerificationSandboxAuthority authority) {
    if (authority == null
        || !runId.equals(authority.runId())
        || !workspaceRoot.toAbsolutePath().normalize().equals(authority.workspaceRoot())) {
      throw new IllegalArgumentException("verification sandbox authority does not match context");
    }
    return new ExecutionContext(
        runId,
        taskId,
        workspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        llmExecutionOperationAuthority,
        workspaceGenerationDispatchAuthority,
        authority,
        buildAcceptance);
  }

  /** Carries immutable acceptance semantics across thread/executor boundaries. */
  public ExecutionContext withBuildAcceptance(BuildAcceptanceExecutionContext acceptance) {
    if (acceptance == null) {
      throw new IllegalArgumentException("build acceptance context is required");
    }
    return new ExecutionContext(
        runId,
        taskId,
        workspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        llmExecutionOperationAuthority,
        workspaceGenerationDispatchAuthority,
        verificationSandboxAuthority,
        acceptance);
  }

  public void assertVerificationSandboxLive() {
    if (verificationSandboxAuthority != null) verificationSandboxAuthority.assertLive();
  }

  public void assertWorkspaceGenerationDispatch(
      WorkspaceGenerationDispatchAuthority.Phase requiredPhase) {
    if (workspaceGenerationDispatchAuthority == null) {
      throw new IllegalStateException("WORKSPACE_GENERATION_AUTHORITY_REQUIRED");
    }
    workspaceGenerationDispatchAuthority.assertDispatch(runId, taskId, requiredPhase);
  }

  /** Preserve the durable tenant boundary while deriving a per-task tool context. */
  public ExecutionContext child(String childTaskId) {
    return new ExecutionContext(
        runId,
        childTaskId,
        workspaceRoot,
        llmExecutionContextId,
        llmExecutionScope,
        llmExecutionOperationAuthority,
        workspaceGenerationDispatchAuthority,
        verificationSandboxAuthority,
        buildAcceptance);
  }

  /**
   * Omits process-local authority from logs. Its own representation is redacted as a second line of
   * defence, but callers should not learn even whether a serialized/logged context held authority.
   */
  @Override
  public String toString() {
    return "ExecutionContext[runId="
        + runId
        + ", taskId="
        + taskId
        + ", workspaceRoot="
        + workspaceRoot
        + ", llmExecutionContextId="
        + llmExecutionContextId
        + ", llmExecutionScope="
        + llmExecutionScope
        + "]";
  }
}
