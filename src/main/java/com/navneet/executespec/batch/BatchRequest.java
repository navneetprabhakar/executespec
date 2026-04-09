package com.navneet.executespec.batch;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic batch request for a single task.
 * Built by {@link BatchRequestBuilder} from the planner's task definitions.
 *
 * @param customId     Task identifier (e.g. "task-abc123") — maps results back to tasks
 * @param model        Model name (e.g. "claude-haiku-4-5-20251001")
 * @param maxTokens    Max output tokens (estimated × multiplier)
 * @param systemPrompt System context for the LLM
 * @param messages     Conversation history — each map has "role" and "content" keys
 */
public record BatchRequest(
        String customId,
        String model,
        int maxTokens,
        String systemPrompt,
        List<Map<String, String>> messages
) {}
