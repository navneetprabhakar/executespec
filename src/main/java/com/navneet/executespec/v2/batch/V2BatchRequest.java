package com.navneet.executespec.v2.batch;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * One worker task, serialised for provider batch submission.
 *
 * <p>{@code taskId} is the correlation key: batch APIs return responses
 * out-of-order; the coordinator matches them back to tasks using this field.
 *
 * <p>See {@code docs/plans/v2-batch-execution-plan.md} §2.
 */
public record V2BatchRequest(
        String taskId,
        String model,
        List<Message> messages,
        List<ToolCallback> tools,
        Double temperature,
        Integer maxTokens) {
}
