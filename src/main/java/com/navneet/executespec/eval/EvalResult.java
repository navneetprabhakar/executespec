package com.navneet.executespec.eval;

import java.util.List;

import com.navneet.executespec.router.AgentTier;

/**
 * Result of evaluating a single model against a single test case.
 *
 * @param testCaseId the test case that was run
 * @param tier the tier being evaluated
 * @param provider LLM provider (anthropic, google, openai)
 * @param model the model string used
 * @param accuracyScore accuracy score (1-10)
 * @param speedScore speed score (1-10)
 * @param costScore cost score (1-10, normalized post-processing)
 * @param overallScore weighted overall: accuracy*W_a + speed*W_s + cost*W_c (tier-dependent)
 * @param latencyMs actual latency in milliseconds
 * @param costUsd actual cost in USD for this call
 * @param inputTokens input token count
 * @param outputTokens output token count
 * @param passed whether all validation patterns matched
 * @param matchedPatterns number of validation patterns that matched
 * @param totalPatterns total validation patterns in test case
 * @param reasoning human-readable scoring rationale
 * @param rawResponse the raw model response (truncated for report)
 * @param patternResults per-pattern match results for detailed debugging
 */
public record EvalResult(
    String testCaseId,
    AgentTier tier,
    String provider,
    String model,
    double accuracyScore,
    double speedScore,
    double costScore,
    double overallScore,
    long latencyMs,
    double costUsd,
    long inputTokens,
    long outputTokens,
    boolean passed,
    int matchedPatterns,
    int totalPatterns,
    String reasoning,
    String rawResponse,
    List<EvalReport.PatternResult> patternResults) {
  /**
   * Creates a mutable builder pre-filled from this result (for post-processing cost normalization).
   */
  public EvalResultBuilder toBuilder() {
    return new EvalResultBuilder(this);
  }

  public static class EvalResultBuilder {
    private String testCaseId;
    private AgentTier tier;
    private String provider;
    private String model;
    private double accuracyScore;
    private double speedScore;
    private double costScore;
    private double overallScore;
    private long latencyMs;
    private double costUsd;
    private long inputTokens;
    private long outputTokens;
    private boolean passed;
    private int matchedPatterns;
    private int totalPatterns;
    private String reasoning;
    private String rawResponse;
    private List<EvalReport.PatternResult> patternResults;

    EvalResultBuilder(EvalResult r) {
      this.testCaseId = r.testCaseId();
      this.tier = r.tier();
      this.provider = r.provider();
      this.model = r.model();
      this.accuracyScore = r.accuracyScore();
      this.speedScore = r.speedScore();
      this.costScore = r.costScore();
      this.overallScore = r.overallScore();
      this.latencyMs = r.latencyMs();
      this.costUsd = r.costUsd();
      this.inputTokens = r.inputTokens();
      this.outputTokens = r.outputTokens();
      this.passed = r.passed();
      this.matchedPatterns = r.matchedPatterns();
      this.totalPatterns = r.totalPatterns();
      this.reasoning = r.reasoning();
      this.rawResponse = r.rawResponse();
      this.patternResults = r.patternResults();
    }

    public EvalResultBuilder costScore(double costScore) {
      this.costScore = costScore;
      return this;
    }

    public EvalResultBuilder overallScore(double overallScore) {
      this.overallScore = overallScore;
      return this;
    }

    public EvalResultBuilder reasoning(String reasoning) {
      this.reasoning = reasoning;
      return this;
    }

    public EvalResult build() {
      return new EvalResult(
          testCaseId,
          tier,
          provider,
          model,
          accuracyScore,
          speedScore,
          costScore,
          overallScore,
          latencyMs,
          costUsd,
          inputTokens,
          outputTokens,
          passed,
          matchedPatterns,
          totalPatterns,
          reasoning,
          rawResponse,
          patternResults);
    }
  }
}
