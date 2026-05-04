package com.navneet.executespec.prreview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.v2.agent.LlmRequest;
import com.navneet.executespec.v2.agent.LlmRouterV2;
import com.navneet.executespec.v2.cost.RunCostTracker;
import com.navneet.executespec.v2.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PR Review Agent — runs a senior-engineer-style review against a PR diff and emits a structured
 * {@link AgentReview} via the {@code submitReview} tool. PLANNING-tier model (Sonnet 4.6 on
 * Anthropic, gpt-5.4 on OpenAI, gemini-3-pro-preview on Gemini) for quality.
 *
 * <p>Single-call agent: no multi-turn loop, no shell, no file mutation. The agent emits exactly one
 * {@code submitReview} tool call and the response is the review. If the LLM returns text-only (no
 * tool call), the service treats it as failure and retries once with a stricter prompt.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PrReviewAgent {

  private static final String PROMPT_PATH = "prompts/codereview/pr-review-agent.md";

  private final LlmRouterV2 router;
  private final ModelResolver modelResolver;
  private final ObjectMapper objectMapper;
  private final RunCostTracker costTracker;

  /**
   * Run the agent against the supplied diff + PR metadata. Returns the parsed agent output; caller
   * is responsible for persistence + GitHub mirroring.
   *
   * @param req review inputs (diff, PR metadata, provider hint)
   * @return parsed {@link AgentReview} with verdict + comments
   * @throws PrReviewAgentException on LLM failure, malformed tool call, or no tool call at all
   */
  public AgentReview review(AgentRequest req) throws PrReviewAgentException {
    long start = System.currentTimeMillis();
    String provider = req.provider() == null ? "anthropic" : req.provider().toLowerCase();
    LlmProvider providerEnum;
    try {
      providerEnum = LlmProvider.valueOf(provider.toUpperCase());
    } catch (Exception e) {
      throw new PrReviewAgentException("unknown provider: " + provider);
    }
    // ModelResolver looks up the tier from application.yml's agent-tier-map by simple class name;
    // PrReviewAgent is registered as PLANNING there. The lookup falls back to WORKHORSE with a
    // WARN log if the YAML is ever missing the entry — boot won't fail, just degrade.
    ResolvedModel resolved = modelResolver.resolve("PrReviewAgent", providerEnum);

    String systemPrompt;
    try {
      systemPrompt =
          StreamUtils.copyToString(
              new ClassPathResource(PROMPT_PATH).getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new PrReviewAgentException("missing prompt resource: " + PROMPT_PATH);
    }

    String userPrompt = composeUserPrompt(req);

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    messages.add(new UserMessage(userPrompt));

    SubmitReviewToolCallback submitTool = new SubmitReviewToolCallback();

    ChatResponse resp;
    try {
      resp =
          router.call(
              LlmRequest.builder()
                  .provider(provider)
                  .model(resolved.getModel())
                  .messages(messages)
                  .temperature(0.0)
                  .maxTokens(8192)
                  .toolsOverride(List.of(submitTool))
                  // Cost tracker scoping — runId/taskId convention used to attribute the
                  // tokens to a synthetic "pr-review-{prReviewId}" bucket so per-tier
                  // billing reports can pivot on it.
                  .context(
                      new ExecutionContext(
                          "pr-review-" + req.prReviewId(), "review", java.nio.file.Paths.get("/")))
                  .build());
    } catch (Exception e) {
      throw new PrReviewAgentException("LLM dispatch failed: " + e.getMessage());
    }

    // Cost attribution — record token + USD into a per-review bucket. The service reads the
    // snapshot to populate pr_review.cost_usd / total_input_tokens / total_output_tokens.
    String runIdForCost = "pr-review-" + req.prReviewId();
    costTracker.record(runIdForCost, provider, resolved.getModel(), resp);

    // Parse the agent's submitReview tool call out of the response.
    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
    for (var gen : resp.getResults()) {
      if (gen.getOutput().getToolCalls() != null) calls.addAll(gen.getOutput().getToolCalls());
    }
    AssistantMessage.ToolCall submit =
        calls.stream().filter(tc -> "submitReview".equals(tc.name())).findFirst().orElse(null);
    if (submit == null) {
      throw new PrReviewAgentException(
          "agent returned no submitReview tool call (model="
              + resolved.getModel()
              + ", calls="
              + calls.size()
              + ")");
    }

    AgentReview parsed;
    try {
      parsed = parseSubmission(submit.arguments());
    } catch (Exception e) {
      throw new PrReviewAgentException("malformed submitReview arguments: " + e.getMessage());
    }

    long elapsed = System.currentTimeMillis() - start;
    log.info(
        "PrReviewAgent done provider={} model={} verdict={} comments={} elapsedMs={}",
        provider,
        resolved.getModel(),
        parsed.verdict(),
        parsed.comments().size(),
        elapsed);
    parsed = parsed.withDurationMs(elapsed);
    parsed = parsed.withProviderModel(provider, resolved.getModel());
    return parsed;
  }

  private String composeUserPrompt(AgentRequest req) {
    StringBuilder b = new StringBuilder();
    b.append("# PR metadata\n\n");
    b.append("Repo: ").append(req.repoFullName()).append('\n');
    b.append("PR #").append(req.prNumber()).append('\n');
    b.append("Head SHA: ").append(req.headSha()).append('\n');
    b.append("Base branch: ").append(req.baseBranch()).append('\n');
    b.append("Head branch: ").append(req.headBranch()).append('\n');
    if (req.title() != null && !req.title().isBlank()) {
      b.append("\n## Title\n").append(req.title()).append('\n');
    }
    if (req.body() != null && !req.body().isBlank()) {
      b.append("\n## Body\n").append(req.body()).append('\n');
    }
    b.append("\n## Stats\n");
    b.append("Files changed: ").append(req.changedFiles()).append('\n');
    b.append("Additions: +").append(req.additions()).append('\n');
    b.append("Deletions: −").append(req.deletions()).append('\n');
    b.append("\n# Unified diff\n\n```diff\n");
    b.append(req.diff());
    b.append("\n```\n");
    return b.toString();
  }

  /** Convert the raw JSON args from the {@code submitReview} tool call into our typed shape. */
  private AgentReview parseSubmission(String argsJson) throws Exception {
    JsonNode root = objectMapper.readTree(argsJson);
    String verdict = root.path("verdict").asText("COMMENT").toUpperCase();
    if (!java.util.Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT").contains(verdict)) {
      verdict = "COMMENT";
    }
    String summary = root.path("summary").asText("");

    List<AgentReviewComment> comments = new ArrayList<>();
    JsonNode arr = root.path("comments");
    if (arr.isArray()) {
      for (JsonNode c : arr) {
        String filePath = c.path("filePath").asText(null);
        Integer lineNumber = c.has("lineNumber") ? c.get("lineNumber").asInt() : null;
        String side = c.path("side").asText("RIGHT").toUpperCase();
        if (!"LEFT".equals(side) && !"RIGHT".equals(side)) side = "RIGHT";
        String category = c.path("category").asText(null);
        String severity = c.path("severity").asText(null);
        String body = c.path("body").asText("");
        if (filePath == null || lineNumber == null || body.isBlank()) {
          // Skip malformed comments rather than fail the whole review.
          log.warn(
              "PrReviewAgent: dropped malformed comment file={} line={} bodyEmpty={}",
              filePath,
              lineNumber,
              body.isBlank());
          continue;
        }
        comments.add(new AgentReviewComment(filePath, lineNumber, side, category, severity, body));
      }
    }
    return new AgentReview(verdict, summary, comments, 0L, null, null);
  }

  // ── Tool definition: submitReview ────────────────────────────────────────

  /**
   * Single-shot terminal tool for the PR review agent. Schema matches the structure documented in
   * {@code prompts/codereview/pr-review-agent.md}. Internal-execution disabled — the route doesn't
   * actually run anything; we just intercept the tool call args and parse them.
   */
  static class SubmitReviewToolCallback implements ToolCallback {
    private static final String SCHEMA =
        """
{
  "type": "object",
  "properties": {
    "verdict": {
      "type": "string",
      "enum": ["APPROVE", "REQUEST_CHANGES", "COMMENT"]
    },
    "summary": { "type": "string" },
    "comments": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "filePath":    { "type": "string" },
          "lineNumber":  { "type": "integer" },
          "side":        { "type": "string", "enum": ["LEFT", "RIGHT"] },
          "category":    { "type": "string" },
          "severity":    { "type": "string", "enum": ["CRITICAL", "MAJOR", "MINOR", "INFO"] },
          "body":        { "type": "string" }
        },
        "required": ["filePath", "lineNumber", "side", "body"],
        "additionalProperties": false
      }
    }
  },
  "required": ["verdict", "summary"],
  "additionalProperties": false
}
""";

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name("submitReview")
          .description(
              "Submit your final PR review. Call exactly once. Comments must cite specific"
                  + " filePath + lineNumber.")
          .inputSchema(SCHEMA)
          .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
      // The agent path doesn't dispatch this — the orchestrator parses the args directly.
      return "{\"ok\":true}";
    }
  }

  // ── DTOs ────────────────────────────────────────────────────────────────

  @Builder
  public record AgentRequest(
      String prReviewId,
      String repoFullName,
      Integer prNumber,
      String headSha,
      String baseBranch,
      String headBranch,
      String title,
      String body,
      Integer changedFiles,
      Integer additions,
      Integer deletions,
      String diff,
      String provider) {}

  public record AgentReviewComment(
      String filePath,
      int lineNumber,
      String side,
      String category,
      String severity,
      String body) {}

  /**
   * Output of the agent — structured review payload with provider/model/duration metadata decorated
   * post-call by the agent.
   */
  public record AgentReview(
      String verdict,
      String summary,
      List<AgentReviewComment> comments,
      long durationMs,
      String provider,
      String model) {
    public AgentReview withDurationMs(long ms) {
      return new AgentReview(verdict, summary, comments, ms, provider, model);
    }

    public AgentReview withProviderModel(String p, String m) {
      return new AgentReview(verdict, summary, comments, durationMs, p, m);
    }
  }

  /** Thrown by {@link #review} when the LLM dispatch or response parsing fails. */
  public static class PrReviewAgentException extends RuntimeException {
    public PrReviewAgentException(String message) {
      super(message);
    }
  }
}
