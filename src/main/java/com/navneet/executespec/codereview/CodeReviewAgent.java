package com.navneet.executespec.codereview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouterV2;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.tool.ExecutionContext;

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
 * Code Review Agent — LLM-augmented whole-repo review. PLANNING tier (Sonnet 4.6) for quality.
 *
 * <p>Two jobs (per {@code prompts/codereview/code-review-agent.md}):
 *
 * <ol>
 *   <li>Triage the static findings produced by {@link SemgrepRunner}: KEEP / REWRITE / DROP.
 *   <li>Find architectural / logic / security / performance issues semgrep can't see.
 * </ol>
 *
 * <p>Single-call agent: emits exactly one {@code submitCodeReview} tool call. The orchestrator
 * parses + persists. No multi-turn loop; no shell; no file mutations.
 *
 * <p>Token budget: 16K output cap. The user-prompt builder caps the file-sample payload at a
 * configurable budget (default ~150KB chars) so we don't pay for runaway uploads.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CodeReviewAgent {

  private static final String PROMPT_PATH = "prompts/codereview/code-review-agent.md";

  private final LlmRouterV2 router;
  private final ModelResolver modelResolver;
  private final ObjectMapper objectMapper;
  private final RunCostTracker costTracker;

  /**
   * Invoke the agent. Throws {@link CodeReviewAgentException} on LLM dispatch failure or malformed
   * tool call so the caller can fail the scan with a clear reason.
   */
  public AgentResult review(AgentRequest req) throws CodeReviewAgentException {
    long start = System.currentTimeMillis();
    String provider = req.provider() == null ? "anthropic" : req.provider().toLowerCase();
    LlmProvider providerEnum;
    try {
      providerEnum = LlmProvider.valueOf(provider.toUpperCase());
    } catch (Exception e) {
      throw new CodeReviewAgentException("unknown provider: " + provider);
    }
    ResolvedModel resolved = modelResolver.resolve("CodeReviewAgent", providerEnum);

    String systemPrompt;
    try {
      systemPrompt =
          StreamUtils.copyToString(
              new ClassPathResource(PROMPT_PATH).getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CodeReviewAgentException("missing prompt resource: " + PROMPT_PATH);
    }

    String userPrompt = composeUserPrompt(req);

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    messages.add(new UserMessage(userPrompt));

    SubmitCodeReviewToolCallback submitTool = new SubmitCodeReviewToolCallback();

    ChatResponse resp;
    try {
      resp =
          router.call(
              LlmRequest.builder()
                  .provider(provider)
                  .model(resolved.getModel())
                  .messages(messages)
                  .temperature(0.0)
                  .maxTokens(16384)
                  .toolsOverride(List.of(submitTool))
                  .context(
                      new ExecutionContext(
                          "code-review-" + req.scanId(), "review", java.nio.file.Paths.get("/")))
                  .build());
    } catch (Exception e) {
      throw new CodeReviewAgentException("LLM dispatch failed: " + e.getMessage());
    }

    String runIdForCost = "code-review-" + req.scanId();
    costTracker.record(runIdForCost, provider, resolved.getModel(), resp);

    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
    for (var gen : resp.getResults()) {
      if (gen.getOutput().getToolCalls() != null) {
        calls.addAll(gen.getOutput().getToolCalls());
      }
    }
    AssistantMessage.ToolCall submit =
        calls.stream().filter(tc -> "submitCodeReview".equals(tc.name())).findFirst().orElse(null);
    if (submit == null) {
      throw new CodeReviewAgentException(
          "agent returned no submitCodeReview tool call (model="
              + resolved.getModel()
              + ", calls="
              + calls.size()
              + ")");
    }

    AgentResult parsed;
    try {
      parsed = parseSubmission(submit.arguments());
    } catch (Exception e) {
      throw new CodeReviewAgentException("malformed submitCodeReview arguments: " + e.getMessage());
    }

    long elapsed = System.currentTimeMillis() - start;
    log.info(
        "CodeReviewAgent done provider={} model={} issues={} dropped={} elapsedMs={}",
        provider,
        resolved.getModel(),
        parsed.issues().size(),
        parsed.droppedStaticFindings().size(),
        elapsed);
    return parsed.withMeta(elapsed, provider, resolved.getModel());
  }

  String composeUserPrompt(AgentRequest req) {
    StringBuilder b = new StringBuilder();
    b.append("# Repo metadata\n\n");
    b.append("Repo: ").append(req.repoFullName()).append('\n');
    b.append("Scanned SHA: ").append(req.scannedSha()).append('\n');
    b.append("Primary language(s): ").append(String.join(", ", req.languages())).append('\n');
    b.append("File count: ").append(req.fileCount()).append('\n');
    b.append("Total LOC: ").append(req.totalLoc()).append('\n');

    b.append("\n# Static findings (").append(req.staticFindings().size()).append(")\n\n");
    if (req.staticFindings().isEmpty()) {
      b.append("(none)\n");
    } else {
      for (var f : req.staticFindings()) {
        b.append("- [")
            .append(f.severity())
            .append('/')
            .append(f.category())
            .append("] ")
            .append(f.ruleId())
            .append(" — ")
            .append(f.filePath())
            .append(':')
            .append(f.lineStart())
            .append('-')
            .append(f.lineEnd())
            .append('\n');
        b.append("  ").append(f.message().replace('\n', ' ')).append('\n');
        if (f.snippet() != null && !f.snippet().isBlank()) {
          b.append("  ```\n  ").append(f.snippet().replace("\n", "\n  ")).append("\n  ```\n");
        }
      }
    }

    b.append("\n# Sampled source files (").append(req.fileSamples().size()).append(")\n\n");
    for (var s : req.fileSamples()) {
      b.append("## `").append(s.path()).append("`\n\n");
      b.append("```").append(s.lang()).append('\n');
      b.append(s.content());
      b.append("\n```\n\n");
    }

    return b.toString();
  }

  /** Parse the agent's structured tool call output into our typed shape. */
  AgentResult parseSubmission(String argsJson) throws Exception {
    JsonNode root = objectMapper.readTree(argsJson);
    String summary = root.path("summary").asText("");
    List<AgentIssue> issues = new ArrayList<>();
    JsonNode arr = root.path("issues");
    if (arr.isArray()) {
      for (JsonNode i : arr) {
        String ruleId = i.path("fingerprintRuleId").asText("LLM-NATIVE-unknown");
        String source = i.path("source").asText("LLM").toUpperCase();
        if (!java.util.Set.of("STATIC", "LLM", "HYBRID").contains(source)) source = "LLM";
        String severity = i.path("severity").asText("INFO").toUpperCase();
        if (!java.util.Set.of("CRITICAL", "MAJOR", "MINOR", "INFO").contains(severity))
          severity = "INFO";
        String category = i.path("category").asText("BUG").toUpperCase();
        if (category.isBlank()) category = "BUG";
        String filePath = i.path("filePath").asText(null);
        int lineStart = i.path("lineStart").asInt(1);
        int lineEnd = i.path("lineEnd").asInt(lineStart);
        String message = i.path("message").asText("");
        String suggestedFix =
            i.has("suggestedFix") && !i.get("suggestedFix").isNull()
                ? i.get("suggestedFix").asText(null)
                : null;
        if (filePath == null || filePath.isBlank() || message.isBlank()) {
          log.warn("CodeReviewAgent: dropped malformed issue file={} msg={}", filePath, message);
          continue;
        }
        issues.add(
            new AgentIssue(
                ruleId,
                source,
                severity,
                category,
                filePath,
                lineStart,
                lineEnd,
                message,
                suggestedFix));
      }
    }
    List<DroppedStatic> dropped = new ArrayList<>();
    JsonNode drArr = root.path("droppedStaticFindings");
    if (drArr.isArray()) {
      for (JsonNode d : drArr) {
        String ruleId = d.path("ruleId").asText("");
        String filePath = d.path("filePath").asText("");
        int lineStart = d.path("lineStart").asInt(0);
        String reason = d.path("reason").asText("");
        if (!ruleId.isBlank()) dropped.add(new DroppedStatic(ruleId, filePath, lineStart, reason));
      }
    }
    return new AgentResult(summary, issues, dropped, 0L, null, null);
  }

  // ── Tool definition ─────────────────────────────────────────────────────────

  static class SubmitCodeReviewToolCallback implements ToolCallback {
    private static final String SCHEMA =
        """
{
  "type": "object",
  "properties": {
    "summary": { "type": "string" },
    "issues": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "fingerprintRuleId": { "type": "string" },
          "source":            { "type": "string", "enum": ["STATIC", "LLM", "HYBRID"] },
          "severity":          { "type": "string", "enum": ["CRITICAL", "MAJOR", "MINOR", "INFO"] },
          "category":          { "type": "string" },
          "filePath":          { "type": "string" },
          "lineStart":         { "type": "integer" },
          "lineEnd":           { "type": "integer" },
          "message":           { "type": "string" },
          "suggestedFix":      { "type": "string" }
        },
        "required": ["fingerprintRuleId", "source", "severity", "category", "filePath", "lineStart", "lineEnd", "message"],
        "additionalProperties": false
      }
    },
    "droppedStaticFindings": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "ruleId":    { "type": "string" },
          "filePath":  { "type": "string" },
          "lineStart": { "type": "integer" },
          "reason":    { "type": "string" }
        },
        "required": ["ruleId", "reason"],
        "additionalProperties": false
      }
    }
  },
  "required": ["summary", "issues"],
  "additionalProperties": false
}
""";

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name("submitCodeReview")
          .description(
              "Submit your final code-review output. Call exactly once per scan. Issues must"
                  + " cite specific filePath + lineStart.")
          .inputSchema(SCHEMA)
          .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
      // Orchestrator parses args directly — this never executes.
      return "{\"ok\":true}";
    }
  }

  // ── DTOs ────────────────────────────────────────────────────────────────────

  /** One static finding handed to the agent for triage. */
  public record StaticFinding(
      String ruleId,
      String severity,
      String category,
      String filePath,
      int lineStart,
      int lineEnd,
      String message,
      String snippet) {}

  /** One source-file excerpt sampled into the user prompt. */
  public record FileSample(String path, String lang, String content) {}

  @Builder
  public record AgentRequest(
      String scanId,
      String repoFullName,
      String scannedSha,
      List<String> languages,
      int fileCount,
      int totalLoc,
      List<StaticFinding> staticFindings,
      List<FileSample> fileSamples,
      String provider) {}

  /** One issue surfaced by the agent — already triaged + categorized. */
  public record AgentIssue(
      String ruleId,
      String source,
      String severity,
      String category,
      String filePath,
      int lineStart,
      int lineEnd,
      String message,
      String suggestedFix) {}

  /** A static finding the agent decided was a false positive. */
  public record DroppedStatic(String ruleId, String filePath, int lineStart, String reason) {}

  /** Final agent output. */
  public record AgentResult(
      String summary,
      List<AgentIssue> issues,
      List<DroppedStatic> droppedStaticFindings,
      long durationMs,
      String provider,
      String model) {
    public AgentResult withMeta(long ms, String p, String m) {
      return new AgentResult(summary, issues, droppedStaticFindings, ms, p, m);
    }
  }

  /** Thrown when the LLM dispatch or response parsing fails. */
  public static class CodeReviewAgentException extends RuntimeException {
    public CodeReviewAgentException(String msg) {
      super(msg);
    }
  }
}
