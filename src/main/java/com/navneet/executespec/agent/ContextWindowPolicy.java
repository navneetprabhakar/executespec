package com.navneet.executespec.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Relevance-scored context window for WorkerAgent.
 *
 * <p>Improves on the plain "first 2 + last N" sliding window by inserting
 * high-relevance messages (tool failures, compile errors, successful writes)
 * into the window even when they fall outside the recency tail.
 *
 * <h3>Window budget</h3>
 * <ul>
 *   <li>First 2 messages (task assignment + initial context) — always included</li>
 *   <li>Last {@code RECENCY_TAIL} messages — always included for continuity</li>
 *   <li>Remaining {@code window - 2 - RECENCY_TAIL} slots filled by highest-scored
 *       messages from the middle portion, in chronological order</li>
 * </ul>
 *
 * <h3>Relevance scores</h3>
 * <ul>
 *   <li>HIGH (3) — tool failures, compile/test errors, ERROR responses</li>
 *   <li>MEDIUM (2) — successful writeFile confirmations</li>
 *   <li>LOW (1) — all other messages</li>
 * </ul>
 */
@Component
@Slf4j
public class ContextWindowPolicy {

    private static final int TOTAL_WINDOW  = 20; // first 2 + up to 18 more
    private static final int RECENCY_TAIL  = 8;  // always keep last N messages
    private static final int MAX_RELEVANCE_SLOTS = TOTAL_WINDOW - 2 - RECENCY_TAIL; // 10

    private static final int SCORE_HIGH   = 3;
    private static final int SCORE_MEDIUM = 2;
    private static final int SCORE_LOW    = 1;

    private static final List<String> ERROR_SIGNALS = List.of(
            "ERROR", "error:", "Error:", "Failed", "failed", "Exception", "exception",
            "compile error", "test failure", "FAILED", "cannot find symbol",
            "does not exist", "NullPointerException", "ClassNotFoundException"
    );

    private static final List<String> WRITE_SIGNALS = List.of(
            "writeFile", "File written", "file written", "wrote", "created"
    );

    /**
     * Apply relevance-scored windowing to a message list.
     *
     * @param messages the full conversation history (excluding system prompt)
     * @param window   total number of non-system messages to include
     * @return windowed list (chronological order, size ≤ {@code window + 2})
     */
    public List<Message> apply(List<Message> messages, int window) {
        // No windowing needed below threshold
        if (messages.size() <= window + 2) return messages;

        int recencyTail = Math.min(RECENCY_TAIL, window - 2);
        int relevanceSlots = Math.max(0, window - 2 - recencyTail);

        List<Message> anchor   = messages.subList(0, 2);
        List<Message> recency  = messages.subList(messages.size() - recencyTail, messages.size());
        List<Message> middle   = messages.subList(2, messages.size() - recencyTail);

        // Score the middle section
        List<ScoredMessage> scored = new ArrayList<>(middle.size());
        for (int i = 0; i < middle.size(); i++) {
            scored.add(new ScoredMessage(middle.get(i), score(middle.get(i)), i));
        }

        // Take top-scoring messages from the middle (deterministic: highest score,
        // then most recent index as tiebreaker)
        List<Message> relevanceWinners = scored.stream()
                .filter(sm -> sm.score > SCORE_LOW)   // only HIGH or MEDIUM qualify
                .sorted(Comparator.comparingInt(ScoredMessage::score).reversed()
                        .thenComparingInt(ScoredMessage::index).reversed())
                .limit(relevanceSlots)
                .sorted(Comparator.comparingInt(ScoredMessage::index)) // restore chronological order
                .map(ScoredMessage::message)
                .toList();

        List<Message> result = new ArrayList<>(2 + relevanceWinners.size() + recencyTail);
        result.addAll(anchor);
        result.addAll(relevanceWinners);
        result.addAll(recency);

        log.debug("[ContextWindow] {}/{} msgs: anchor=2 relevance={} recency={} (dropped {})",
                result.size(), messages.size(),
                relevanceWinners.size(), recencyTail,
                messages.size() - result.size());

        return result;
    }

    private int score(Message msg) {
        String content = extractText(msg);

        if (containsAny(content, ERROR_SIGNALS)) return SCORE_HIGH;
        if (msg instanceof ToolResponseMessage trm) {
            // ToolResponseMessage: check if it's a tool failure
            for (var response : trm.getResponses()) {
                if (containsAny(response.responseData(), ERROR_SIGNALS)) return SCORE_HIGH;
                if (containsAny(response.responseData(), WRITE_SIGNALS)) return SCORE_MEDIUM;
            }
        }
        if (containsAny(content, WRITE_SIGNALS)) return SCORE_MEDIUM;
        return SCORE_LOW;
    }

    private String extractText(Message msg) {
        if (msg instanceof AssistantMessage am) {
            return am.getText() != null ? am.getText() : "";
        }
        return msg.getText() != null ? msg.getText() : "";
    }

    private boolean containsAny(String text, List<String> signals) {
        if (text == null || text.isEmpty()) return false;
        for (String s : signals) {
            if (text.contains(s)) return true;
        }
        return false;
    }

    private record ScoredMessage(Message message, int score, int index) {}
}
