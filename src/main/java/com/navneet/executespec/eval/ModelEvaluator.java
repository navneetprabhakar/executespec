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
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Orchestrates model evaluation: runs all models against all test cases,
 * scores results, normalizes costs, and generates the final report.
 *
 * <p>Total calls: 6 models x 14 test cases = 84 LLM calls.
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
    private final LlmJudgeValidator llmJudge;

    /** Max time for a single LLM call before it's killed and scored as failed. */
    private static final Duration PER_CALL_TIMEOUT = Duration.ofSeconds(240);

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

                // Save raw output for post-mortem analysis
                saveRawOutput(evalId, provider, model, testCase.id(), result.rawResponse());
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

        // Load previous report for cross-run comparison
        EvalReport previousReport = loadMostRecentReport();

        // Build report
        EvalReport report = buildReport(evalId, start, resultsByModel, previousReport);

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
            return buildFailedResult(testCase, provider, model, latencyMs,
                    "TIMEOUT: exceeded " + PER_CALL_TIMEOUT.toSeconds() + "s limit");
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.error("[ModelEvaluator] Unexpected error: {}:{} on {}: {}",
                    provider, model, testCase.id(), e.getMessage());
            return buildFailedResult(testCase, provider, model, latencyMs,
                    "ERROR: " + e.getMessage());
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
                reason, null, List.of()
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
                log.warn("[ModelEvaluator] Token extraction failed for {}/{}: {}",
                        provider, model, e.getMessage());
                inputTokens = -1;  // sentinel: unknown tokens
                outputTokens = -1;
            }

            // Unknown tokens → flag cost as unknown (-1); scorer will assign neutral 5.0
            double costUsd;
            if (inputTokens < 0 || outputTokens < 0) {
                costUsd = -1.0;
            } else {
                costUsd = costEstimator.computeCost(provider, model, inputTokens, outputTokens);
            }
            double accuracy = scorer.scoreAccuracy(responseText, testCase.validationPatterns(), testCase.requiredJsonKeys());
            double speed = scorer.scoreSpeed(latencyMs, testCase.latencyCeilingMs());
            int matched = scorer.countMatchedPatterns(responseText, testCase.validationPatterns());

            // Compute per-pattern match results for debugging
            List<EvalReport.PatternResult> patternResults = computePatternResults(
                    responseText, testCase.validationPatterns(), testCase.patternLabels());

            // LLM-as-judge second pass for REGEX_AND_LLM tests (saga tests)
            if (testCase.validationMode() == EvalTestCase.ValidationMode.REGEX_AND_LLM) {
                double judgeAccuracy = llmJudge.judge(testCase, responseText);
                if (judgeAccuracy > 0 && judgeAccuracy > accuracy) {
                    log.info("[ModelEvaluator] LLM judge upgraded accuracy for {}/{}: regex={} → judge={}",
                            provider + ":" + model, testCase.id(),
                            String.format("%.1f", accuracy), String.format("%.1f", judgeAccuracy));
                    accuracy = judgeAccuracy;
                }
            }

            // Length penalty for LIGHTWEIGHT tests (graduated: 1.5x→-1pt, 2x→-2pt, max -3pt)
            double lenPenalty = scorer.lengthPenalty(responseText, testCase.maxResponseLength());
            if (lenPenalty > 0) {
                accuracy = Math.max(1.0, accuracy - lenPenalty);
            }

            boolean passed = matched == testCase.validationPatterns().size();
            String reasoning = buildReasoning(matched, testCase.validationPatterns().size(),
                    latencyMs, testCase.latencyCeilingMs(), accuracy, speed);

            return new EvalResult(
                    testCase.id(), testCase.tier(), provider, model,
                    accuracy, speed, 5.0, // cost placeholder — normalized later
                    scorer.overallScore(accuracy, speed, 5.0, testCase.tier()),
                    latencyMs, costUsd, inputTokens, outputTokens,
                    passed, matched, testCase.validationPatterns().size(),
                    reasoning, responseText, patternResults
            );

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.error("[ModelEvaluator] Error evaluating {}:{} on {}: {}",
                    provider, model, testCase.id(), e.getMessage());

            return buildFailedResult(testCase, provider, model, latencyMs,
                    "ERROR: " + e.getMessage());
        }
    }

    /**
     * Normalize cost scores per tier (not per test case).
     * Uses average cost per model within each tier so the same model gets a
     * consistent cost score across all tests in that tier.
     */
    private void normalizeCosts(Map<String, List<EvalResult>> resultsByModel) {
        List<String> modelKeys = new ArrayList<>(resultsByModel.keySet());

        for (AgentTier tier : AgentTier.values()) {
            // Compute average cost per model for this tier
            double[] avgCosts = new double[modelKeys.size()];
            for (int m = 0; m < modelKeys.size(); m++) {
                List<EvalResult> tierResults = resultsByModel.get(modelKeys.get(m)).stream()
                        .filter(r -> r.tier() == tier)
                        .toList();
                if (tierResults.isEmpty()) {
                    avgCosts[m] = 0;
                    continue;
                }
                // Exclude unknown (-1) and zero costs from average
                double totalCost = tierResults.stream()
                        .mapToDouble(EvalResult::costUsd)
                        .filter(c -> c > 0)
                        .sum();
                long validCount = tierResults.stream()
                        .mapToDouble(EvalResult::costUsd)
                        .filter(c -> c > 0)
                        .count();
                avgCosts[m] = validCount > 0 ? totalCost / validCount : tierResults.get(0).costUsd();
            }

            double[] costScores = scorer.normalizeCostScores(avgCosts);

            // Apply the same normalized cost score to ALL tests for this model in this tier
            for (int m = 0; m < modelKeys.size(); m++) {
                double tierCostScore = costScores[m];
                List<EvalResult> modelResults = resultsByModel.get(modelKeys.get(m));

                for (int idx = 0; idx < modelResults.size(); idx++) {
                    EvalResult old = modelResults.get(idx);
                    if (old.tier() != tier) continue;

                    double newOverall = scorer.overallScore(old.accuracyScore(), old.speedScore(), tierCostScore, tier);
                    String updatedReasoning = old.reasoning()
                            + String.format(" | Cost: $%.6f (tier-normalized score %.1f/10 across %d models)",
                            old.costUsd(), tierCostScore, modelKeys.size());

                    modelResults.set(idx, old.toBuilder()
                            .costScore(tierCostScore)
                            .overallScore(newOverall)
                            .reasoning(updatedReasoning)
                            .build());
                }
            }
        }
    }

    private EvalReport buildReport(String evalId, Instant start,
                                   Map<String, List<EvalResult>> resultsByModel,
                                   EvalReport previousReport) {
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
                // Use tier-aware weights for the aggregate overall score
                double avgOverall = scorer.overallScore(avgAccuracy, avgSpeed, avgCost, tier);

                List<EvalReport.TestCaseResult> testResults = tierResults.stream()
                        .map(r -> new EvalReport.TestCaseResult(
                                r.testCaseId(), r.accuracyScore(), r.speedScore(), r.costScore(),
                                r.overallScore(), r.latencyMs(), r.costUsd(), r.passed(),
                                r.patternResults() != null ? r.patternResults() : List.of()))
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

        // Disqualification checks and recommendations
        Map<AgentTier, EvalReport.Recommendation> recommendations = new EnumMap<>(AgentTier.class);
        List<EvalReport.Disqualification> disqualifications = new ArrayList<>();

        for (AgentTier tier : AgentTier.values()) {
            EvalReport.ModelReport best = null;
            double bestScore = -1;

            for (EvalReport.ModelReport mr : modelReports) {
                EvalReport.TierScore ts = mr.tierScores().get(tier);
                if (ts == null) continue;

                // Disqualification: PLANNING tier requires >= 50% pass rate
                if (tier == AgentTier.PLANNING) {
                    long passed = ts.testResults().stream().filter(EvalReport.TestCaseResult::passed).count();
                    long total = ts.testResults().size();
                    double passRate = total > 0 ? (double) passed / total : 0;

                    if (passRate < 0.50) {
                        String reason = String.format("Pass rate %.0f%% (%d/%d) below 50%% minimum for PLANNING",
                                passRate * 100, passed, total);
                        disqualifications.add(new EvalReport.Disqualification(
                                mr.provider(), mr.model(), tier, reason, ts.overall()));
                        log.info("[ModelEvaluator] DISQUALIFIED {}:{} from {} — {} (would have scored %.1f)",
                                mr.provider(), mr.model(), tier, reason, String.format("%.1f", ts.overall()));
                        continue; // skip this model for this tier's recommendation
                    }

                    // Also disqualify if any single PLANNING test accuracy < 6.0
                    Optional<EvalReport.TestCaseResult> weakTest = ts.testResults().stream()
                            .filter(t -> t.accuracy() < 6.0)
                            .findFirst();
                    if (weakTest.isPresent()) {
                        String reason = String.format("Test %s accuracy %.1f below 6.0 floor",
                                weakTest.get().testCaseId(), weakTest.get().accuracy());
                        disqualifications.add(new EvalReport.Disqualification(
                                mr.provider(), mr.model(), tier, reason, ts.overall()));
                        log.info("[ModelEvaluator] DISQUALIFIED {}:{} from {} — {} (would have scored {})",
                                mr.provider(), mr.model(), tier, reason, String.format("%.1f", ts.overall()));
                        continue;
                    }
                }

                if (ts.overall() > bestScore) {
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

        // Cross-run comparison
        List<EvalReport.CrossRunDelta> crossRunDeltas = computeCrossRunDeltas(modelReports, previousReport);

        return new EvalReport(evalId, Instant.now(), durationMs, modelReports, recommendations, disqualifications, crossRunDeltas);
    }

    /**
     * Load the most recent eval report from the reports directory.
     * Returns null if no previous reports exist or on read error.
     */
    private EvalReport loadMostRecentReport() {
        try {
            if (!Files.isDirectory(REPORT_DIR)) return null;
            Optional<Path> latest = Files.list(REPORT_DIR)
                    .filter(p -> p.getFileName().toString().startsWith("eval-") && p.toString().endsWith(".json"))
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }));
            if (latest.isEmpty()) return null;
            EvalReport report = mapper.readValue(latest.get().toFile(), EvalReport.class);
            log.info("[ModelEvaluator] Loaded previous report {} for cross-run comparison", report.evaluationId());
            return report;
        } catch (Exception e) {
            log.warn("[ModelEvaluator] Could not load previous report for comparison: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Compute per-model, per-tier accuracy deltas against the previous evaluation run.
     */
    private List<EvalReport.CrossRunDelta> computeCrossRunDeltas(
            List<EvalReport.ModelReport> currentReports, EvalReport previousReport) {
        List<EvalReport.CrossRunDelta> deltas = new ArrayList<>();

        if (previousReport == null) {
            // No previous run — mark all models as NEW
            for (EvalReport.ModelReport mr : currentReports) {
                for (Map.Entry<AgentTier, EvalReport.TierScore> entry : mr.tierScores().entrySet()) {
                    deltas.add(new EvalReport.CrossRunDelta(
                            mr.provider(), mr.model(), entry.getKey(),
                            0.0, entry.getValue().accuracy(), entry.getValue().accuracy(),
                            "NEW", null));
                }
            }
            return deltas;
        }

        // Build lookup: "provider:model:tier" → previous accuracy
        Map<String, Double> prevAccuracy = new HashMap<>();
        for (EvalReport.ModelReport pmr : previousReport.modelResults()) {
            for (Map.Entry<AgentTier, EvalReport.TierScore> entry : pmr.tierScores().entrySet()) {
                prevAccuracy.put(pmr.provider() + ":" + pmr.model() + ":" + entry.getKey(), entry.getValue().accuracy());
            }
        }

        for (EvalReport.ModelReport mr : currentReports) {
            for (Map.Entry<AgentTier, EvalReport.TierScore> entry : mr.tierScores().entrySet()) {
                String key = mr.provider() + ":" + mr.model() + ":" + entry.getKey();
                double currentAcc = entry.getValue().accuracy();
                Double prevAcc = prevAccuracy.get(key);

                String flag;
                double prev;
                double delta;

                if (prevAcc == null) {
                    flag = "NEW";
                    prev = 0.0;
                    delta = currentAcc;
                } else if (prevAcc < 1.0) {
                    // Previous score near zero indicates infra failure
                    flag = "INFRA_RECOVERY";
                    prev = prevAcc;
                    delta = currentAcc - prevAcc;
                } else {
                    prev = prevAcc;
                    delta = currentAcc - prevAcc;
                    if (delta < -1.0) flag = "REGRESSION";
                    else if (delta > 1.0) flag = "IMPROVEMENT";
                    else flag = "OK";
                }

                deltas.add(new EvalReport.CrossRunDelta(
                        mr.provider(), mr.model(), entry.getKey(),
                        prev, currentAcc, delta, flag, previousReport.evaluationId()));

                if (!"OK".equals(flag) && !"NEW".equals(flag)) {
                    log.info("[ModelEvaluator] Cross-run {} {}:{} {} — prev={} curr={} delta={}",
                            flag, mr.provider(), mr.model(), entry.getKey(),
                            String.format("%.1f", prev), String.format("%.1f", currentAcc),
                            String.format("%+.1f", delta));
                }
            }
        }

        return deltas;
    }

    private void writeReport(EvalReport report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path reportFile = REPORT_DIR.resolve("eval-" + report.evaluationId() + ".json");
        mapper.writeValue(reportFile.toFile(), report);
        log.info("[ModelEvaluator] Report written to {}", reportFile);
    }

    /**
     * Save the raw (non-truncated) LLM response to eval-outputs/{evalId}/{provider}-{model}/{testCaseId}.txt.
     * Best-effort — failures are logged but don't affect the evaluation.
     */
    private void saveRawOutput(String evalId, String provider, String model,
                               String testCaseId, String rawResponse) {
        if (rawResponse == null) return;
        try {
            String safeModel = model.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path outputDir = Path.of("eval-outputs", evalId, provider + "-" + safeModel);
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve(testCaseId + ".txt");
            Files.writeString(outputFile, rawResponse);
        } catch (IOException e) {
            log.warn("[ModelEvaluator] Failed to save raw output for {}/{}:{}: {}",
                    testCaseId, provider, model, e.getMessage());
        }
    }

    /**
     * Compute per-pattern match results for detailed debugging in reports.
     */
    private List<EvalReport.PatternResult> computePatternResults(
            String response, List<java.util.regex.Pattern> patterns, List<String> labels) {
        List<EvalReport.PatternResult> results = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            String label = (labels != null && i < labels.size()) ? labels.get(i) : "P" + (i + 1);
            boolean matched = response != null && patterns.get(i).matcher(response).find();
            results.add(new EvalReport.PatternResult(label, matched));
        }
        return results;
    }

    /**
     * Build an EvalResult for a failed test case (timeout, error, or skip).
     * Assigns minimum accuracy/speed scores (1.0) and neutral cost (5.0).
     */
    private EvalResult buildFailedResult(EvalTestCase testCase, String provider, String model,
                                         long latencyMs, String reason) {
        return new EvalResult(
                testCase.id(), testCase.tier(), provider, model,
                1.0, 1.0, 5.0, scorer.overallScore(1.0, 1.0, 5.0),
                latencyMs, 0.0, 0, 0,
                false, 0, testCase.validationPatterns().size(),
                reason, null, List.of()
        );
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
