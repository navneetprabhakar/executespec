package com.navneet.executespec.v2.context;

import java.time.Instant;
import java.util.List;

import org.springframework.ai.chat.messages.Message;

import lombok.Builder;

/**
 * Snapshot of a worker task's in-flight state, persisted by {@link ContextStore}.
 *
 * @param runId associated run
 * @param taskId associated task
 * @param iteration iteration count at the time of save
 * @param history the full (or compacted) message list
 * @param savedAt server clock at the moment of save
 * @param sizeBytes serialized byte size (for Redis memory monitoring)
 */
@Builder
public record SavedContext(
    String runId,
    String taskId,
    int iteration,
    List<Message> history,
    Instant savedAt,
    int sizeBytes) {}
