package com.navneet.executespec.agent;

import com.navneet.executespec.router.ModelTaskMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Queries recent task execution data to derive actionable failure patterns
 * for injection into the planner prompt. Returns an empty string when there
 * is insufficient data (< 20 completed runs) to avoid spurious warnings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FailurePatternService {

    private static final int MIN_SAMPLES = 20;
    private static final double HIGH_FAILURE_THRESHOLD = 0.30; // 30% failure rate

    private final ModelTaskMetricRepository metricRepository;

    /**
     * Queries the last {@code limit} model_task_metric rows and returns a markdown
     * bullet list of observed failure patterns, or an empty string if too few samples.
     */
    public String getRecentPatterns(int limit) {
        try {
            var metrics = metricRepository.findTopNOrderByCreatedAtDesc(limit);
            if (metrics.size() < MIN_SAMPLES) {
                return "";
            }

            List<String> patterns = new ArrayList<>();

            // Group by complexity → compute failure rate
            Map<String, List<Boolean>> byComplexity = metrics.stream()
                    .collect(Collectors.groupingBy(
                            m -> m.getComplexity() != null ? m.getComplexity() : "UNKNOWN",
                            Collectors.mapping(m -> !m.isValidationPassed(), Collectors.toList())));

            byComplexity.forEach((complexity, failures) -> {
                long failCount = failures.stream().filter(f -> f).count();
                double rate = (double) failCount / failures.size();
                if (rate >= HIGH_FAILURE_THRESHOLD) {
                    patterns.add(String.format(
                            "- **%s complexity** tasks fail %.0f%% of the time (%d/%d) — consider splitting HIGH tasks into MEDIUM, and MEDIUM into LOW",
                            complexity, rate * 100, failCount, failures.size()));
                }
            });

            // Group by iteration count to warn about tasks that consistently stall
            double avgIterations = metrics.stream()
                    .mapToInt(m -> m.getIterationCount())
                    .average()
                    .orElse(0);
            if (avgIterations > 20) {
                patterns.add(String.format(
                        "- Recent tasks average %.1f iterations (high) — reduce file count or complexity per task",
                        avgIterations));
            }

            // Warn on truncation rate
            long truncatedCount = metrics.stream().filter(m -> m.isWasTruncated()).count();
            double truncRate = (double) truncatedCount / metrics.size();
            if (truncRate >= 0.20) {
                patterns.add(String.format(
                        "- %.0f%% of recent tasks were truncated — assign LOW complexity more aggressively",
                        truncRate * 100));
            }

            if (patterns.isEmpty()) {
                return "";
            }

            return "**Recent failure patterns (last " + metrics.size() + " tasks):**\n"
                    + String.join("\n", patterns) + "\n";

        } catch (Exception e) {
            log.debug("[FailurePatternService] Could not load patterns: {}", e.getMessage());
            return "";
        }
    }
}
