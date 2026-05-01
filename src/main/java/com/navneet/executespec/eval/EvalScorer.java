package com.navneet.executespec.eval;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.AgentTier;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Scoring algorithms for the model evaluation system.
 *
 * <p>Three dimensions:
 *
 * <ul>
 *   <li><b>Accuracy (50%)</b> — pattern matching + structural validation
 *   <li><b>Speed (25%)</b> — latency relative to tier budget
 *   <li><b>Cost (25%)</b> — normalized across models per tier (post-processing)
 * </ul>
 */
@Slf4j
@Component
public class EvalScorer {

  private static final ObjectMapper mapper = new ObjectMapper();

  /**
   * Score accuracy based on pattern matching and structural JSON validation.
   *
   * <p>Uses a continuous scoring curve to avoid binary pass/fail cliffs:
   *
   * <ul>
   *   <li>100% patterns matched → 10.0
   *   <li>80% patterns matched → 8.0
   *   <li>50% patterns matched → 5.5 (slightly penalized via quadratic curve)
   *   <li>25% patterns matched → 3.2
   *   <li>0% patterns matched → 1.0
   * </ul>
   *
   * @param response the raw LLM response
   * @param validationPatterns regex patterns the response should match
   * @param requiredJsonKeys JSON keys that must be present (empty list = no structural check)
   * @return accuracy score 1-10
   */
  public double scoreAccuracy(
      String response, List<Pattern> validationPatterns, List<String> requiredJsonKeys) {
    if (response == null || response.isBlank()) return 1.0;

    int matched = 0;
    int total = validationPatterns.size();

    for (Pattern pattern : validationPatterns) {
      if (pattern.matcher(response).find()) {
        matched++;
      }
    }

    // Quadratic curve: rewards high match rates, penalizes low ones more sharply
    // f(ratio) = 1 + 9 * ratio^1.3  (slight superlinear penalty for missing patterns)
    double ratio = total > 0 ? (double) matched / total : 1.0;
    double patternScore = 1.0 + 9.0 * Math.pow(ratio, 1.3);

    // Structural JSON validation deductions
    double deduction = 0;
    if (!requiredJsonKeys.isEmpty()) {
      String jsonStr = extractJson(response);
      if (jsonStr == null) {
        deduction += 2.0; // JSON not parseable
      } else {
        try {
          JsonNode root = mapper.readTree(jsonStr);
          int missingKeys = 0;
          for (String key : requiredJsonKeys) {
            if (!root.has(key)) missingKeys++;
          }
          // Proportional deduction: missing 1 of 4 keys = 0.75 deduction
          deduction += 3.0 * ((double) missingKeys / requiredJsonKeys.size());
        } catch (Exception e) {
          deduction += 2.0;
        }
      }
    }

    return Math.max(1.0, Math.min(10.0, patternScore - deduction));
  }

  /**
   * Score speed relative to the tier's latency budget. Score 10 at <=50% of budget, linearly down
   * to 1 at 200% of budget.
   */
  public double scoreSpeed(long latencyMs, long latencyCeilingMs) {
    if (latencyCeilingMs <= 0) return 5.0;
    double ratio = (double) latencyMs / latencyCeilingMs;
    double score = 10.0 - (ratio - 0.5) * 6.0;
    return Math.max(1.0, Math.min(10.0, score));
  }

  /**
   * Normalize cost scores across all models for a single test case. Uses log-scale normalization to
   * prevent over-penalizing expensive models.
   *
   * <p>Linear normalization compresses the range when costs differ by 10-60x (e.g. Haiku $0.001 vs
   * Opus $0.06). Log-scale respects the cost ratio while keeping scores in a useful 1-10 range:
   *
   * <ul>
   *   <li>Cheapest model → 10.0
   *   <li>2x more expensive → 8.5
   *   <li>4x more expensive → 7.0
   *   <li>8x more expensive → 5.5
   *   <li>16x more expensive → 4.0
   *   <li>32x more expensive → 2.5
   *   <li>64x+ more expensive → 1.0
   * </ul>
   *
   * @param costs array of costs (one per model). Negative values (-1) indicate unknown cost (token
   *     extraction failed) and receive a neutral score of 5.0.
   * @return array of scores (1-10)
   */
  public double[] normalizeCostScores(double[] costs) {
    if (costs.length == 0) return new double[0];
    if (costs.length == 1) {
      return new double[] {costs[0] < 0 ? 5.0 : 10.0};
    }

    double min = Double.MAX_VALUE;
    for (double c : costs) {
      if (c > 0 && c < min) min = c;
    }
    // If all costs are zero or unknown, everyone gets 10 (or 5 for unknown)
    if (min == Double.MAX_VALUE) {
      double[] scores = new double[costs.length];
      for (int i = 0; i < costs.length; i++) {
        scores[i] = costs[i] < 0 ? 5.0 : 10.0;
      }
      return scores;
    }

    double[] scores = new double[costs.length];
    for (int i = 0; i < costs.length; i++) {
      if (costs[i] < 0) {
        scores[i] = 5.0; // unknown cost — neutral, neither penalized nor rewarded
      } else if (costs[i] <= 0) {
        scores[i] = 10.0; // free = max score
      } else {
        double ratio = costs[i] / min;
        // Gentler log2 scale with 1.5 multiplier for wider dynamic range:
        // 1x→10, 2x→8.5, 4x→7, 8x→5.5, 16x→4, 32x→2.5, 64x→1
        double logPenalty = 1.5 * (Math.log(ratio) / Math.log(2));
        scores[i] = Math.max(1.0, Math.min(10.0, 10.0 - logPenalty));
      }
    }
    return scores;
  }

  /**
   * Compute overall weighted score with default (WORKHORSE) weights. accuracy * 0.50 + speed * 0.25
   * + cost * 0.25
   */
  public double overallScore(double accuracy, double speed, double cost) {
    return overallScore(accuracy, speed, cost, AgentTier.WORKHORSE);
  }

  /**
   * Compute overall weighted score with tier-dependent weights.
   *
   * <ul>
   *   <li>PLANNING: accuracy=0.70, speed=0.15, cost=0.15 (wrong plan = wasted pipeline)
   *   <li>WORKHORSE: accuracy=0.50, speed=0.25, cost=0.25 (balanced)
   *   <li>LIGHTWEIGHT: accuracy=0.40, speed=0.30, cost=0.30 (latency-sensitive)
   * </ul>
   */
  public double overallScore(double accuracy, double speed, double cost, AgentTier tier) {
    return switch (tier) {
      case PLANNING -> accuracy * 0.70 + speed * 0.15 + cost * 0.15;
      case WORKHORSE -> accuracy * 0.50 + speed * 0.25 + cost * 0.25;
      case LIGHTWEIGHT -> accuracy * 0.40 + speed * 0.30 + cost * 0.30;
    };
  }

  /** Count how many validation patterns match the response. */
  public int countMatchedPatterns(String response, List<Pattern> patterns) {
    if (response == null) return 0;
    int count = 0;
    for (Pattern p : patterns) {
      if (p.matcher(response).find()) count++;
    }
    return count;
  }

  /** Check if response length exceeds the max (for LIGHTWEIGHT tests). */
  public boolean isWithinLengthLimit(String response, int maxLength) {
    return maxLength <= 0 || response == null || response.length() <= maxLength;
  }

  /**
   * Compute a length penalty for verbose responses.
   *
   * <ul>
   *   <li>Within limit → 0.0
   *   <li>1.5x over → -1.0 point
   *   <li>2x over → -2.0 points
   *   <li>Capped at -3.0
   * </ul>
   */
  public double lengthPenalty(String response, int maxLength) {
    if (maxLength <= 0 || response == null || response.length() <= maxLength) return 0.0;
    double overageRatio = (double) response.length() / maxLength;
    return Math.min(3.0, (overageRatio - 1.0) * 2.0);
  }

  /**
   * Latency ceiling for each agent tier.
   *
   * <p>PLANNING: 120s (Sonnet needs 80-170s on saga tests; 30s was unachievable) WORKHORSE: 45s
   * (unchanged) LIGHTWEIGHT: 10s (5s was too aggressive — only Mini met it)
   */
  public static long latencyCeilingMs(AgentTier tier) {
    return switch (tier) {
      case PLANNING -> 120_000;
      case WORKHORSE -> 45_000;
      case LIGHTWEIGHT -> 10_000;
    };
  }

  /** Extract first JSON object or array from a response that may contain markdown fences. */
  private String extractJson(String response) {
    if (response == null) return null;
    // Strip markdown code fences
    String cleaned = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");
    // Find first { or [
    int braceStart = cleaned.indexOf('{');
    int bracketStart = cleaned.indexOf('[');
    int start;
    char open, close;
    if (braceStart < 0 && bracketStart < 0) return null;
    if (braceStart < 0) {
      start = bracketStart;
      open = '[';
      close = ']';
    } else if (bracketStart < 0) {
      start = braceStart;
      open = '{';
      close = '}';
    } else if (braceStart < bracketStart) {
      start = braceStart;
      open = '{';
      close = '}';
    } else {
      start = bracketStart;
      open = '[';
      close = ']';
    }

    int depth = 0;
    for (int i = start; i < cleaned.length(); i++) {
      if (cleaned.charAt(i) == open) depth++;
      else if (cleaned.charAt(i) == close) depth--;
      if (depth == 0) return cleaned.substring(start, i + 1);
    }
    return cleaned.substring(start); // unclosed — return what we have
  }
}
