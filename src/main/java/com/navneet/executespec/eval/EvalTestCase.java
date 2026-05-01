package com.navneet.executespec.eval;

import java.util.List;
import java.util.regex.Pattern;

import com.navneet.executespec.router.AgentTier;

/**
 * A single evaluation test case that scores an LLM model on a specific tier capability.
 *
 * @param id unique test case ID (e.g. "planning-plan-generation")
 * @param tier the agent tier this test targets
 * @param description human-readable description of what the test evaluates
 * @param systemPrompt optional system prompt (null if not needed)
 * @param userPrompt the user prompt to send to the model
 * @param validationPatterns regex patterns the response must match for accuracy scoring
 * @param requiredJsonKeys JSON keys required in the response (for structural validation)
 * @param latencyCeilingMs max acceptable latency for this tier (used for speed scoring)
 * @param maxResponseLength max acceptable response length in chars (0 = no limit)
 * @param validationMode REGEX_ONLY or REGEX_AND_LLM (LLM judge as second pass)
 */
public record EvalTestCase(
    String id,
    AgentTier tier,
    String description,
    String systemPrompt,
    String userPrompt,
    List<Pattern> validationPatterns,
    List<String> patternLabels,
    List<String> requiredJsonKeys,
    long latencyCeilingMs,
    int maxResponseLength,
    ValidationMode validationMode) {

  public enum ValidationMode {
    REGEX_ONLY,
    REGEX_AND_LLM
  }

  /**
   * Convenience constructor without pattern labels (auto-generates P1, P2, ...). Defaults to
   * REGEX_ONLY validation.
   */
  public EvalTestCase(
      String id,
      AgentTier tier,
      String description,
      String systemPrompt,
      String userPrompt,
      List<Pattern> validationPatterns,
      List<String> requiredJsonKeys,
      long latencyCeilingMs,
      int maxResponseLength) {
    this(
        id,
        tier,
        description,
        systemPrompt,
        userPrompt,
        validationPatterns,
        generateLabels(validationPatterns.size()),
        requiredJsonKeys,
        latencyCeilingMs,
        maxResponseLength,
        ValidationMode.REGEX_ONLY);
  }

  /** Convenience constructor without pattern labels but with validationMode. */
  public EvalTestCase(
      String id,
      AgentTier tier,
      String description,
      String systemPrompt,
      String userPrompt,
      List<Pattern> validationPatterns,
      List<String> requiredJsonKeys,
      long latencyCeilingMs,
      int maxResponseLength,
      ValidationMode validationMode) {
    this(
        id,
        tier,
        description,
        systemPrompt,
        userPrompt,
        validationPatterns,
        generateLabels(validationPatterns.size()),
        requiredJsonKeys,
        latencyCeilingMs,
        maxResponseLength,
        validationMode);
  }

  /** Convenience constructor with pattern labels but default REGEX_ONLY validation. */
  public EvalTestCase(
      String id,
      AgentTier tier,
      String description,
      String systemPrompt,
      String userPrompt,
      List<Pattern> validationPatterns,
      List<String> patternLabels,
      List<String> requiredJsonKeys,
      long latencyCeilingMs,
      int maxResponseLength) {
    this(
        id,
        tier,
        description,
        systemPrompt,
        userPrompt,
        validationPatterns,
        patternLabels,
        requiredJsonKeys,
        latencyCeilingMs,
        maxResponseLength,
        ValidationMode.REGEX_ONLY);
  }

  private static List<String> generateLabels(int count) {
    List<String> labels = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) labels.add("P" + (i + 1));
    return labels;
  }
}
