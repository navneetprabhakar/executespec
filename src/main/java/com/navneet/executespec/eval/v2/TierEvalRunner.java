package com.navneet.executespec.eval.v2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.WorkerResult;
import com.navneet.executespec.agent.WorkerTaskRequest;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.eval.v2.TierEvalCase.ValidationVerdict;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.core.io.ClassPathResource;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrator for the v2 tier eval. Takes a {@link TierEvalCase} and a target provider, resolves
 * the provider's model for the case's tier from the model matrix, invokes through {@link
 * LlmRouter}, and assembles a {@link TierEvalResult} that captures what production actually cares
 * about: tool-call validity, cache hit rate, latency, and cost.
 *
 * <p>One synthetic {@code runId} is allocated per call so {@link RunCostTracker} bookkeeping
 * doesn't bleed between cases. The synthetic id is {@code eval-v2-<UUID>}; nothing else in the
 * system depends on those rows.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TierEvalRunner {

  /** Hard ceiling on a single eval call. Mirrors {@code ModelEvaluator.PER_CALL_TIMEOUT}. */
  public static final long PER_CALL_TIMEOUT_SECONDS = 240;

  /** Thread name pattern for the per-call executor — easy to spot in stack traces. */
  private static final String EXECUTOR_THREAD_NAME = "tier-eval-v2";

  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final RunCostTracker costTracker;
  private final ObjectMapper objectMapper;
  private final WorkerAgent workerAgent;

  /**
   * Run a single case against a single provider. Returns a fully-populated result, including
   * failure modes — never throws to the caller.
   */
  public TierEvalResult run(TierEvalCase testCase, String provider) {
    String runId = "eval-v2-" + UUID.randomUUID();
    String taskId = testCase.name();
    ExecutionContext ctx =
        new ExecutionContext(runId, taskId, java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
    LlmProvider providerEnum;
    try {
      providerEnum = LlmProvider.valueOf(provider.toUpperCase());
    } catch (IllegalArgumentException e) {
      return failureResult(
          testCase,
          provider,
          "?",
          TierEvalFailureMode.RUNTIME_ERROR,
          "unknown provider: " + provider);
    }
    ResolvedModel resolved;
    try {
      // The eval grades a tier slot, so we resolve through the matrix the same way the
      // production agent for that tier would (CoherencePlannerAgent for LIGHTWEIGHT,
      // OutlinePlannerAgent for PLANNING, WorkerAgent for WORKHORSE). Picking a
      // representative real agent name keeps the model lookup honest to production behavior.
      String agentName = representativeAgentFor(testCase.tier());
      resolved = modelResolver.resolve(agentName, providerEnum, testCase.tier());
    } catch (Exception e) {
      return failureResult(
          testCase,
          provider,
          "?",
          TierEvalFailureMode.RUNTIME_ERROR,
          "model resolution failed: " + e.getMessage());
    }
    String model = resolved.getModel();
    String resolvedProvider = resolved.getActualProvider().name().toLowerCase();

    LlmRequest req =
        LlmRequest.builder()
            .provider(resolvedProvider)
            .model(model)
            .messages(testCase.messages())
            .context(ctx)
            .temperature(0.0)
            .maxTokens(8192)
            .toolsOverride(testCase.tools().isEmpty() ? null : testCase.tools())
            .build();

    long start = System.currentTimeMillis();
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, EXECUTOR_THREAD_NAME + "-" + testCase.name());
              t.setDaemon(true);
              return t;
            });
    try {
      Future<ChatResponse> future = executor.submit(() -> llmRouter.call(req));
      ChatResponse resp = future.get(PER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      long elapsed = System.currentTimeMillis() - start;
      return grade(testCase, resolvedProvider, model, resp, elapsed, runId);
    } catch (TimeoutException e) {
      long elapsed = System.currentTimeMillis() - start;
      log.warn("tier-eval-v2 timeout case={} provider={} elapsed={}ms", testCase.name(), provider, elapsed);
      return failureResult(testCase, resolvedProvider(provider), model, TierEvalFailureMode.TIMEOUT,
          "exceeded " + PER_CALL_TIMEOUT_SECONDS + "s");
    } catch (Exception e) {
      log.warn(
          "tier-eval-v2 error case={} provider={} cause={}",
          testCase.name(),
          provider,
          e.getMessage());
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      // Provider HTTP non-2xx surfaces here as a runtime exception with the body in the message.
      return failureResult(
          testCase,
          resolvedProvider(provider),
          model,
          TierEvalFailureMode.PROVIDER_ERROR,
          truncate(cause.getMessage(), 240));
    } finally {
      executor.shutdownNow();
    }
  }

  private TierEvalResult grade(
      TierEvalCase testCase, String provider, String model, ChatResponse resp, long elapsedMs, String runId) {
    // Scan ALL generations, not just resp.getResult() (the first). Anthropic via Spring AI can
    // split a response into multiple generations (e.g. a thinking block + a tool_use block); the
    // tool call may live in a generation other than the first, so reading only getResult() misses
    // it and reports a false "no tool call." Aggregate text + tool calls across every generation.
    StringBuilder textBuf = new StringBuilder();
    java.util.List<AssistantMessage.ToolCall> allToolCalls = new java.util.ArrayList<>();
    if (resp.getResults() != null) {
      for (org.springframework.ai.chat.model.Generation g : resp.getResults()) {
        AssistantMessage out = g.getOutput();
        if (out == null) continue;
        if (out.getText() != null && !out.getText().isBlank()) {
          if (textBuf.length() > 0) textBuf.append('\n');
          textBuf.append(out.getText());
        }
        if (out.getToolCalls() != null) allToolCalls.addAll(out.getToolCalls());
      }
    }
    String assistantText = textBuf.length() > 0 ? textBuf.toString() : null;

    int toolCallCount = allToolCalls.size();
    int toolCallMalformed = 0;
    String firstToolName = null;
    String firstToolArgs = null;
    {
      for (AssistantMessage.ToolCall tc : allToolCalls) {
        if (firstToolName == null) {
          firstToolName = tc.name();
          firstToolArgs = tc.arguments();
        }
        if (tc.arguments() != null && !tc.arguments().isBlank()) {
          try {
            objectMapper.readTree(tc.arguments());
          } catch (Exception e) {
            toolCallMalformed++;
          }
        }
      }
    }

    // Snapshot the cost tracker for this synthetic runId; it captures input/output/cache tokens
    // + the computed cost from the model-matrix pricing for this exact (provider, model) call.
    RunCostTracker.Snapshot snap = costTracker.snapshot(runId);
    long inputTokens = snap.inputTokens();
    long outputTokens = snap.outputTokens();
    long cacheReadTokens = snap.cacheReadTokens();
    double cost = snap.costUsd();

    // Validate — first the runner-level shortcuts (wrong tool / malformed JSON), then the case.
    if (testCase.requiresToolCall()) {
      if (firstToolName == null) {
        // Debug aid (2026-05-20): when no tool call fires, capture a snippet of what the model
        // returned instead so we can tell text-plan from integration failure.
        String snippet =
            assistantText == null ? "(null assistant text)" : truncate(assistantText.strip(), 220);
        return baseBuilder(testCase, provider, model, elapsedMs, inputTokens, outputTokens,
            cacheReadTokens, cost)
            .toolCallsAttempted(toolCallCount)
            .toolCallsMalformed(toolCallMalformed)
            .firstToolCallName(null)
            .correctnessScore(0)
            .failureMode(TierEvalFailureMode.WRONG_TOOL)
            .detail(
                "no tool call (expected " + testCase.expectedToolName() + "). text="
                    + snippet)
            .build();
      }
      String expected = testCase.expectedToolName();
      if (expected != null && !expected.equals(firstToolName)) {
        return baseBuilder(testCase, provider, model, elapsedMs, inputTokens, outputTokens,
            cacheReadTokens, cost)
            .toolCallsAttempted(toolCallCount)
            .toolCallsMalformed(toolCallMalformed)
            .firstToolCallName(firstToolName)
            .correctnessScore(0)
            .failureMode(TierEvalFailureMode.WRONG_TOOL)
            .detail("called " + firstToolName + " (expected " + expected + ")")
            .build();
      }
      if (firstToolArgs != null && !firstToolArgs.isBlank()) {
        try {
          objectMapper.readTree(firstToolArgs);
        } catch (Exception e) {
          return baseBuilder(testCase, provider, model, elapsedMs, inputTokens, outputTokens,
              cacheReadTokens, cost)
              .toolCallsAttempted(toolCallCount)
              .toolCallsMalformed(toolCallMalformed)
              .firstToolCallName(firstToolName)
              .correctnessScore(0)
              .failureMode(TierEvalFailureMode.MALFORMED_TOOL_CALL)
              .detail("args not parseable JSON")
              .build();
        }
      }
    }

    TierEvalCase.ValidationVerdict verdict;
    try {
      verdict = testCase.validate(assistantText, firstToolName, firstToolArgs);
    } catch (Exception e) {
      verdict = new TierEvalCase.ValidationVerdict(0, "validator threw: " + e.getMessage());
    }

    return baseBuilder(testCase, provider, model, elapsedMs, inputTokens, outputTokens,
        cacheReadTokens, cost)
        .toolCallsAttempted(toolCallCount)
        .toolCallsMalformed(toolCallMalformed)
        .firstToolCallName(firstToolName)
        .correctnessScore(verdict.correctness())
        .failureMode(TierEvalFailureMode.OK)
        .detail(verdict.detail())
        .build();
  }

  private TierEvalResult.TierEvalResultBuilder baseBuilder(
      TierEvalCase testCase,
      String provider,
      String model,
      long elapsedMs,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      double cost) {
    return TierEvalResult.builder()
        .caseName(testCase.name())
        .tier(testCase.tier())
        .provider(provider)
        .model(model)
        .latencyMs(elapsedMs)
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .cacheReadTokens(cacheReadTokens)
        .costUsd(cost);
  }

  private TierEvalResult failureResult(
      TierEvalCase testCase, String provider, String model, TierEvalFailureMode mode, String detail) {
    return failureResult(testCase.name(), testCase.tier(), provider, model, mode, detail);
  }

  private TierEvalResult failureResult(
      String caseName,
      AgentTier tier,
      String provider,
      String model,
      TierEvalFailureMode mode,
      String detail) {
    return TierEvalResult.builder()
        .caseName(caseName)
        .tier(tier)
        .provider(provider)
        .model(model)
        .latencyMs(0)
        .inputTokens(0)
        .outputTokens(0)
        .cacheReadTokens(0)
        .costUsd(0.0)
        .toolCallsAttempted(0)
        .toolCallsMalformed(0)
        .firstToolCallName(null)
        .correctnessScore(0)
        .failureMode(mode)
        .detail(detail)
        .build();
  }

  /** Best-effort lowercase of the input provider for failure paths where resolution didn't run. */
  private static String resolvedProvider(String input) {
    return input == null ? "?" : input.toLowerCase();
  }

  private static String representativeAgentFor(AgentTier tier) {
    return switch (tier) {
      case LIGHTWEIGHT -> "SmartClassifierAgent";
      case WORKHORSE -> "WorkerAgent";
      case PLANNING -> "OutlinePlannerAgent";
    };
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  /**
   * Drive the real {@link WorkerAgent} multi-turn loop for one workhorse case against one provider.
   * Seeds a scratch workspace under the temp dir, runs the worker with the production worker prompt,
   * grades the resulting files, then cleans up. Never throws.
   */
  public TierEvalResult runWorkhorse(WorkhorseEvalCase testCase, String provider) {
    String runId = "eval-v2-wh-" + UUID.randomUUID();
    Path workspace;
    try {
      workspace = Files.createTempDirectory("eval-wh-");
      testCase.seedWorkspace(workspace);
    } catch (Exception e) {
      return failureResult(
          testCase.name(), AgentTier.WORKHORSE, provider, "?",
          TierEvalFailureMode.RUNTIME_ERROR, "workspace seed failed: " + e.getMessage());
    }
    LlmProvider providerEnum;
    try {
      providerEnum = LlmProvider.valueOf(provider.toUpperCase());
    } catch (IllegalArgumentException e) {
      cleanup(workspace);
      return failureResult(testCase.name(), AgentTier.WORKHORSE, provider, "?",
          TierEvalFailureMode.RUNTIME_ERROR, "unknown provider: " + provider);
    }
    ResolvedModel resolved;
    try {
      resolved = modelResolver.resolve("WorkerAgent", providerEnum, AgentTier.WORKHORSE);
    } catch (Exception e) {
      cleanup(workspace);
      return failureResult(testCase.name(), AgentTier.WORKHORSE, provider, "?",
          TierEvalFailureMode.RUNTIME_ERROR, "model resolve failed: " + e.getMessage());
    }
    String model = resolved.getModel();
    String resolvedProvider = resolved.getActualProvider().name().toLowerCase();
    ExecutionContext ctx = new ExecutionContext(runId, testCase.name(), workspace);
    String workerPrompt = loadClasspath("prompts/worker.txt");

    long start = System.currentTimeMillis();
    try {
      WorkerResult wr =
          workerAgent.run(
              WorkerTaskRequest.builder()
                  .provider(resolvedProvider)
                  .model(model)
                  .systemPrompt(workerPrompt)
                  .userPrompt(testCase.taskPrompt())
                  .context(ctx)
                  .maxIterations(15)
                  .temperature(0.0)
                  .maxTokensPerCall(8192)
                  .wallClockSeconds(240)
                  .build());
      long elapsed = System.currentTimeMillis() - start;
      RunCostTracker.Snapshot snap = costTracker.snapshot(runId);
      ValidationVerdict v;
      try {
        v = testCase.validate(workspace, wr);
      } catch (Exception e) {
        v = new ValidationVerdict(0, "validator threw: " + e.getMessage());
      }
      boolean ok = wr.outcome() != WorkerResult.Outcome.ERROR;
      return TierEvalResult.builder()
          .caseName(testCase.name())
          .tier(AgentTier.WORKHORSE)
          .provider(resolvedProvider)
          .model(model)
          .latencyMs(elapsed)
          .inputTokens(snap.inputTokens())
          .outputTokens(snap.outputTokens())
          .cacheReadTokens(snap.cacheReadTokens())
          .costUsd(snap.costUsd())
          .toolCallsAttempted(wr.toolCallsDispatched())
          .toolCallsMalformed(0)
          .firstToolCallName(wr.outcome().name())
          .iterationsUsed(wr.iterations())
          .correctnessScore(v.correctness())
          .failureMode(ok ? TierEvalFailureMode.OK : TierEvalFailureMode.RUNTIME_ERROR)
          .detail(v.detail() + " [" + wr.iterations() + " iters, " + wr.toolCallsDispatched()
              + " tool calls, outcome=" + wr.outcome() + "]")
          .build();
    } catch (Exception e) {
      log.warn("tier-eval-v2 workhorse error case={} provider={}: {}", testCase.name(), provider,
          e.getMessage());
      return failureResult(testCase.name(), AgentTier.WORKHORSE, resolvedProvider, model,
          TierEvalFailureMode.RUNTIME_ERROR, truncate(e.getMessage(), 200));
    } finally {
      cleanup(workspace);
    }
  }

  private static void cleanup(Path dir) {
    if (dir == null) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (Exception ignored) {
              // best-effort
            }
          });
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private static String loadClasspath(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("classpath resource missing: " + path, e);
    }
  }

  /** Run every workhorse case against every provider. */
  public List<TierEvalResult> runAllWorkhorse(
      List<WorkhorseEvalCase> cases, List<String> providers) {
    List<TierEvalResult> results = new java.util.ArrayList<>();
    for (WorkhorseEvalCase c : cases) {
      for (String p : providers) {
        log.info("tier-eval-v2 workhorse start case={} provider={}", c.name(), p);
        TierEvalResult r = runWorkhorse(c, p);
        results.add(r);
        log.info(
            "tier-eval-v2 workhorse done case={} provider={} score={} iters={} mode={}",
            c.name(), p, r.correctnessScore(), r.iterationsUsed(), r.failureMode());
      }
    }
    return results;
  }

  /**
   * Build a {@link TierEvalReport} by running {@code cases} against every provider in {@code
   * providers}. Sequential — keeps provider quotas + token-budget logging clean.
   */
  public TierEvalReport runAll(List<TierEvalCase> cases, List<String> providers) {
    Instant started = Instant.now();
    long t0 = System.currentTimeMillis();
    java.util.List<TierEvalResult> results = new java.util.ArrayList<>();
    for (TierEvalCase c : cases) {
      for (String p : providers) {
        log.info("tier-eval-v2 start case={} provider={}", c.name(), p);
        TierEvalResult r = run(c, p);
        results.add(r);
        log.info(
            "tier-eval-v2 done case={} provider={} model={} correctness={} mode={} latency={}ms cost=${}",
            c.name(),
            p,
            r.model(),
            r.correctnessScore(),
            r.failureMode(),
            r.latencyMs(),
            String.format("%.4f", r.costUsd()));
      }
    }
    return TierEvalReport.builder()
        .evaluationId("eval-v2-" + UUID.randomUUID())
        .startedAt(started)
        .durationMs(System.currentTimeMillis() - t0)
        .results(results)
        .build();
  }
}
