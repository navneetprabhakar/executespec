package com.navneet.executespec.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.tool.ExecutionContext;

/**
 * Operator-only, no-route harness for a deterministic LOW coding fixture. It deliberately does not
 * reserve provider budget itself: callers must reserve an exact provider/model/run scope with
 * {@code scripts/provider-budget-ledger.mjs} before invoking {@link #run}. The canonical {@code
 * LlmRouter} guard then authorizes every live request.
 */
public final class LowCodingFixtureHarness {

  public static final String FIXTURE_ID = "low-python-addition-v1";
  public static final String TIER = "LOW";
  public static final int MAX_ITERATIONS = 60;
  public static final int MAX_TOKENS_PER_CALL = 4_096;

  private static final String SOURCE_PATH = "calculator.py";
  private static final String SOURCE = "def add(a, b):\n    return a - b\n";
  private static final String TASK =
      "Fix calculator.py so add(a, b) returns the sum. Preserve the function name and signature."
          + " Verify the result for 2 and 3, then call completeTask.";
  private static final String TOOL_SCHEMA_ID = "worker-tools-v2";

  private LowCodingFixtureHarness() {}

  public record Evidence(
      String fixtureId,
      String provider,
      String model,
      String tier,
      String runId,
      String promptFingerprint,
      String toolSchemaFingerprint,
      String harnessFingerprint,
      WorkerResult.Outcome outcome,
      int iterations,
      int toolCalls,
      boolean evaluatorPassed,
      String evaluatorReason,
      String finalSource) {}

  /** Create the fixed repository-local fixture and return its root. */
  public static Path prepare(Path root) throws IOException {
    Files.createDirectories(root);
    Files.writeString(root.resolve(SOURCE_PATH), SOURCE, StandardCharsets.UTF_8);
    return root;
  }

  /**
   * Run the fixture through the canonical worker loop with LOW caps and evaluate the edited file. A
   * blank reservation id fails closed before any worker call.
   */
  public static Evidence run(
      WorkerAgent worker,
      String provider,
      String model,
      String runId,
      String reservationId,
      Path workspace)
      throws IOException {
    requireNonBlank(provider, "provider");
    requireNonBlank(model, "model");
    requireNonBlank(runId, "runId");
    requireNonBlank(reservationId, "reservationId");
    prepare(workspace);
    WorkerResult result =
        worker.run(
            WorkerTaskRequest.builder()
                .provider(provider)
                .model(model)
                .systemPrompt(
                    "You are a bounded LOW coding worker. Use tools, verify, then complete.")
                .userPrompt(TASK)
                .context(new ExecutionContext(runId, "low-fixture-task", workspace.toRealPath()))
                .maxIterations(MAX_ITERATIONS)
                .maxTokensPerCall(MAX_TOKENS_PER_CALL)
                .temperature(0.0)
                .build());
    String source = Files.readString(workspace.resolve(SOURCE_PATH), StandardCharsets.UTF_8);
    boolean passed = source.contains("return a + b");
    String reason =
        passed
            ? "calculator.py contains additive implementation"
            : "additive implementation missing";
    return new Evidence(
        FIXTURE_ID,
        provider,
        model,
        TIER,
        runId,
        sha256(TASK),
        sha256(TOOL_SCHEMA_ID),
        sha256(FIXTURE_ID + "\n" + TIER + "\n" + MAX_ITERATIONS + "\n" + MAX_TOKENS_PER_CALL),
        result.outcome(),
        result.iterations(),
        result.toolCallsDispatched(),
        passed,
        reason,
        source);
  }

  public static Map<String, Object> flags() {
    return Map.of(
        "worker.verify-before-complete.enabled", true,
        "worker.auto-verify.enabled", false,
        "worker.progress-v2.enabled", false);
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
