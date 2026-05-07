package com.navneet.executespec.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 2 of the hierarchical planner — fills in the body of one skeleton task (description /
 * touchesFiles / hardConstraints / meetsRequirements) given the full plan context. Designed to be
 * invoked in parallel for every LEAF task in a SKELETON_ONLY outline; the orchestrator stitches the
 * expanded bodies back onto the plan before execution.
 *
 * <p>Slice 3 contract:
 *
 * <ul>
 *   <li>WORKHORSE-tier model — bodies are mechanical fills, no high-judgement reasoning beyond what
 *       the outline already encodes.
 *   <li>~3k {@code maxTokens} — single-task body fits comfortably.
 *   <li>One LLM call per task. No retry — failure surfaces back to the orchestrator which marks
 *       {@code agent_task.expansion_status = FAILED} and lets the coherence pass decide whether to
 *       recover.
 *   <li>Tool: {@code submitTaskDetail}. Output is a single {@link PlannedTask} with the original
 *       skeleton fields preserved.
 * </ul>
 *
 * <p>EPIC tasks are NOT expanded by this agent — the orchestrator routes them back through {@link
 * OutlinePlannerAgent#plan} in skeleton mode (recursive depth ≤ 2, slice 4).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskExpanderAgent {

  private final LlmRouter router;
  private final ObjectMapper objectMapper;
  private final ModelResolver modelResolver;

  private static final String PROMPT_PATH = "prompts/v2/task-expander.md";

  /** v2.6.2 slice 3 — body of a single task fits in 3k easily. */
  private static final int EXPAND_MAX_TOKENS = 3_000;

  /**
   * Expand one skeleton task into a fully-bodied {@link PlannedTask}. Returns the original skeleton
   * with description/touchesFiles/hardConstraints/ meetsRequirements filled in. The id, title,
   * complexity, type, and dependsOn fields are preserved verbatim from the input.
   *
   * @param skeleton the outline-emitted skeleton (id + title + complexity at minimum)
   * @param plan the full plan for cross-task context (sibling skeletons + requirements)
   * @param req planner-request providing provider, workspace summary, etc.
   * @param context per-task execution context for the LLM router
   */
  public PlannedTask expand(
      PlannedTask skeleton, Plan plan, PlannerRequest req, ExecutionContext context) {
    if (skeleton == null) throw new IllegalArgumentException("skeleton required");

    String systemPrompt =
        loadPrompt()
            .replace("{{PLAN_SUMMARY}}", safe(plan.planSummary()))
            .replace("{{PROJECT_TYPE}}", safe(plan.projectType()))
            .replace(
                "{{RUNTIME_SURFACE}}",
                plan.runtimeSurface() == null ? "HTTP" : plan.runtimeSurface().name())
            .replace("{{BUILD_COMMAND}}", safe(plan.buildCommand()))
            .replace("{{WORKSPACE_SUMMARY}}", safe(req.workspaceSummary()))
            .replace("{{REQUIREMENTS}}", renderRequirements(plan))
            .replace("{{ALL_TASK_SKELETONS}}", renderSkeletons(plan, skeleton.id()))
            .replace("{{TASK_ID}}", skeleton.id())
            .replace("{{TASK_TITLE}}", skeleton.title())
            .replace(
                "{{TASK_COMPLEXITY}}",
                skeleton.complexity() == null ? "MEDIUM" : skeleton.complexity().name())
            .replace(
                "{{TASK_TYPE}}", skeleton.taskType() == null ? "LEAF" : skeleton.taskType().name())
            .replace(
                "{{TASK_DEPENDS_ON}}",
                skeleton.dependsOn() == null || skeleton.dependsOn().isEmpty()
                    ? "(none)"
                    : String.join(", ", skeleton.dependsOn()))
            .replace(
                "{{TASK_TOUCHES_FILES}}",
                skeleton.touchesFiles() == null || skeleton.touchesFiles().isEmpty()
                    ? "(none)"
                    : String.join(", ", skeleton.touchesFiles()));

    List<Message> messages =
        List.of(
            new SystemMessage(systemPrompt),
            new UserMessage("Expand task " + skeleton.id() + " now."));

    ToolCallback submitTaskDetail = buildSubmitTaskDetailCallback(skeleton.id());
    LlmProvider provider = resolveProvider(req.provider());
    ResolvedModel resolved =
        modelResolver.resolve("TaskExpanderAgent", provider, AgentTier.WORKHORSE);

    log.info(
        "TaskExpander {} -> {} {} (parent task title='{}')",
        skeleton.id(),
        resolved.providerName(),
        resolved.getModel(),
        truncate(skeleton.title(), 60));

    ChatResponse resp =
        router.call(
            LlmRequest.builder()
                .provider(resolved.providerName())
                .model(resolved.getModel())
                .messages(messages)
                .context(context)
                .temperature(0.0)
                .maxTokens(EXPAND_MAX_TOKENS)
                .toolsOverride(List.of(submitTaskDetail))
                .build());

    AssistantMessage.ToolCall expandCall = null;
    for (var gen : resp.getResults()) {
      if (gen.getOutput().getToolCalls() != null) {
        for (var tc : gen.getOutput().getToolCalls()) {
          if ("submitTaskDetail".equals(tc.name())) {
            expandCall = tc;
            break;
          }
        }
      }
      if (expandCall != null) break;
    }
    if (expandCall == null) {
      String finishReason = String.valueOf(resp.getResult().getMetadata().getFinishReason());
      throw new IllegalStateException(
          "TaskExpander did not call submitTaskDetail for "
              + skeleton.id()
              + " (finish="
              + finishReason
              + ")");
    }

    return mergeExpansion(skeleton, expandCall.arguments());
  }

  private PlannedTask mergeExpansion(PlannedTask skeleton, String argsJson) {
    JsonNode root;
    try {
      root = objectMapper.readTree(argsJson);
    } catch (Exception e) {
      throw new IllegalStateException(
          "submitTaskDetail args are not valid JSON for " + skeleton.id() + ": " + e.getMessage(),
          e);
    }

    // Returned taskId MUST match — protects against the LLM accidentally
    // expanding a different task than asked.
    String returnedId = root.path("taskId").asText("").trim();
    if (!returnedId.isEmpty() && !returnedId.equals(skeleton.id())) {
      log.warn(
          "TaskExpander returned taskId={} but skeleton id={}; using skeleton id",
          returnedId,
          skeleton.id());
    }

    String description = root.path("description").asText("").trim();
    if (description.isBlank()) {
      // Defensive — the prompt requires a non-blank description, but we
      // mustn't NPE the worker if the LLM ignores us. Use the title as
      // the floor.
      description = "Implement: " + skeleton.title();
    }

    // touchesFiles: prefer expander's revised list; fall back to skeleton.
    List<String> touchesFiles = stringList(root.path("touchesFiles"));
    if (touchesFiles.isEmpty() && skeleton.touchesFiles() != null) {
      touchesFiles = skeleton.touchesFiles();
    }
    List<String> hardConstraints = stringList(root.path("hardConstraints"));
    List<String> meetsRequirements = stringList(root.path("meetsRequirements"));

    return PlannedTask.builder()
        .id(skeleton.id())
        .title(skeleton.title())
        .description(description)
        .complexity(skeleton.complexity())
        .touchesFiles(touchesFiles)
        .dependsOn(skeleton.dependsOn())
        .hardConstraints(hardConstraints)
        .meetsRequirements(meetsRequirements)
        .taskType(skeleton.taskType())
        .build();
  }

  // ─── tool definition ────────────────────────────────────────────────────

  private ToolCallback buildSubmitTaskDetailCallback(String expectedTaskId) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("submitTaskDetail")
            .description(
                "Submit the expanded body for task "
                    + expectedTaskId
                    + ". Call exactly once. Set taskId to '"
                    + expectedTaskId
                    + "' verbatim — do not change it.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "taskId": {"type": "string"},
    "description": {"type": "string"},
    "touchesFiles": {"type": "array", "items": {"type": "string"}},
    "hardConstraints": {"type": "array", "items": {"type": "string"}},
    "meetsRequirements": {"type": "array", "items": {"type": "string"}},
    "smokeTestStrategy": {"type": "string", "description": "Optional override: BUILD | PREVIEW_HEALTH | TEST_SUITE"}
  },
  "required": ["taskId", "description"]
}
""")
            .build();
    final String callbackId = "task-expander-" + UUID.randomUUID();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return def;
      }

      @Override
      public String call(String toolInput) {
        log.debug(
            "submitTaskDetail callback invoked unexpectedly ({}); args={}",
            callbackId,
            truncate(toolInput, 200));
        return "{\"ok\":true}";
      }
    };
  }

  // ─── helpers ────────────────────────────────────────────────────────────

  private String loadPrompt() {
    try {
      return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("TaskExpander prompt missing at " + PROMPT_PATH, e);
    }
  }

  private static String renderRequirements(Plan plan) {
    if (plan.requirements() == null || plan.requirements().isEmpty()) {
      return "(no requirements list — outline planner skipped requirement enumeration)";
    }
    StringBuilder sb = new StringBuilder();
    for (RequirementSpec r : plan.requirements()) {
      sb.append("- ")
          .append(r.id())
          .append(" [")
          .append(r.kind() == null ? "FUNCTIONAL" : r.kind().name())
          .append("] ")
          .append(r.text())
          .append("\n");
    }
    return sb.toString();
  }

  private static String renderSkeletons(Plan plan, String currentId) {
    if (plan.tasks() == null || plan.tasks().isEmpty()) return "(no sibling tasks)";
    StringBuilder sb = new StringBuilder();
    for (PlannedTask t : plan.tasks()) {
      if (t.id().equals(currentId)) continue; // skip self
      sb.append("- ")
          .append(t.id())
          .append(" [")
          .append(t.complexity() == null ? "MEDIUM" : t.complexity().name())
          .append("/")
          .append(t.taskType() == null ? "LEAF" : t.taskType().name())
          .append("] ")
          .append(t.title())
          .append("\n");
    }
    return sb.length() == 0 ? "(no sibling tasks)" : sb.toString();
  }

  private static List<String> stringList(JsonNode n) {
    if (n == null || !n.isArray()) return List.of();
    List<String> out = new ArrayList<>();
    n.forEach(
        item -> {
          if (item.isTextual() && !item.asText().isBlank()) out.add(item.asText());
        });
    return out;
  }

  private static LlmProvider resolveProvider(String provider) {
    if (provider == null || provider.isBlank()) return LlmProvider.OPENAI;
    try {
      return LlmProvider.valueOf(provider.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      return LlmProvider.OPENAI;
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }
}
