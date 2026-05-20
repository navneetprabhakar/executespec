package com.navneet.executespec.eval.v2;

import java.util.List;

import com.navneet.executespec.router.AgentTier;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

/**
 * One production-shaped tier-eval test case.
 *
 * <p>Each case mirrors a real production agent invocation exactly: same system prompt, same tool
 * schema, same user message shape. The {@link TierEvalRunner} feeds {@link #messages()} and {@link
 * #tools()} into {@link com.navneet.executespec.agent.LlmRouter} and then asks the case to validate
 * the response with {@link #validate}.
 *
 * <p>Splitting "what to send" from "how to grade" lets one runner serve every tier — the runner is
 * generic, the case carries the production-specific shape.
 */
public interface TierEvalCase {

  String name();

  AgentTier tier();

  /** Full prompt sent to the model. Typically {@code [SystemMessage, UserMessage]}. */
  List<Message> messages();

  /**
   * Tool callbacks the model may call. Either real production callbacks (in which case the model
   * can dispatch them) or shim callbacks (router returns the tool-call args without executing). For
   * single-shot eval cases, shim callbacks are sufficient — we're only measuring whether the model
   * produced a well-formed tool call.
   */
  List<ToolCallback> tools();

  /**
   * Score the model's response 0–100. Implementations should inspect the assistant message + any
   * tool-call arguments. The runner pre-checks tool-call presence + JSON-validity and short-
   * circuits to failureMode={@link TierEvalFailureMode#MALFORMED_TOOL_CALL} / {@code WRONG_TOOL}
   * when applicable, so validators can assume a tool call exists when {@link #requiresToolCall()}
   * is true.
   *
   * @param assistantText raw text on the assistant message (may be empty/null for tool-only
   *     responses)
   * @param toolCallName name of the first tool call, or null if no tool call fired
   * @param toolCallArgsJson raw JSON arguments string of the first tool call, or null
   * @return correctness score 0–100 + a short detail message describing the verdict
   */
  ValidationVerdict validate(String assistantText, String toolCallName, String toolCallArgsJson);

  /**
   * Whether this case requires a tool call. When true and no tool call fires, the runner skips
   * {@link #validate} and reports {@link TierEvalFailureMode#WRONG_TOOL}.
   */
  default boolean requiresToolCall() {
    return !tools().isEmpty();
  }

  /** Expected tool name (when {@link #requiresToolCall()}); null = any tool counts. */
  default String expectedToolName() {
    return tools().isEmpty() ? null : tools().get(0).getToolDefinition().name();
  }

  /** Compact validator return value: correctness 0–100 + a one-line detail string. */
  record ValidationVerdict(int correctness, String detail) {}
}
