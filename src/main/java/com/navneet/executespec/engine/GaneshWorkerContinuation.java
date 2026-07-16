package com.navneet.executespec.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.RunKeyStore;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.smoketest.ExecutableVerification;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** One fail-closed Ganesh worker route and its sole E2-triggered frontier continuation. */
@Component
@RequiredArgsConstructor
public class GaneshWorkerContinuation {

  public static final String FLAG = "worker.ganesh.enabled";
  public static final String PROVIDER = "ganesh";

  private static final Map<String, String> MODELS =
      Map.of(
          "py", "ganesh-python",
          "go", "ganesh-go",
          "ts", "ganesh-typescript",
          "js", "ganesh-javascript",
          "java", "ganesh-java");

  private final FeatureFlagService flags;
  private final RunKeyStore runKeyStore;
  private final WorkspaceTestRunner verifier;
  private final SseBroadcaster broadcaster;
  private final GaneshWorkerAttemptRepository attemptRepository;
  private final ObjectMapper mapper;

  @Value("${spring.ai.ganesh.base-url:}")
  private String endpoint;

  @Value("${spring.ai.ganesh.api-key:}")
  private String platformToken;

  @FunctionalInterface
  public interface AttemptRunner {
    WorkerResult run(String provider, String model, ExecutableVerification previousEvidence);
  }

  public record Request(
      String runId,
      String taskId,
      Path workspace,
      String mode,
      String requestedProvider,
      String requestedModel,
      List<String> targetFiles,
      boolean requireConfirmationBeforeExpensiveContinuation) {}

  public record Result(
      WorkerResult workerResult,
      String provider,
      String model,
      ExecutableVerification verification,
      boolean ganeshRouted,
      boolean frontierRetried) {
    public boolean accepted() {
      return !ganeshRouted || (verification != null && verification.accepted());
    }
  }

  public Result execute(Request request, AttemptRunner runner) {
    if (PROVIDER.equalsIgnoreCase(request.requestedProvider())) {
      throw new IllegalStateException(
          "Ganesh is worker-only; select a frontier run provider and allow worker routing by"
              + " policy");
    }
    String ganeshModel = eligibleModel(request);
    if (ganeshModel == null) {
      WorkerResult result = runner.run(request.requestedProvider(), request.requestedModel(), null);
      return new Result(
          result, request.requestedProvider(), request.requestedModel(), null, false, false);
    }

    WorkerResult initial = runner.run(PROVIDER, ganeshModel, null);
    ExecutableVerification first = verifier.verify(request.runId(), request.workspace());
    retain(request, 1, PROVIDER, ganeshModel, first, false);
    if (first.accepted()) {
      return new Result(initial, PROVIDER, ganeshModel, first, true, false);
    }
    if (request.requireConfirmationBeforeExpensiveContinuation()) {
      return new Result(initial, PROVIDER, ganeshModel, first, true, false);
    }

    WorkerResult retry = runner.run(request.requestedProvider(), request.requestedModel(), first);
    ExecutableVerification second = verifier.verify(request.runId(), request.workspace());
    retain(request, 2, request.requestedProvider(), request.requestedModel(), second, true);
    return new Result(
        retry, request.requestedProvider(), request.requestedModel(), second, true, true);
  }

  private String eligibleModel(Request request) {
    if (!flags.isEnabled(FLAG)) return null;
    if (blank(endpoint) || blank(platformToken)) {
      return null;
    }
    ResolvedKeys keys = runKeyStore.get(request.runId());
    String mode =
        keys == null || keys.keySourceMode() == null
            ? null
            : keys.keySourceMode().trim().toUpperCase(Locale.ROOT);
    if (!("SYSTEM".equals(mode) || "HYBRID".equals(mode))) {
      return null;
    }
    if (!("BUILD".equals(request.mode()) || "PATCH".equals(request.mode()))) {
      return null;
    }
    if (request.targetFiles() == null || request.targetFiles().size() != 1) {
      return null;
    }
    String expected = modelFor(request.targetFiles().getFirst());
    return expected;
  }

  static String modelFor(String file) {
    if (file == null) return null;
    int dot = file.lastIndexOf('.');
    if (dot < 0 || dot == file.length() - 1) return null;
    return MODELS.get(file.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  private void retain(
      Request request,
      int attempt,
      String provider,
      String model,
      ExecutableVerification evidence,
      boolean retry) {
    if (evidence == null) {
      throw new IllegalStateException("Ganesh E2 evidence is missing for attempt " + attempt);
    }
    ObjectNode data = mapper.createObjectNode();
    data.put("attempt", attempt);
    data.put("maxAttempts", request.requireConfirmationBeforeExpensiveContinuation() ? 1 : 2);
    data.put("provider", provider);
    data.put("model", model);
    data.put("retry", retry);
    data.put("status", evidence.status().name());
    data.put("failureStage", evidence.failureStage().name());
    data.put("testsRun", evidence.testsRun());
    data.put("testsPassed", evidence.testsPassed());
    data.put("command", evidence.command());
    data.put("exitCode", evidence.exitCode());
    data.put("durationMs", evidence.durationMs());
    data.put("detail", evidence.detail());
    data.put("outputTail", evidence.outputTail());
    data.put("outputSha256", sha256(evidence.outputTail()));
    if (evidence.summary() != null) {
      data.put("testsTotal", evidence.summary().getTotalTests());
      data.put("testsPassedCount", evidence.summary().getPassed());
      data.put("testsFailedCount", evidence.summary().getFailed());
      data.put("testErrorsCount", evidence.summary().getErrors());
    }
    broadcaster.emit(
        request.runId(),
        RunEvent.ganeshExecutableAttempt(
            request.runId(),
            request.taskId(),
            "Ganesh E2 attempt " + attempt + ": " + evidence.status(),
            mapper.convertValue(data, Map.class)));
    persistEvidence(request, attempt, provider, model, evidence, data);
  }

  private void persistEvidence(
      Request request,
      int attempt,
      String provider,
      String model,
      ExecutableVerification evidence,
      ObjectNode data) {
    if (blank(request.runId()) || blank(request.taskId())) {
      throw new IllegalStateException("runId and taskId are required for Ganesh evidence");
    }
    String id = request.runId() + ":" + request.taskId() + ":" + attempt;
    try {
      attemptRepository.saveAndFlush(
          GaneshWorkerAttempt.builder()
              .id(id)
              .runId(request.runId())
              .taskId(request.taskId())
              .attempt(attempt)
              .provider(provider)
              .model(model)
              .verificationStatus(evidence.status().name())
              .failureStage(evidence.failureStage().name())
              .evidenceJson(mapper.writeValueAsString(data))
              .createdAt(java.time.Instant.now())
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("Could not retain Ganesh E2 evidence", e);
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
