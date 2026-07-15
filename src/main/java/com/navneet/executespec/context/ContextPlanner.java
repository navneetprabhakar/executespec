package com.navneet.executespec.context;

import java.util.Comparator;
import java.util.List;

import com.navneet.executespec.router.ModelRegistry;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Provider-window aware context budget planner. It owns both deterministic evidence selection and
 * the worker's pre-request headroom decision.
 */
@Service
public class ContextPlanner {

  static final double SAFETY_RATIO = 0.08;
  static final int MIN_SAFETY_TOKENS = 4_096;

  /** Reserve space for the next tool-result envelope, not just the current request output. */
  static final int MIN_TOOL_RESULT_RESERVE_TOKENS = 2_048;

  /** Keep a small deterministic budget for test/build failure evidence and repair instructions. */
  static final int MIN_VERIFICATION_RESERVE_TOKENS = 2_048;

  /** Keep a handoff/journal reserve so a provider switch can carry the task state forward. */
  static final int MIN_HANDOFF_RESERVE_TOKENS = 1_024;

  static final int CHARS_PER_TOKEN_ESTIMATE = 4;
  static final int MESSAGE_OVERHEAD_TOKENS = 12;

  private final ModelRegistry modelRegistry;
  private final MeterRegistry meterRegistry;

  /** Pure-planner compatibility constructor used by evidence-selection callers and unit tests. */
  public ContextPlanner() {
    this.modelRegistry = null;
    this.meterRegistry = null;
  }

  public ContextPlanner(ModelRegistry modelRegistry) {
    this(modelRegistry, null);
  }

  /** Spring constructor; the registry is optional for pure planner/test callers. */
  @Autowired
  public ContextPlanner(ModelRegistry modelRegistry, MeterRegistry meterRegistry) {
    this.modelRegistry = modelRegistry;
    this.meterRegistry = meterRegistry;
  }

  public Plan plan(Window window, List<Item> items) {
    return plan(window, items, null);
  }

  /**
   * Plans evidence against the current repository revision. Items carrying a different SHA are
   * retained in {@link Plan#omitted()} for auditability but can never enter the selected context. A
   * null/blank current SHA preserves the compatibility behavior for non-repository contexts.
   */
  public Plan plan(Window window, List<Item> items, String currentRepositorySha) {
    if (window == null) throw new IllegalArgumentException("window is required");
    List<Item> candidates =
        items == null
            ? List.of()
            : items.stream()
                .filter(item -> item != null && item.tokenEstimate() > 0)
                // Keep lane priority explicit before item priority, then make ties stable. Callers
                // assemble evidence from Redis/search results whose iteration order is not
                // guaranteed; a non-deterministic tie break would change which context survives a
                // tight budget and make model evaluations/retries irreproducible.
                .sorted(
                    Comparator.comparingInt((Item item) -> lanePriority(item.kind()))
                        .reversed()
                        .thenComparing(Comparator.comparingInt(Item::priority).reversed())
                        .thenComparing(item -> item.fresh() ? 0 : 1)
                        .thenComparing(item -> item.kind() == null ? "" : item.kind().name())
                        .thenComparing(item -> item.id() == null ? "" : item.id()))
                .toList();
    long usable =
        window.contextWindowTokens()
            - window.maxOutputTokens()
            - window.providerSafetyMarginTokens()
            - window.futureToolReserveTokens()
            - window.verificationReserveTokens()
            - window.handoffReserveTokens();
    long remaining = Math.max(0, usable);
    var selected = new java.util.ArrayList<Item>();
    var omitted = new java.util.ArrayList<Item>();
    for (Item item : candidates) {
      if (isStale(item, currentRepositorySha)) {
        omitted.add(item);
      } else if (item.tokenEstimate() <= remaining) {
        selected.add(item);
        remaining -= item.tokenEstimate();
      } else omitted.add(item);
    }
    return new Plan(
        Math.max(0, usable),
        selected,
        omitted,
        remaining,
        omitted.stream().mapToLong(Item::tokenEstimate).sum());
  }

  private static boolean isStale(Item item, String currentRepositorySha) {
    return currentRepositorySha != null
        && !currentRepositorySha.isBlank()
        && item.repositorySha() != null
        && !item.repositorySha().isBlank()
        && !currentRepositorySha.equals(item.repositorySha());
  }

  private static int lanePriority(Kind kind) {
    if (kind == null) return 0;
    return switch (kind) {
      case SYSTEM -> 1_000;
      case GOAL -> 990;
      case PLAN -> 980;
      case TASK_EVIDENCE -> 900;
      case WORKING_SET -> 800;
      case PROJECT_BASELINE -> 700;
      case JOURNAL -> 600;
      case EXTERNAL_ARTIFACT -> 100;
    };
  }

  /**
   * Decides whether the existing tool-pair-safe history compactor should run before the next worker
   * request. Unknown model metadata is explicitly unavailable so callers preserve their legacy
   * trigger/recovery behavior.
   */
  public HeadroomDecision planHeadroom(
      String provider,
      String model,
      long lastReportedInputTokens,
      Integer requestedOutputTokens,
      List<Message> messages,
      List<ToolCallback> tools) {
    if (modelRegistry == null || model == null || model.isBlank()) {
      return record(HeadroomDecision.unavailable(provider, model, "model metadata unavailable"));
    }
    try {
      if (!modelRegistry.isKnownModel(model)) {
        return record(HeadroomDecision.unavailable(provider, model, "model is not registered"));
      }
      int window = modelRegistry.contextLimitFor(model);
      if (window <= 0) {
        return record(
            HeadroomDecision.unavailable(provider, model, "context window is not configured"));
      }
      int outputReserve =
          requestedOutputTokens != null && requestedOutputTokens > 0
              ? Math.min(requestedOutputTokens, modelRegistry.maxOutputForModel(model))
              : modelRegistry.maxOutputForModel(model);
      outputReserve = Math.max(0, outputReserve);
      // A request whose output reservation alone consumes the complete model window cannot be
      // repaired by history compaction. Treat this as unavailable model-specific budget metadata
      // so the caller keeps its overflow-recovery path instead of repeatedly compacting a
      // transcript that can never fit.
      if (outputReserve >= window) {
        return record(
            HeadroomDecision.unavailable(
                provider,
                model,
                "output reservation exceeds model context window ("
                    + outputReserve
                    + " >= "
                    + window
                    + ")"));
      }
      int safetyReserve = Math.max(MIN_SAFETY_TOKENS, (int) Math.ceil(window * SAFETY_RATIO));
      int toolResultReserve = reserveFromOutput(outputReserve, MIN_TOOL_RESULT_RESERVE_TOKENS);
      int verificationReserve = reserveFromOutput(outputReserve, MIN_VERIFICATION_RESERVE_TOKENS);
      int handoffReserve = reserveFromOutput(outputReserve, MIN_HANDOFF_RESERVE_TOKENS);
      long estimatedInput =
          Math.max(Math.max(0L, lastReportedInputTokens), estimateInputTokens(messages, tools));
      long available =
          (long) window
              - estimatedInput
              - outputReserve
              - safetyReserve
              - toolResultReserve
              - verificationReserve
              - handoffReserve;
      HeadroomAction action = available < 0 ? HeadroomAction.COMPACT : HeadroomAction.CONTINUE;
      return record(
          new HeadroomDecision(
              action,
              provider,
              model,
              window,
              estimatedInput,
              outputReserve,
              safetyReserve,
              toolResultReserve,
              verificationReserve,
              handoffReserve,
              available,
              action == HeadroomAction.COMPACT
                  ? "output, safety, tool-result, verification, and handoff reserves exceed"
                      + " model-window headroom"
                  : "model-window headroom is sufficient after output, safety, tool-result,"
                      + " verification, and handoff reserves"));
    } catch (RuntimeException e) {
      return record(
          HeadroomDecision.unavailable(
              provider, model, "context planning failed: " + e.getMessage()));
    }
  }

  private static int reserveFromOutput(int outputReserve, int minimum) {
    return Math.max(minimum, (int) Math.ceil(Math.max(0, outputReserve) * 0.25));
  }

  /**
   * Records low-cardinality headroom evidence without changing the planner's pure decision. The
   * model name is intentionally not a tag: enterprise overrides can introduce unbounded values.
   */
  private HeadroomDecision record(HeadroomDecision decision) {
    if (meterRegistry == null || decision == null) return decision;
    String action = decision.action().name().toLowerCase(java.util.Locale.ROOT);
    String provider = normalizeProvider(decision.provider());
    Counter.builder("executespec.context.headroom.decisions")
        .description("Context headroom decisions made before worker requests")
        .tag("action", action)
        .tag("provider", provider)
        .register(meterRegistry)
        .increment();
    DistributionSummary.builder("executespec.context.headroom.available_tokens")
        .description("Available model-window tokens after input and output reservation")
        .baseUnit("tokens")
        .tag("action", action)
        .tag("provider", provider)
        .publishPercentiles(0.5, 0.95)
        .register(meterRegistry)
        .record(Math.max(0, decision.availableHeadroomTokens()));
    return decision;
  }

  private static String normalizeProvider(String provider) {
    if (provider == null || provider.isBlank()) return "unknown";
    return provider.trim().toLowerCase(java.util.Locale.ROOT);
  }

  static long estimateInputTokens(List<Message> messages, List<ToolCallback> tools) {
    long chars = 0;
    int messageCount = 0;
    if (messages != null) {
      for (Message message : messages) {
        if (message == null) continue;
        messageCount++;
        String text = message.getText();
        if (text != null) chars += text.length();
        String rendered = message.toString();
        if (rendered != null && (text == null || rendered.length() > text.length())) {
          chars += Math.min(rendered.length() - (text == null ? 0 : text.length()), 16_000);
        }
      }
    }
    if (tools != null) {
      for (ToolCallback tool : tools) {
        if (tool == null || tool.getToolDefinition() == null) continue;
        var definition = tool.getToolDefinition();
        chars += length(definition.name());
        chars += length(definition.description());
        chars += length(definition.inputSchema());
      }
    }
    return divideCeil(chars, CHARS_PER_TOKEN_ESTIMATE)
        + (long) messageCount * MESSAGE_OVERHEAD_TOKENS;
  }

  /**
   * Deterministically estimates the input budget for routing-time task admission. This is
   * intentionally conservative and provider-neutral: the actual worker request will still use
   * {@link #estimateInputTokens(List, List)} once the full prompt and tool schemas are available.
   */
  public static long estimateTextTokens(String... values) {
    long chars = 0;
    int valueCount = 0;
    if (values != null) {
      for (String value : values) {
        if (value == null || value.isBlank()) continue;
        chars += value.length();
        valueCount++;
      }
    }
    return divideCeil(chars, CHARS_PER_TOKEN_ESTIMATE)
        + (long) valueCount * MESSAGE_OVERHEAD_TOKENS;
  }

  private static int length(String value) {
    return value == null ? 0 : value.length();
  }

  private static long divideCeil(long value, long divisor) {
    return value <= 0 ? 0 : 1 + ((value - 1) / divisor);
  }

  public enum HeadroomAction {
    CONTINUE,
    COMPACT,
    UNAVAILABLE
  }

  public record HeadroomDecision(
      HeadroomAction action,
      String provider,
      String model,
      int contextWindowTokens,
      long estimatedInputTokens,
      int outputReserveTokens,
      int safetyReserveTokens,
      int futureToolReserveTokens,
      int verificationReserveTokens,
      int handoffReserveTokens,
      long availableHeadroomTokens,
      String reason) {

    /** Compatibility constructor for callers that only supplied output and safety reserves. */
    public HeadroomDecision(
        HeadroomAction action,
        String provider,
        String model,
        int contextWindowTokens,
        long estimatedInputTokens,
        int outputReserveTokens,
        int safetyReserveTokens,
        long availableHeadroomTokens,
        String reason) {
      this(
          action,
          provider,
          model,
          contextWindowTokens,
          estimatedInputTokens,
          outputReserveTokens,
          safetyReserveTokens,
          0,
          0,
          0,
          availableHeadroomTokens,
          reason);
    }

    public boolean shouldCompact() {
      return action == HeadroomAction.COMPACT;
    }

    public static HeadroomDecision unavailable(String provider, String model, String reason) {
      return new HeadroomDecision(
          HeadroomAction.UNAVAILABLE, provider, model, 0, 0, 0, 0, 0, 0, 0, 0, reason);
    }
  }

  public record Window(
      long contextWindowTokens,
      long maxOutputTokens,
      long providerSafetyMarginTokens,
      long futureToolReserveTokens,
      long verificationReserveTokens,
      long handoffReserveTokens) {
    public Window {
      if (contextWindowTokens <= 0)
        throw new IllegalArgumentException("context window must be positive");
    }
  }

  public record Item(
      String id,
      Kind kind,
      String source,
      String repositorySha,
      String path,
      int tokenEstimate,
      int priority,
      boolean fresh,
      String provenance,
      String range,
      String taskPhase,
      String reloadHandle) {

    /** Compatibility constructor for callers that predate lane provenance metadata. */
    public Item(
        String id,
        Kind kind,
        String source,
        String repositorySha,
        String path,
        int tokenEstimate,
        int priority,
        boolean fresh) {
      this(
          id,
          kind,
          source,
          repositorySha,
          path,
          tokenEstimate,
          priority,
          fresh,
          source,
          path,
          kind == null ? null : kind.name(),
          id);
    }
  }

  public enum Kind {
    SYSTEM,
    GOAL,
    PLAN,
    PROJECT_BASELINE,
    TASK_EVIDENCE,
    WORKING_SET,
    JOURNAL,
    EXTERNAL_ARTIFACT
  }

  public record Plan(
      long usableInputTokens,
      List<Item> selected,
      List<Item> omitted,
      long remainingTokens,
      long omittedTokens) {}
}
