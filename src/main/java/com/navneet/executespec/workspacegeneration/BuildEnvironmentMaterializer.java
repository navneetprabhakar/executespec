package com.navneet.executespec.workspacegeneration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.tool.ExecutionContext;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Materializes ignored dependencies without permitting them to alter product generation. */
@Service
@RequiredArgsConstructor
public class BuildEnvironmentMaterializer {

  private final BuildEnvironmentPlanResolver plans;
  private final SandboxCommandRoutingService sandboxRouting;
  private final CorrectionWorkspaceTransaction generations;
  private final BuildEnvironmentReceiptStore receipts;
  private final ObjectMapper objectMapper;

  public Receipt materialize(
      ExecutionContext context, UUID attemptId, Phase phase, Runnable authorityFence) {
    if (context == null || attemptId == null || phase == null || authorityFence == null) {
      throw new IllegalArgumentException("BUILD_ENVIRONMENT_INPUT_REQUIRED");
    }
    authorityFence.run();
    BuildEnvironmentPlan plan = plans.resolve(context.workspaceRoot());
    String generationBefore = generations.captureGeneration(context.workspaceRoot());
    List<Map<String, Object>> executions = new ArrayList<>();
    Disposition disposition = Disposition.PASSED;
    String failureCode = null;
    for (BuildEnvironmentPlan.Module module : plan.modules()) {
      authorityFence.run();
      SandboxExecResult result;
      try {
        result = execute(context, module);
      } catch (RuntimeException internalFailure) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("moduleRoot", module.repositoryRelativeRoot());
        execution.put("ecosystem", module.ecosystem());
        execution.put("commandDigest", commandDigest(module));
        execution.put("status", "INTERNAL_PROTOCOL");
        execution.put("exitCode", null);
        execution.put("durationMillis", 0);
        executions.add(execution);
        disposition = Disposition.INTERNAL_PROTOCOL;
        failureCode = "BUILD_ENVIRONMENT_INTERNAL_PROTOCOL";
        break;
      }
      Map<String, Object> execution = new LinkedHashMap<>();
      execution.put("moduleRoot", module.repositoryRelativeRoot());
      execution.put("ecosystem", module.ecosystem());
      execution.put("commandDigest", commandDigest(module));
      execution.put("status", result.status().name());
      execution.put("exitCode", result.completed() ? result.exitCode() : null);
      execution.put("durationMillis", result.duration() == null ? 0 : result.duration().toMillis());
      executions.add(execution);
      if (!result.succeeded()) {
        disposition = disposition(result);
        failureCode = code(result);
        break;
      }
    }
    authorityFence.run();
    String generationAfter = generations.captureGeneration(context.workspaceRoot());
    if (!generationBefore.equals(generationAfter)) {
      disposition = Disposition.PRODUCT_MUTATION_REFUSED;
      failureCode = "BUILD_ENVIRONMENT_CHANGED_PRODUCT_GENERATION";
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", 1);
    evidence.put("phase", phase.name());
    evidence.put("planDigest", plan.planDigest());
    evidence.put("manifestDigest", plan.manifestDigest());
    evidence.put("runtimeImage", plan.runtimeImage());
    evidence.put("networkPolicy", plan.networkPolicy());
    evidence.put("cachePolicy", plan.cachePolicy());
    evidence.put("generationBefore", generationBefore);
    evidence.put("generationAfter", generationAfter);
    evidence.put("productGenerationUnchanged", generationBefore.equals(generationAfter));
    evidence.put("executions", executions);
    evidence.put("failureCode", failureCode);
    String evidenceJson = json(evidence);
    String evidenceDigest = DigestUtils.sha256Hex(evidenceJson);
    receipts.recordMaterialization(
        attemptId, phase.name(), plan, disposition.name(), evidenceJson, evidenceDigest);
    Receipt receipt =
        new Receipt(
            phase,
            plan.planDigest(),
            plan.manifestDigest(),
            disposition,
            generationBefore,
            generationAfter,
            failureCode,
            evidenceDigest);
    if (disposition != Disposition.PASSED) {
      throw new BuildEnvironmentException(receipt);
    }
    return receipt;
  }

  private SandboxExecResult execute(ExecutionContext context, BuildEnvironmentPlan.Module module) {
    String script =
        "cd -- "
            + shellQuote(module.repositoryRelativeRoot())
            + " && exec "
            + module.argv().stream()
                .map(BuildEnvironmentMaterializer::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
    Duration timeout = Duration.ofSeconds(module.timeoutSeconds());
    if (context.verificationSandboxAuthority() != null) {
      return sandboxRouting.executeVerificationShell(
          context.verificationSandboxAuthority(), script, timeout);
    }
    return sandboxRouting.executeShell(context.runId(), context.workspaceRoot(), script, timeout);
  }

  private static String commandDigest(BuildEnvironmentPlan.Module module) {
    return DigestUtils.sha256Hex(
        module.repositoryRelativeRoot() + "\n" + String.join("\u0000", module.argv()));
  }

  private static Disposition disposition(SandboxExecResult result) {
    return switch (result.status()) {
      case COMPLETED -> Disposition.COMMAND_FAILED;
      case TIMED_OUT -> Disposition.TIMED_OUT;
      case UNAVAILABLE -> Disposition.UNAVAILABLE;
      case REFUSED -> Disposition.POLICY_DENIED;
    };
  }

  private static String code(SandboxExecResult result) {
    return switch (result.status()) {
      case COMPLETED -> "BUILD_ENVIRONMENT_COMMAND_FAILED";
      case TIMED_OUT -> "BUILD_ENVIRONMENT_TIMED_OUT";
      case UNAVAILABLE -> "BUILD_ENVIRONMENT_RUNTIME_UNAVAILABLE";
      case REFUSED -> "BUILD_ENVIRONMENT_POLICY_DENIED";
    };
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_EVIDENCE_SERIALIZATION_FAILED", failure);
    }
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  public enum Phase {
    WORKER,
    INITIAL_PROBE,
    FINAL_VERIFICATION
  }

  public enum Disposition {
    PASSED,
    COMMAND_FAILED,
    UNAVAILABLE,
    POLICY_DENIED,
    TIMED_OUT,
    PRODUCT_MUTATION_REFUSED,
    INTERNAL_PROTOCOL
  }

  public record Receipt(
      Phase phase,
      String planDigest,
      String manifestDigest,
      Disposition disposition,
      String generationBefore,
      String generationAfter,
      String failureCode,
      String evidenceDigest) {}

  public static final class BuildEnvironmentException extends IllegalStateException {
    private final Receipt receipt;

    BuildEnvironmentException(Receipt receipt) {
      super(receipt.failureCode());
      this.receipt = receipt;
    }

    public Receipt receipt() {
      return receipt;
    }
  }
}
