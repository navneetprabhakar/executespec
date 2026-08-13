package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns disposable-environment admission and evidence decoration for task acceptance. */
@Component
public class TaskAcceptanceDisposableRunner {

  private final DisposableVerificationWorkspaceService disposableWorkspaces;
  private final BuildEnvironmentMaterializer environments;
  private final ObjectMapper objectMapper;

  public TaskAcceptanceDisposableRunner(
      DisposableVerificationWorkspaceService disposableWorkspaces, ObjectMapper objectMapper) {
    this(disposableWorkspaces, null, objectMapper);
  }

  @Autowired
  public TaskAcceptanceDisposableRunner(
      DisposableVerificationWorkspaceService disposableWorkspaces,
      BuildEnvironmentMaterializer environments,
      ObjectMapper objectMapper) {
    this.disposableWorkspaces = disposableWorkspaces;
    this.environments = environments;
    this.objectMapper = objectMapper;
  }

  TaskAcceptanceVerifier.InitialFailure probe(
      ExecutionContext context,
      Function<ExecutionContext, TaskAcceptanceVerifier.InitialFailure> verifier) {
    try {
      return disposableWorkspaces
          .execute(
              context,
              WorkspaceGenerationDispatchAuthority.Phase.MUTATION,
              disposable -> {
                materialize(
                    context,
                    disposable,
                    BuildEnvironmentMaterializer.Phase.INITIAL_PROBE,
                    WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
                return verifier.apply(disposable);
              })
          .value();
    } catch (DisposableVerificationWorkspaceService.DisposableVerificationException failure) {
      return new TaskAcceptanceVerifier.InitialFailure(
          "UNAVAILABLE",
          "INITIAL_ENVIRONMENT_" + failure.kind().name(),
          failure.getMessage(),
          null,
          List.of());
    } catch (BuildEnvironmentMaterializer.BuildEnvironmentException failure) {
      return new TaskAcceptanceVerifier.InitialFailure(
          "UNAVAILABLE",
          failure.receipt().failureCode(),
          "The frozen offline build environment could not be materialized",
          null,
          List.of());
    }
  }

  WorkspaceGenerationLifecycle.Verification verify(
      ExecutionContext context,
      TaskAcceptanceContract contract,
      Function<ExecutionContext, WorkspaceGenerationLifecycle.Verification> verifier) {
    Instant started = Instant.now();
    try {
      DisposableVerificationWorkspaceService.Execution<WorkspaceGenerationLifecycle.Verification>
          execution =
              disposableWorkspaces.execute(
                  context,
                  WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
                  disposable -> {
                    materialize(
                        context,
                        disposable,
                        BuildEnvironmentMaterializer.Phase.FINAL_VERIFICATION,
                        WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION);
                    return verifier.apply(disposable);
                  });
      return withDisposableEvidence(execution.value(), execution);
    } catch (DisposableVerificationWorkspaceService.DisposableVerificationException failure) {
      return unavailableVerification(
          contract,
          started,
          failure.kind().name(),
          failure.getMessage(),
          "DISPOSABLE_" + failure.kind().name(),
          "ENVIRONMENT");
    } catch (BuildEnvironmentMaterializer.BuildEnvironmentException failure) {
      return unavailableVerification(
          contract,
          started,
          "BUILD_ENVIRONMENT",
          failure.receipt().failureCode(),
          failure.receipt().failureCode(),
          failure.receipt().disposition()
                  == BuildEnvironmentMaterializer.Disposition.INTERNAL_PROTOCOL
              ? "INTERNAL_PROTOCOL"
              : "ENVIRONMENT");
    }
  }

  private WorkspaceGenerationLifecycle.Verification unavailableVerification(
      TaskAcceptanceContract contract,
      Instant started,
      String failureKind,
      String failureCode,
      String environmentCode,
      String failureCategory) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("schemaVersion", contract.schemaVersion());
    command.put("commandClass", "BROAD_VERIFICATION");
    command.put("executionBoundary", "DISPOSABLE_EXACT_GENERATION");
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", contract.schemaVersion());
    evidence.put("environmentFailureKind", failureKind);
    evidence.put("environmentFailureCode", failureCode);
    evidence.put("productVerificationFailure", false);
    evidence.put("canonicalFallbackUsed", false);
    return new WorkspaceGenerationLifecycle.Verification(
        "BUILD_ONLY",
        json(command),
        "UNAVAILABLE",
        failureCategory,
        environmentCode,
        "DISPOSABLE_EXACT_GENERATION",
        false,
        false,
        0,
        0,
        0,
        0,
        json(evidence),
        contract.requiredProofStrength().name(),
        "NONE",
        started,
        Instant.now());
  }

  private void materialize(
      ExecutionContext canonical,
      ExecutionContext disposable,
      BuildEnvironmentMaterializer.Phase materializationPhase,
      WorkspaceGenerationDispatchAuthority.Phase authorityPhase) {
    if (environments == null) return; // compatibility for isolated pre-R6 unit constructions
    environments.materialize(
        disposable,
        attemptId(canonical),
        materializationPhase,
        () -> canonical.assertWorkspaceGenerationDispatch(authorityPhase));
  }

  private static java.util.UUID attemptId(ExecutionContext context) {
    if (context.workspaceGenerationDispatchAuthority() == null) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_ATTEMPT_AUTHORITY_REQUIRED");
    }
    return context.workspaceGenerationDispatchAuthority().attemptId();
  }

  private WorkspaceGenerationLifecycle.Verification withDisposableEvidence(
      WorkspaceGenerationLifecycle.Verification verification,
      DisposableVerificationWorkspaceService.Execution<?> execution) {
    try {
      ObjectNode evidence = (ObjectNode) objectMapper.readTree(verification.evidenceJson());
      evidence.put("canonicalGenerationBefore", execution.canonicalGenerationBefore());
      evidence.put("disposableGenerationBefore", execution.disposableGenerationBefore());
      evidence.put("sourceEntries", execution.sourceEntries());
      evidence.put("sourceBytes", execution.sourceBytes());
      evidence.put("disposableSnapshotBeforeSha256", execution.disposableSnapshotBeforeSha256());
      evidence.put("disposableSnapshotAfterSha256", execution.disposableSnapshotAfterSha256());
      evidence.put("commandSideEffectEntryCount", execution.sideEffectEntryCount());
      evidence.set("commandReceiptIds", objectMapper.valueToTree(execution.commandReceiptIds()));
      evidence.put("disposableDurationMillis", execution.duration().toMillis());
      evidence.put("cleanupRequired", execution.cleanupRequired());
      evidence.put("canonicalGenerationUnchanged", true);
      return new WorkspaceGenerationLifecycle.Verification(
          verification.verifierKind(),
          verification.serverResolvedCommandContract(),
          verification.outcome(),
          verification.failureCategory(),
          verification.environmentCode(),
          verification.executionBoundary(),
          verification.buildPassed(),
          verification.testsExecuted(),
          verification.testsPassed(),
          verification.testsFailed(),
          verification.testsErrors(),
          verification.testsSkipped(),
          objectMapper.writeValueAsString(evidence),
          verification.requiredProofStrength(),
          verification.achievedProofStrength(),
          verification.startedAt(),
          verification.completedAt());
    } catch (JsonProcessingException malformed) {
      throw new IllegalStateException("DISPOSABLE_VERIFICATION_EVIDENCE_INVALID", malformed);
    }
  }

  private String json(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("VERIFICATION_EVIDENCE_SERIALIZATION_FAILED", failure);
    }
  }
}
