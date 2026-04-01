package com.navneet.executespec.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.navneet.executespec.agent.CostEstimator;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.ModelMatrixProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Orchestrates model evaluation: runs all models against all test cases,
 * scores results, normalizes costs, and generates the final report.
 *
 * <p>Total calls: 6 models x 12 test cases = 72 LLM calls.
 * Models are tested sequentially to avoid rate limits.
 * Includes 3 boss-level saga tests (diagnosis, consistency, compliance).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelEvaluator {

    private final LlmRouter llmRouter;
    private final CostEstimator costEstimator;
    private final EvalScorer scorer;
    private final EvalTestCaseRegistry testCaseRegistry;
    private final ModelMatrixProperties matrixProps;

    /** Max time for a single LLM call before it's killed and scored as failed. */
    private static final Duration PER_CALL_TIMEOUT = Duration.ofSeconds(180);

    /** Max wall-clock time for the entire evaluation run. */
    private static final Duration EVAL_DEADLINE = Duration.ofMinutes(45);

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Path REPORT_DIR = Path.of("eval-reports");

    /**
     * Run the full evaluation and return the report.
     * @param providerFilter if non-null, only evaluate models from these providers (e.g. {"google", "openai"})
     */
    public EvalReport evaluate(Set<String> providerFilter) throws IOException {
        Instant start = Instant.now();
        String evalId = UUID.randomUUID().toString();
        log.info("[ModelEvaluator] Starting evaluation {} — {} test cases", evalId, testCaseRegistry.allTestCases().size());

        // Enumerate all unique provider:model pairs from the matrix
        List<String[]> models = enumerateModels();
        if (providerFilter != null && !providerFilter.isEmpty()) {
            models = models.stream()
                    .filter(m -> providerFilter.contains(m[0].toLowerCase()))
                    .collect(Collectors.toList());
            log.info("[ModelEvaluator] Filtered to {} models for providers: {}", models.size(), providerFilter);
        }
        log.info("[ModelEvaluator] Evaluating {} models: {}", models.size(),
                models.stream().map(m -> m[0] + ":" + m[1]).collect(Collectors.joining(", ")));

        // Run all test cases for each model with per-call and overall deadlines
        // Key: "provider:model", Value: list of results for that model
        Map<String, List<EvalResult>> resultsByModel = new LinkedHashMap<>();
        Instant deadline = start.plus(EVAL_DEADLINE);
        int completedCalls = 0;
        int skippedCalls = 0;
        int timedOutCalls = 0;

        for (String[] providerModel : models) {
            String provider = providerModel[0];
            String model = providerModel[1];
            String key = provider + ":" + model;
            List<EvalResult> modelResults = new ArrayList<>();

            // Check overall deadline before starting a new model
            if (Instant.now().isAfter(deadline)) {
                log.warn("[ModelEvaluator] Overall deadline exceeded ({}min). Skipping remaining models starting from {}",
                        EVAL_DEADLINE.toMinutes(), key);
                // Fill remaining models with skip results
                for (EvalTestCase testCase : testCaseRegistry.allTestCases()) {
                    modelResults.add(buildSkipResult(testCase, provider, model, "SKIPPED: eval deadline exceeded"));
                    skippedCalls++;
                }
                resultsByModel.put(key, modelResults);
                continue;
            }

            for (EvalTestCase testCase : testCaseRegistry.allTestCases()) {
                // Check deadline before each call
                if (Instant.now().isAfter(deadline)) {
                    log.warn("[ModelEvaluator] Deadline exceeded mid-model, skipping remaining tests for {}", key);
                    modelResults.add(buildSkipResult(testCase, provider, model, "SKIPPED: eval deadline exceeded"));
                    skippedCalls++;
                    continue;
                }

                EvalResult result = runSingleTestWithTimeout(provider, model, testCase);
                modelResults.add(result);
                completedCalls++;
                if (result.reasoning() != null && result.reasoning().startsWith("TIMEOUT")) timedOutCalls++;

                log.info("[ModelEvaluator] [{}/{}] {}/{} — accuracy={}, speed={}, latency={}ms, cost=${}",
                        completedCalls, models.size() * testCaseRegistry.allTestCases().size(),
                        key, testCase.id(), String.format("%.1f", result.accuracyScore()),
                        String.format("%.1f", result.speedScore()), result.latencyMs(),
                        String.format("%.6f", result.costUsd()));
            }

            resultsByModel.put(key, modelResults);
        }

        log.info("[ModelEvaluator] Calls: {} completed, {} timed out, {} skipped",
                completedCalls, timedOutCalls, skippedCalls);

        // Post-processing: normalize cost scores per test case
        normalizeCosts(resultsByModel);

        // Build report
        EvalReport report = buildReport(evalId, start, resultsByModel);

        // Write to file
        writeReport(report);

        log.info("[ModelEvaluator] Evaluation {} complete in {}ms", evalId, report.durationMs());
        return report;
    }

    private List<String[]> enumerateModels() {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> result = new ArrayList<>();

        for (Map.Entry<String, ModelMatrixProperties.ProviderModels> entry : matrixProps.getProviders().entrySet()) {
            String provider = entry.getKey();
            ModelMatrixProperties.ProviderModels pm = entry.getValue();

            addIfNew(seen, result, provider, pm.getPlanning());
            addIfNew(seen, result, provider, pm.getWorkhorse());
            addIfNew(seen, result, provider, pm.getLightweight());
        }

        return result;
    }

    private void addIfNew(Set<String> seen, List<String[]> result, String provider, String model) {
        String key = provider + ":" + model;
        if (seen.add(key)) {
            result.add(new String[]{provider, model});
        }
    }

    /**
     * Wraps runSingleTest with a per-call timeout. If the LLM call exceeds
     * {@link #PER_CALL_TIMEOUT}, the future is cancelled and a timeout result is returned.
     */
    private EvalResult runSingleTestWithTimeout(String provider, String model, EvalTestCase testCase) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long startMs = System.currentTimeMillis();
        try {
            Future<EvalResult> future = executor.submit(() -> runSingleTest(provider, model, testCase));
            return future.get(PER_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.warn("[ModelEvaluator] TIMEOUT after {}ms: {}:{} on {} (limit={}s)",
                    latencyMs, provider, model, testCase.id(), PER_CALL_TIMEOUT.toSeconds());
            return new EvalResult(
                    testCase.id(), testCase.tier(), provider, model,
                    1.0, 1.0, 5.0, scorer.overallScore(1.0, 1.0, 5.0),
                    latencyMs, 0.0, 0, 0,
                    false, 0, testCase.validationPatterns().size(),
                    "TIMEOUT: exceeded " + PER_CALL_TIMEOUT.toSeconds() + "s limit", null
            );
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.error("[ModelEvaluator] Unexpected error: {}:{} on {}: {}",
                    provider, model, testCase.id(), e.getMessage());
            return new EvalResult(
                    testCase.id(), testCase.tier(), provider, model,
                    1.0, 1.0, 5.0, scorer.overallScore(1.0, 1.0, 5.0),
                    latencyMs, 0.0, 0, 0,
                    false, 0, testCase.validationPatterns().size(),
                    "ERROR: " + e.getMessage(), null
            );
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Build a placeholder result for skipped tests (deadline exceeded).
     */
    private EvalResult buildSkipResult(EvalTestCase testCase, String provider, String model, String reason) {
        return new EvalResult(
                testCase.id(), testCase.tier(), provider, model,
                0.0, 0.0, 5.0, 0.0,
                0, 0.0, 0, 0,
                false, 0, testCase.validationPatterns().size(),
                reason, null
        );
    }

    private EvalResult runSingleTest(String provider, String model, EvalTestCase testCase) {
        Prompt prompt = new Prompt(List.of(new UserMessage(testCase.userPrompt())));

        long startMs = System.currentTimeMillis();
        String responseText;
        long inputTokens = 0;
        long outputTokens = 0;

        try {
            LlmRouter.LlmResponse llmResponse = llmRouter.callDirect(provider, model, prompt);
            long latencyMs = System.currentTimeMillis() - startMs;

            responseText = llmResponse.response().getResult().getOutput().getText();

            try {
                var usage = llmResponse.response().getMetadata().getUsage();
                inputTokens = usage.getPromptTokens();
                outputTokens = usage.getCompletionTokens();
            } catch (Exception e) {
                // token extraction best-effort
            }

            double costUsd = costEstimator.computeCost(provider, model, inputTokens, outputTokens);
            double accuracy = scorer.scoreAccuracy(responseText, testCase.validationPatterns(), testCase.requiredJsonKeys());
            double speed = scorer.scoreSpeed(latencyMs, testCase.latencyCeilingMs());
            int matched = scorer.countMatchedPatterns(responseText, testCase.validationPatterns());

            // Length penalty for LIGHTWEIGHT tests
            if (testCase.maxResponseLength() > 0 && responseText != null
                    && responseText.length() > testCase.maxResponseLength()) {
                accuracy = Math.max(1.0, accuracy - 2.0);
            }

            boolean passed = matched == testCase.validationPatterns().size();
            String reasoning = buildReasoning(matched, testCase.validationPatterns().size(),
                    latencyMs, testCase.latencyCeilingMs(), accuracy, speed);

            return new EvalResult(
                    testCase.id(), testCase.tier(), provider, model,
                    accuracy, speed, 5.0, // cost placeholder — normalized later
                    scorer.overallScore(accuracy, speed, 5.0),
                    latencyMs, costUsd, inputTokens, outputTokens,
                    passed, matched, testCase.validationPatterns().size(),
                    reasoning, truncate(responseText, 2000)
            );

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.error("[ModelEvaluator] Error evaluating {}:{} on {}: {}",
                    provider, model, testCase.id(), e.getMessage());

            return new EvalResult(
                    testCase.id(), testCase.tier(), provider, model,
                    1.0, 1.0, 5.0, scorer.overallScore(1.0, 1.0, 5.0),
                    latencyMs, 0.0, 0, 0,
                    false, 0, testCase.validationPatterns().size(),
                    "ERROR: " + e.getMessage(), null
            );
        }
    }

    /**
     * Normalize cost scores across all models for each test case.
     * Cheapest = 10, most expensive = 1.
     */
    private void normalizeCosts(Map<String, List<EvalResult>> resultsByModel) {
        List<EvalTestCase> allTests = testCaseRegistry.allTestCases();

        for (int testIdx = 0; testIdx < allTests.size(); testIdx++) {
            final int idx = testIdx;
            String testId = allTests.get(idx).id();

            // Collect costs for this test across all models
            List<String> modelKeys = new ArrayList<>(resultsByModel.keySet());
            double[] costs = new double[modelKeys.size()];
            for (int m = 0; m < modelKeys.size(); m++) {
                costs[m] = resultsByModel.get(modelKeys.get(m)).get(idx).costUsd();
            }

            double[] costScores = scorer.normalizeCostScores(costs);

            // Update results with normalized cost scores
            for (int m = 0; m < modelKeys.size(); m++) {
                List<EvalResult> modelResults = resultsByModel.get(modelKeys.get(m));
                EvalResult old = modelResults.get(idx);
                double newCost = costScores[m];
                double newOverall = scorer.overallScore(old.accuracyScore(), old.speedScore(), newCost);
                String updatedReasoning = old.reasoning()
                        + String.format(" | Cost: $%.6f (score %.1f/10, normalized across %d models)",
                        old.costUsd(), newCost, modelKeys.size());

                modelResults.set(idx, old.toBuilder()
                        .costScore(newCost)
                        .overallScore(newOverall)
                        .reasoning(updatedReasoning)
                        .build());
            }
        }
    }

    private EvalReport buildReport(String evalId, Instant start,
                                   Map<String, List<EvalResult>> resultsByModel) {
        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();

        List<EvalReport.ModelReport> modelReports = new ArrayList<>();

        for (Map.Entry<String, List<EvalResult>> entry : resultsByModel.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            String provider = parts[0];
            String model = parts[1];
            List<EvalResult> results = entry.getValue();

            Map<AgentTier, EvalReport.TierScore> tierScores = new EnumMap<>(AgentTier.class);

            for (AgentTier tier : AgentTier.values()) {
                List<EvalResult> tierResults = results.stream()
                        .filter(r -> r.tier() == tier).toList();

                if (tierResults.isEmpty()) continue;

                double avgAccuracy = tierResults.stream().mapToDouble(EvalResult::accuracyScore).average().orElse(0);
                double avgSpeed = tierResults.stream().mapToDouble(EvalResult::speedScore).average().orElse(0);
                double avgCost = tierResults.stream().mapToDouble(EvalResult::costScore).average().orElse(0);
                double avgOverall = tierResults.stream().mapToDouble(EvalResult::overallScore).average().orElse(0);

                List<EvalReport.TestCaseResult> testResults = tierResults.stream()
                        .map(r -> new EvalReport.TestCaseResult(
                                r.testCaseId(), r.accuracyScore(), r.speedScore(), r.costScore(),
                                r.overallScore(), r.latencyMs(), r.costUsd(), r.passed()))
                        .toList();

                String reasoning = String.format(
                        "Avg accuracy=%.1f, speed=%.1f, cost=%.1f across %d tests. %d/%d passed.",
                        avgAccuracy, avgSpeed, avgCost, tierResults.size(),
                        tierResults.stream().filter(EvalResult::passed).count(), tierResults.size());

                tierScores.put(tier, new EvalReport.TierScore(
                        avgAccuracy, avgSpeed, avgCost, avgOverall, reasoning, testResults));
            }

            // Best-fit tier = tier with highest overall score
            AgentTier bestFit = tierScores.entrySet().stream()
                    .max(Comparator.comparingDouble(e -> e.getValue().overall()))
                    .map(Map.Entry::getKey)
                    .orElse(AgentTier.WORKHORSE);

            modelReports.add(new EvalReport.ModelReport(provider, model, tierScores, bestFit));
        }

        // Recommendations: best model per tier
        Map<AgentTier, EvalReport.Recommendation> recommendations = new EnumMap<>(AgentTier.class);
        for (AgentTier tier : AgentTier.values()) {
            EvalReport.ModelReport best = null;
            double bestScore = -1;

            for (EvalReport.ModelReport mr : modelReports) {
                EvalReport.TierScore ts = mr.tierScores().get(tier);
                if (ts != null && ts.overall() > bestScore) {
                    bestScore = ts.overall();
                    best = mr;
                }
            }

            if (best != null) {
                EvalReport.TierScore ts = best.tierScores().get(tier);
                recommendations.put(tier, new EvalReport.Recommendation(
                        best.provider(), best.model(), ts.overall(),
                        String.format("Best %s model: %s:%s (overall=%.1f, accuracy=%.1f, speed=%.1f, cost=%.1f)",
                                tier, best.provider(), best.model(),
                                ts.overall(), ts.accuracy(), ts.speed(), ts.cost())));
            }
        }

        return new EvalReport(evalId, Instant.now(), durationMs, modelReports, recommendations);
    }

    private void writeReport(EvalReport report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path reportFile = REPORT_DIR.resolve("eval-" + report.evaluationId() + ".json");
        mapper.writeValue(reportFile.toFile(), report);
        log.info("[ModelEvaluator] Report written to {}", reportFile);
    }

    private String buildReasoning(int matched, int total, long latencyMs, long ceiling,
                                  double accuracy, double speed) {
        return String.format("Patterns: %d/%d matched. Latency: %dms (ceiling %dms). " +
                "Accuracy=%.1f, Speed=%.1f", matched, total, latencyMs, ceiling, accuracy, speed);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "... [truncated]";
    }
}
