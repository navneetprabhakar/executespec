package com.navneet.executespec.eval;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM-as-judge validator for complex eval test cases (saga tests).
 *
 * <p>Runs a LIGHTWEIGHT model (Haiku) as a second-pass judge after regex scoring. The judge
 * evaluates whether each validation criterion is present/correct in the model's response, catching
 * cases where regex patterns miss valid reasoning.
 *
 * <p>Final accuracy = max(regexAccuracy, llmJudgeAccuracy) — correct answers get credit regardless
 * of which validator catches them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmJudgeValidator {

  private final LlmRouter llmRouter;
  private final EvalScorer scorer;
  private final ModelRegistry modelRegistry;
  private static final ObjectMapper mapper = new ObjectMapper();

  /**
   * Run LLM judge on a response and return the judge's accuracy score.
   *
   * @param testCase the eval test case (must have patternLabels for criteria)
   * @param modelResponse the response being judged
   * @return accuracy score (1-10) from the judge, or -1.0 if the judge call fails
   */
  public double judge(EvalTestCase testCase, String modelResponse) {
    if (modelResponse == null || modelResponse.isBlank()) return 1.0;

    String judgePrompt = buildJudgePrompt(testCase, modelResponse);

    try {
      Prompt prompt = new Prompt(List.of(new UserMessage(judgePrompt)));
      LlmRouter.LlmResponse response =
          llmRouter.callDirect("anthropic", modelRegistry.lightweightModelFor("anthropic"), prompt);
      String judgeResponse = response.response().getResult().getOutput().getText();

      return parseJudgeResponse(judgeResponse, testCase.validationPatterns().size());
    } catch (Exception e) {
      log.warn("[LlmJudge] Judge call failed for {}: {}", testCase.id(), e.getMessage());
      return -1.0; // signal failure — caller will use regex score
    }
  }

  private String buildJudgePrompt(EvalTestCase testCase, String modelResponse) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are evaluating an AI model's response to a distributed systems question.\n");
    sb.append("Score each criterion 0 (absent/wrong) or 1 (present/correct).\n\n");
    sb.append("QUESTION:\n").append(testCase.userPrompt()).append("\n\n");
    sb.append("RESPONSE:\n").append(truncate(modelResponse, 12000)).append("\n\n");
    sb.append("CRITERIA:\n");

    List<String> labels = testCase.patternLabels();
    for (int i = 0; i < labels.size(); i++) {
      sb.append(i + 1).append(". ").append(labels.get(i)).append("\n");
    }

    sb.append("\nRespond ONLY with JSON: {\"scores\": [0,1,1,...], \"total\": N, \"max\": M}");
    return sb.toString();
  }

  private double parseJudgeResponse(String response, int totalCriteria) {
    try {
      // Strip markdown fences if present
      String cleaned = response.trim();
      if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
      else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
      if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
      cleaned = cleaned.trim();

      // Extract JSON object
      int jsonStart = cleaned.indexOf('{');
      if (jsonStart < 0) {
        log.warn("[LlmJudge] No JSON found in judge response");
        return -1.0;
      }
      cleaned = cleaned.substring(jsonStart);

      JsonNode root = mapper.readTree(cleaned);
      JsonNode scoresNode = root.get("scores");
      if (scoresNode == null || !scoresNode.isArray()) {
        log.warn("[LlmJudge] Missing or invalid 'scores' array in judge response");
        return -1.0;
      }

      int matched = 0;
      int total = scoresNode.size();
      for (int i = 0; i < total; i++) {
        if (scoresNode.get(i).asInt(0) >= 1) matched++;
      }

      // Use same scoring curve as regex accuracy
      double ratio = total > 0 ? (double) matched / total : 1.0;
      double score = 1.0 + 9.0 * Math.pow(ratio, 1.3);

      log.info(
          "[LlmJudge] Judge scored {}/{} criteria → accuracy {}",
          matched,
          total,
          String.format("%.1f", score));
      return Math.max(1.0, Math.min(10.0, score));
    } catch (Exception e) {
      log.warn("[LlmJudge] Failed to parse judge response: {}", e.getMessage());
      return -1.0;
    }
  }

  private String truncate(String s, int maxLen) {
    if (s == null) return null;
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "... [truncated]";
  }
}
