package com.navneet.executespec.benchmark;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.tool.ExecutionContext;

/**
 * Bounded operator harness for a paired provider handoff attempt.
 *
 * <p>This is deliberately separate from customer routing. A pass is pinned to one provider, and the
 * only possible second pass is an explicit handoff to the one allow-listed fallback. The worker
 * request carries {@code strictProvider=true}, so normal YAML failover cannot contaminate the
 * comparison. Callers must authorize each pass against a distinct external ledger reservation.
 */
public final class ProviderHandoffFixtureHarness {

  public static final String FIXTURE_VERSION = "v1";
  public static final String HANDOFF_FIXTURE_VERSION = "provider-handoff-v1";
  public static final int MAX_ITERATIONS = 60;
  public static final int MAX_TOKENS_PER_CALL = 4_096;

  /**
   * Providers admissible for quality-evidence comparison. Gemini is excluded from the comparator
   * scope by explicit direction, and retired providers are gone from production entirely; neither
   * may enter this harness even by operator request. OpenRouter/Fireworks stay admissible as
   * separate capability probes, never as native-provider quality evidence.
   */
  public static final Set<String> APPROVED_QUALITY_EVIDENCE_PROVIDERS =
      Set.of("anthropic", "openai", "deepseek", "openrouter", "fireworks");

  private static final Map<String, Fixture> FIXTURES = fixtures();

  private ProviderHandoffFixtureHarness() {}

  public enum HandoffTrigger {
    ALWAYS,
    ON_PROVIDER_ERROR,
    ON_STAGNATION
  }

  @FunctionalInterface
  public interface PassAuthorizer {
    void authorize(String reservationId, String provider, String model, String runId);
  }

  public record Request(
      String fixtureId,
      String fixtureVersion,
      String primaryProvider,
      String primaryModel,
      String primaryReservationId,
      String fallbackProvider,
      String fallbackModel,
      String fallbackReservationId,
      Set<String> allowedProviders,
      HandoffTrigger handoffTrigger) {
    public Request {
      fixtureId = require(fixtureId, "fixtureId");
      fixtureVersion = require(fixtureVersion, "fixtureVersion");
      primaryProvider = normalize(primaryProvider, "primaryProvider");
      primaryModel = require(primaryModel, "primaryModel");
      primaryReservationId = require(primaryReservationId, "primaryReservationId");
      fallbackProvider = normalize(fallbackProvider, "fallbackProvider");
      fallbackModel = require(fallbackModel, "fallbackModel");
      fallbackReservationId = require(fallbackReservationId, "fallbackReservationId");
      allowedProviders = normalizeProviders(allowedProviders);
      if (handoffTrigger == null) throw new IllegalArgumentException("handoffTrigger is required");
    }
  }

  public record PassEvidence(
      String provider,
      String model,
      String reservationId,
      WorkerResult.Outcome outcome,
      int iterations,
      int toolCalls,
      boolean evaluatorPassed,
      String evaluatorReason) {}

  public record Evidence(
      String runId,
      String fixtureId,
      String fixtureVersion,
      String handoffHarnessVersion,
      List<String> allowedProviders,
      HandoffTrigger handoffTrigger,
      boolean handoffTriggered,
      PassEvidence primary,
      PassEvidence fallback,
      String artifactFingerprint,
      String evaluatorFingerprint,
      String harnessFingerprint) {}

  /** Returns the only fixture ids accepted by this versioned operator harness. */
  public static Set<String> fixtureIds() {
    return FIXTURES.keySet();
  }

  public static Evidence run(
      WorkerAgent worker, Request request, String runId, Path workspace, PassAuthorizer authorizer)
      throws IOException {
    if (worker == null) throw new IllegalArgumentException("worker is required");
    if (authorizer == null) throw new IllegalArgumentException("authorizer is required");
    Fixture fixture = fixture(request);
    validateRoute(request);
    String effectiveRunId = require(runId, "runId");
    Files.createDirectories(workspace);
    fixture.prepare().accept(workspace);

    authorizer.authorize(
        request.primaryReservationId(),
        request.primaryProvider(),
        request.primaryModel(),
        effectiveRunId);
    PassEvidence primary =
        runPass(
            worker,
            fixture,
            request.primaryProvider(),
            request.primaryModel(),
            effectiveRunId,
            workspace,
            false,
            request.primaryReservationId());
    boolean handoff = shouldHandoff(request.handoffTrigger(), primary.outcome());
    PassEvidence fallback = null;
    if (handoff) {
      authorizer.authorize(
          request.fallbackReservationId(),
          request.fallbackProvider(),
          request.fallbackModel(),
          effectiveRunId);
      fallback =
          runPass(
              worker,
              fixture,
              request.fallbackProvider(),
              request.fallbackModel(),
              effectiveRunId + "-handoff",
              workspace,
              true,
              request.fallbackReservationId());
    }

    return new Evidence(
        effectiveRunId,
        fixture.id(),
        fixture.version(),
        HANDOFF_FIXTURE_VERSION,
        request.allowedProviders().stream().sorted().toList(),
        request.handoffTrigger(),
        handoff,
        primary,
        fallback,
        fingerprintWorkspace(workspace),
        sha256(fixture.evaluatorId()),
        sha256(
            HANDOFF_FIXTURE_VERSION
                + "\n"
                + fixture.id()
                + "\n"
                + MAX_ITERATIONS
                + "\n"
                + MAX_TOKENS_PER_CALL));
  }

  private static PassEvidence runPass(
      WorkerAgent worker,
      Fixture fixture,
      String provider,
      String model,
      String runId,
      Path workspace,
      boolean handoff,
      String reservationId) {
    WorkerResult result =
        worker.run(
            WorkerTaskRequest.builder()
                .provider(provider)
                .model(model)
                .systemPrompt(
                    "You are a bounded operator coding worker. Use tools, verify the fixture, and"
                        + " call completeTask.")
                .userPrompt(
                    fixture.task()
                        + (handoff
                            ? " This is an explicit fallback handoff; do not change the route."
                            : ""))
                .context(
                    new ExecutionContext(
                        runId, fixture.id(), workspace.toAbsolutePath().normalize()))
                .maxIterations(MAX_ITERATIONS)
                .maxTokensPerCall(MAX_TOKENS_PER_CALL)
                .temperature(0.0)
                .strictProvider(true)
                .build());
    Evaluation evaluation = fixture.evaluate().apply(workspace);
    return new PassEvidence(
        provider,
        model,
        reservationId,
        result.outcome(),
        result.iterations(),
        result.toolCallsDispatched(),
        evaluation.passed(),
        evaluation.reason());
  }

  private static Fixture fixture(Request request) {
    if (!FIXTURE_VERSION.equals(request.fixtureVersion())) {
      throw new IllegalArgumentException("unsupported fixtureVersion: " + request.fixtureVersion());
    }
    Fixture fixture = FIXTURES.get(request.fixtureId());
    if (fixture == null)
      throw new IllegalArgumentException("unsupported fixtureId: " + request.fixtureId());
    return fixture;
  }

  private static void validateRoute(Request request) {
    if (request.primaryProvider().equals(request.fallbackProvider())) {
      throw new IllegalArgumentException("primaryProvider and fallbackProvider must differ");
    }
    for (String provider : List.of(request.primaryProvider(), request.fallbackProvider())) {
      if (!APPROVED_QUALITY_EVIDENCE_PROVIDERS.contains(provider)) {
        throw new IllegalArgumentException(
            "provider "
                + provider
                + " is not admissible for quality-evidence comparison (approved: "
                + APPROVED_QUALITY_EVIDENCE_PROVIDERS.stream().sorted().toList()
                + ")");
      }
    }
    Set<String> allowed =
        request.allowedProviders().stream()
            .map(String::toLowerCase)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (allowed.size() != 2
        || !allowed.equals(Set.of(request.primaryProvider(), request.fallbackProvider()))) {
      throw new IllegalArgumentException(
          "allowedProviders must contain exactly primaryProvider and fallbackProvider");
    }
    if (request.primaryReservationId().equals(request.fallbackReservationId())) {
      throw new IllegalArgumentException("primary and fallback reservations must differ");
    }
  }

  private static Set<String> normalizeProviders(Set<String> providers) {
    if (providers == null) return Set.of();
    return providers.stream()
        .map(value -> normalize(value, "allowedProviders entry"))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static boolean shouldHandoff(HandoffTrigger trigger, WorkerResult.Outcome outcome) {
    return trigger == HandoffTrigger.ALWAYS
        || (trigger == HandoffTrigger.ON_PROVIDER_ERROR && outcome == WorkerResult.Outcome.ERROR)
        || (trigger == HandoffTrigger.ON_STAGNATION
            && (outcome == WorkerResult.Outcome.COMPLETED_BY_STAGNATION
                || outcome == WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE
                || outcome == WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED));
  }

  private record Evaluation(boolean passed, String reason) {}

  private record Fixture(
      String id,
      String version,
      String task,
      String evaluatorId,
      java.util.function.Consumer<Path> prepare,
      java.util.function.Function<Path, Evaluation> evaluate) {}

  private static Map<String, Fixture> fixtures() {
    Map<String, Fixture> out = new LinkedHashMap<>();
    out.put(
        "repository-localization-v1",
        new Fixture(
            "repository-localization-v1",
            FIXTURE_VERSION,
            "Find the buggy add function under src/math/calculator.py, fix it to return a+b, and"
                + " verify it.",
            "repository-localization-evaluator-v1",
            root -> write(root, "src/math/calculator.py", "def add(a, b):\n    return a - b\n"),
            root -> evaluateSource(root, "src/math/calculator.py")));
    out.put(
        "edit-test-repair-v1",
        new Fixture(
            "edit-test-repair-v1",
            FIXTURE_VERSION,
            "Repair src/math/calculator.py so add returns a+b and run the supplied test.",
            "edit-test-repair-evaluator-v1",
            root -> {
              write(root, "src/math/calculator.py", "def add(a, b):\n    return a - b\n");
              write(root, "tests/test_calculator.py", "assert add(2, 3) == 5\n");
            },
            root -> evaluateSource(root, "src/math/calculator.py")));
    out.put(
        "stuck-handoff-v1",
        new Fixture(
            "stuck-handoff-v1",
            FIXTURE_VERSION,
            "Fix the calculator bug. If the task cannot be completed after inspecting the fixture,"
                + " stop cleanly so the explicit provider handoff can be evaluated.",
            "stuck-handoff-evaluator-v1",
            root -> write(root, "calculator.py", "def add(a, b):\n    return a - b\n"),
            root -> evaluateSource(root, "calculator.py")));
    return Map.copyOf(out);
  }

  private static Evaluation evaluateSource(Path root, String relative) {
    try {
      String source = Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
      return source.contains("return a + b")
          ? new Evaluation(true, "independent fixture evaluator found additive implementation")
          : new Evaluation(false, "additive implementation missing");
    } catch (IOException error) {
      return new Evaluation(false, "fixture artifact missing: " + relative);
    }
  }

  private static void write(Path root, String relative, String content) {
    try {
      Path target = root.resolve(relative);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("could not prepare fixture " + relative, error);
    }
  }

  private static String fingerprintWorkspace(Path root) throws IOException {
    // Hash RAW BYTES, not a UTF-8-decoded String: a worker that runs tests/builds leaves binary
    // artifacts (e.g. Python `.pyc`, compiled classes) in the workspace, and Files.readString would
    // throw MalformedInputException (an IOException) on the first non-UTF-8 file, turning a
    // legitimate
    // no-deliverable stagnation into a 500 instead of scoreable evidence. `.git` internals are
    // excluded because their object/timestamp churn is non-deterministic noise, not deliverable
    // code.
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
    try (var walk = Files.walk(root)) {
      for (Path path :
          walk.filter(Files::isRegularFile)
              .filter(p -> !root.relativize(p).toString().startsWith(".git" + File.separator))
              .filter(p -> !root.relativize(p).toString().equals(".git"))
              .sorted()
              .toList()) {
        digest.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(Files.readAllBytes(path));
        digest.update((byte) '\n');
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }

  private static String normalize(String value, String name) {
    return require(value, name).toLowerCase();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }
}
