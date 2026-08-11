package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpansionValueMerger.MAX_HARD_CONSTRAINTS;
import static com.navneet.executespec.agent.TaskExpansionValueMerger.MAX_HARD_CONSTRAINT_CHARS;
import static com.navneet.executespec.agent.TaskExpansionValueMerger.MAX_MEETS_REQUIREMENTS;
import static com.navneet.executespec.agent.TaskExpansionValueMerger.MAX_REQUIREMENT_ID_CHARS;
import static com.navneet.executespec.agent.TaskExpansionValueMerger.MAX_TOUCHES_FILES;
import static com.navneet.executespec.agent.TaskExpansionValueMerger.MAX_TOUCH_FILE_CHARS;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
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
 *   <li>4k {@code maxTokens} with a compact schema — enough for one bounded task body.
 *   <li>One normal LLM call plus one schema-repair retry only when the tool call is absent or its
 *       JSON is truncated. Semantic failures still surface immediately.
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
  private final ModelRegistry modelRegistry;
  private final PromptAssembler promptAssembler;

  private static final String PROMPT_PATH = "prompts/task-expander.md";

  /**
   * Output ceiling for one expanded task body. Derived from the resolved model rather than fixed at
   * 4,096: on a reasoning model the reasoning tokens share this budget with the emitted tool call,
   * so a fixed ceiling truncates the task detail mid-emission. A ceiling is a bound, not a charge.
   * The 4,000-character description envelope below is enforced separately and still fails closed.
   */
  static final int MAX_DESCRIPTION_CHARS = 4_000;

  public static final class ExpansionContractException extends IllegalStateException {
    private final PlannedTask rejectedTask;

    public ExpansionContractException(
        String taskId, PlannedTask rejectedTask, List<String> violations) {
      super(
          "TaskExpander semantic contract violation for "
              + taskId
              + ": "
              + String.join("; ", violations));
      this.rejectedTask = rejectedTask;
    }

    public ExpansionContractException(String taskId, List<String> violations) {
      this(taskId, null, violations);
    }

    public PlannedTask rejectedTask() {
      return rejectedTask;
    }
  }

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

    String systemPrompt = loadPrompt();

    List<Message> messages =
        new ArrayList<>(
            List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(TaskExpansionPromptEvidence.build(plan, skeleton, req))));

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

    PlannedTask expanded;
    boolean repairConsumed = false;
    try {
      expanded = expandOnce(skeleton, context, messages, resolved, submitTaskDetail, null);
    } catch (IllegalStateException firstFailure) {
      if (!isSchemaRepairable(firstFailure)) throw firstFailure;
      log.warn(
          "TaskExpander {} emitted an absent/truncated tool payload; attempting one compact schema"
              + " repair",
          skeleton.id());
      messages.add(
          new UserMessage(
              "SCHEMA REPAIR: the prior submitTaskDetail payload was absent or truncated. Call the"
                  + " tool once with valid complete JSON. Keep description under 4,000 characters,"
                  + " emit only additional values, and do not repeat authoritative skeleton values."
                  + " Remaining capacities: additionalTouchesFiles="
                  + TaskExpansionValueMerger.remainingCapacity(
                      skeleton.touchesFiles(), MAX_TOUCHES_FILES, MAX_TOUCH_FILE_CHARS)
                  + ", additionalHardConstraints="
                  + TaskExpansionValueMerger.remainingCapacity(
                      skeleton.hardConstraints(), MAX_HARD_CONSTRAINTS, MAX_HARD_CONSTRAINT_CHARS)
                  + ". Do not emit a requirement-ID array; skeleton requirement ownership is"
                  + " authoritative. Do not add commentary."));
      expanded =
          expandOnce(skeleton, context, messages, resolved, submitTaskDetail, "schema-repair");
      repairConsumed = true;
    }

    List<String> semanticViolations = semanticViolations(expanded, plan);
    if (semanticViolations.isEmpty()) return expanded;
    PlannedTask deterministicFallback =
        applyDeterministicSemanticFallback(expanded, semanticViolations);
    List<String> afterDeterministicFallback = semanticViolations(deterministicFallback, plan);
    if (afterDeterministicFallback.isEmpty()) {
      log.info(
          "TaskExpander {} applied deterministic fallback for {} explicit schema contract(s)",
          skeleton.id(),
          semanticViolations.size());
      return deterministicFallback;
    }
    expanded = deterministicFallback;
    semanticViolations = afterDeterministicFallback;
    if (repairConsumed) {
      throw new ExpansionContractException(skeleton.id(), expanded, semanticViolations);
    }
    log.warn(
        "TaskExpander {} violated {} deterministic requirement contract(s); attempting one semantic"
            + " repair. violations={}",
        skeleton.id(),
        semanticViolations.size(),
        semanticViolations);
    messages.add(
        new UserMessage(
            "SEMANTIC REPAIR: the prior task body violated these cited requirement contracts: "
                + String.join("; ", semanticViolations)
                + ". Re-emit the complete task body using only the current request, plan, and"
                + " established prerequisite contracts. Repair each listed contradiction"
                + " explicitly; do not import routes, fields, state machines, or algorithms from"
                + " another application. Do not add commentary.\n"
                + PromptTrustBoundary.wrap(
                    "INVALID_EXPANDED_TASK", semanticRepairEvidence(expanded))));
    PlannedTask invalidExpanded = expanded;
    try {
      expanded =
          expandOnce(skeleton, context, messages, resolved, submitTaskDetail, "semantic-repair");
    } catch (IllegalStateException repairFailure) {
      if (!isSchemaRepairable(repairFailure)) throw repairFailure;
      PlannedTask fallback =
          applyDeterministicSemanticFallback(invalidExpanded, semanticViolations);
      List<String> remaining = semanticViolations(fallback, plan);
      if (remaining.isEmpty()) {
        log.warn(
            "TaskExpander {} semantic repair payload was malformed; retained the original valid"
                + " task with deterministic contract constraints",
            skeleton.id());
        return fallback;
      }
      throw new ExpansionContractException(skeleton.id(), fallback, remaining);
    }
    semanticViolations = semanticViolations(expanded, plan);
    if (!semanticViolations.isEmpty()) {
      PlannedTask postRepairFallback =
          applyDeterministicSemanticFallback(expanded, semanticViolations);
      List<String> remaining = semanticViolations(postRepairFallback, plan);
      if (!remaining.isEmpty()) {
        throw new ExpansionContractException(skeleton.id(), postRepairFallback, remaining);
      }
      log.info(
          "TaskExpander {} applied deterministic fallback for {} explicit schema contract(s)",
          skeleton.id(),
          semanticViolations.size());
      return postRepairFallback;
    }
    return expanded;
  }

  private PlannedTask expandOnce(
      PlannedTask skeleton,
      ExecutionContext context,
      List<Message> messages,
      ResolvedModel resolved,
      ToolCallback submitTaskDetail,
      String repairKind) {
    ChatResponse resp =
        router.call(
            LlmRequest.builder()
                .provider(resolved.providerName())
                .model(resolved.getModel())
                .messages(List.copyOf(messages))
                .context(context)
                .agentRole("TaskExpanderAgent")
                .stepId(
                    "planner.expand."
                        + skeleton.id()
                        + (repairKind == null ? "" : "." + repairKind))
                .temperature(0.0)
                .maxTokens(
                    PlanEmissionBudget.firstAttempt(
                        modelRegistry.maxOutputForModel(resolved.getModel())))
                .toolChoice("required")
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

  static boolean isSchemaRepairable(IllegalStateException failure) {
    return TaskExpansionSemanticContract.isSchemaRepairable(failure);
  }

  private static String semanticRepairEvidence(PlannedTask task) {
    return TaskExpansionSemanticContract.semanticRepairEvidence(task);
  }

  static PlannedTask applyDeterministicSemanticFallback(PlannedTask task, List<String> violations) {
    return TaskExpansionSemanticContract.applyDeterministicSemanticFallback(task, violations);
  }

  static List<String> semanticViolations(PlannedTask task, Plan plan) {
    return TaskExpansionSemanticContract.semanticViolations(task, plan);
  }

  public static List<String> crossTaskContractViolations(Plan plan) {
    return TaskExpansionPlanContract.crossTaskContractViolations(plan);
  }

  public static List<String> finalPlanContractViolations(Plan plan) {
    return TaskExpansionPlanContract.finalPlanContractViolations(plan);
  }

  /** Fail-closed subset — the assembled plan literally cannot execute (see delegate javadoc). */
  public static List<String> structuralPlanContractViolations(Plan plan) {
    return TaskExpansionPlanContract.structuralPlanContractViolations(plan);
  }

  /** Advisory remainder — heuristic cross-task guidance, surfaced but never a run-killer. */
  public static List<String> advisoryPlanContractViolations(Plan plan) {
    return TaskExpansionPlanContract.advisoryPlanContractViolations(plan);
  }

  public static Plan normalizeFinalPlan(Plan plan) {
    return TaskExpansionPlanContract.normalizeFinalPlan(plan);
  }

  static boolean isImplementationTask(PlannedTask task) {
    return TaskExpansionPlanContract.isImplementationTask(task);
  }

  static boolean isIdempotencySchemaOwner(PlannedTask task, Plan plan) {
    return TaskExpansionPlanContract.isIdempotencySchemaOwner(task, plan);
  }

  static boolean isOrderSchemaProducer(PlannedTask task, String evidence) {
    return TaskExpansionPlanContract.isOrderSchemaProducer(task, evidence);
  }

  static boolean isPersistenceSchemaProducer(PlannedTask task, String evidence) {
    return TaskExpansionPlanContract.isPersistenceSchemaProducer(task, evidence);
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

    String rawDescription = normalizeTechnicalSpacing(root.path("description").asText("").trim());
    String description = requireDescriptionWithinEnvelope(skeleton.id(), rawDescription);
    List<String> rawAdditionalTouchesFiles =
        stringList(additionalArray(root, "additionalTouchesFiles", "touchesFiles", skeleton.id()));
    List<String> rawAdditionalHardConstraints =
        stringList(
            additionalArray(root, "additionalHardConstraints", "hardConstraints", skeleton.id()));
    List<String> rawAdditionalMeetsRequirements =
        new ArrayList<>(stringList(root.path("additionalMeetsRequirements")));
    if (root.has("meetsRequirements")) {
      log.warn(
          "TaskExpander output_contract task={} legacyField=meetsRequirements"
              + " expectedField=(none; ownership authoritative)",
          skeleton.id());
      rawAdditionalMeetsRequirements.addAll(stringList(root.path("meetsRequirements")));
    }
    TaskExpansionValueMerger.Result touchesFiles =
        TaskExpansionValueMerger.mergeAndReport(
            skeleton.id(),
            "touchesFiles",
            skeleton.touchesFiles(),
            rawAdditionalTouchesFiles,
            MAX_TOUCHES_FILES,
            MAX_TOUCH_FILE_CHARS);
    TaskExpansionValueMerger.Result hardConstraints =
        TaskExpansionValueMerger.mergeAndReport(
            skeleton.id(),
            "hardConstraints",
            skeleton.hardConstraints(),
            rawAdditionalHardConstraints,
            MAX_HARD_CONSTRAINTS,
            MAX_HARD_CONSTRAINT_CHARS);
    TaskExpansionValueMerger.Result meetsRequirements =
        TaskExpansionValueMerger.authoritativeOnlyAndReport(
            skeleton.id(),
            "meetsRequirements",
            skeleton.meetsRequirements(),
            rawAdditionalMeetsRequirements,
            MAX_MEETS_REQUIREMENTS,
            MAX_REQUIREMENT_ID_CHARS);
    validateExpansionEnvelope(
        skeleton.id(),
        description,
        touchesFiles.values(),
        hardConstraints.values(),
        meetsRequirements.values());
    if (description.isBlank()) {
      // Defensive — the prompt requires a non-blank description, but we
      // mustn't NPE the worker if the LLM ignores us. Use the title as
      // the floor.
      description = "Implement: " + skeleton.title();
    }

    return PlannedTask.builder()
        .id(skeleton.id())
        .title(skeleton.title())
        .description(description)
        .complexity(skeleton.complexity())
        .touchesFiles(touchesFiles.values())
        .requiredArtifacts(skeleton.requiredArtifacts())
        .dependsOn(skeleton.dependsOn())
        .hardConstraints(hardConstraints.values())
        .meetsRequirements(meetsRequirements.values())
        .taskType(skeleton.taskType())
        .build();
  }

  static void validateExpansionEnvelope(
      String taskId,
      String description,
      List<String> touchesFiles,
      List<String> hardConstraints,
      List<String> meetsRequirements) {
    if (description.length() > MAX_DESCRIPTION_CHARS
        || touchesFiles.size() > MAX_TOUCHES_FILES
        || hardConstraints.size() > MAX_HARD_CONSTRAINTS
        || meetsRequirements.size() > MAX_MEETS_REQUIREMENTS
        || touchesFiles.stream().anyMatch(value -> value.length() > MAX_TOUCH_FILE_CHARS)
        || hardConstraints.stream().anyMatch(value -> value.length() > MAX_HARD_CONSTRAINT_CHARS)
        || meetsRequirements.stream()
            .anyMatch(value -> value.length() > MAX_REQUIREMENT_ID_CHARS)) {
      throw new IllegalStateException("submitTaskDetail args exceed schema limits for " + taskId);
    }
  }

  /**
   * Reject provider output that cannot fit the executable task schema without losing detail.
   *
   * <p>The old path silently truncated an oversized description and appended a compaction marker.
   * That made an incomplete schema or acceptance procedure look executable and allowed workers to
   * receive phrases such as "verbose implementation detail omitted". Oversize is a repairable tool
   * schema violation: the existing bounded schema-repair turn asks the provider for a complete,
   * concise payload, and a second oversize response fails closed before plan assembly.
   */
  static String requireDescriptionWithinEnvelope(String taskId, String description) {
    String value = description == null ? "" : description;
    if (value.length() > MAX_DESCRIPTION_CHARS) {
      throw new IllegalStateException(
          "submitTaskDetail args exceed schema limits for "
              + taskId
              + ": description is "
              + value.length()
              + " characters; maximum is "
              + MAX_DESCRIPTION_CHARS);
    }
    return value;
  }

  private static JsonNode additionalArray(
      JsonNode root, String field, String legacyField, String taskId) {
    if (root.has(field)) return root.path(field);
    if (root.has(legacyField)) {
      log.warn(
          "TaskExpander output_contract task={} legacyField={} expectedField={}",
          taskId,
          legacyField,
          field);
      return root.path(legacyField);
    }
    return root.path(field);
  }

  static List<String> mergeAuthoritativeValues(List<String> authoritative, List<String> generated) {
    java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
    if (authoritative != null) merged.addAll(authoritative);
    if (generated != null) merged.addAll(generated);
    return List.copyOf(merged);
  }

  static String boundText(String value, int maxLength) {
    return TaskExpansionTextNormalizer.boundText(value, maxLength);
  }

  static String normalizeTechnicalSpacing(String value) {
    return TaskExpansionTextNormalizer.normalizeTechnicalSpacing(value);
  }

  static List<String> boundValues(List<String> values, int maxItems, int maxLength) {
    return TaskExpansionValueMerger.boundValues(values, maxItems, maxLength);
  }

  private ToolCallback buildSubmitTaskDetailCallback(String expectedTaskId) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("submitTaskDetail")
            .description(
                "Submit the expanded body for the task identified in the user evidence. Call"
                    + " exactly once and copy that taskId verbatim.")
            .inputSchema(TaskExpansionToolSchema.SUBMIT_TASK_DETAIL)
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

  String loadPrompt() {
    try {
      return promptAssembler.assemble(PROMPT_PATH.substring("prompts/".length()));
    } catch (Exception e) {
      throw new IllegalStateException("TaskExpander prompt missing at " + PROMPT_PATH, e);
    }
  }

  static String buildExpansionEvidence(Plan plan, PlannedTask skeleton, PlannerRequest req) {
    return TaskExpansionPromptEvidence.build(plan, skeleton, req);
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

  static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  static String safe(String s) {
    return s == null ? "" : s;
  }
}
