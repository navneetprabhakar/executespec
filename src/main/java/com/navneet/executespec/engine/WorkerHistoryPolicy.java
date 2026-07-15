package com.navneet.executespec.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Pure context-overflow recovery helpers shared by the worker and its tests. */
final class WorkerHistoryPolicy {
  private WorkerHistoryPolicy() {}

  static String truncate(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    int end = max - 1;
    if (end > 0
        && Character.isHighSurrogate(s.charAt(end - 1))
        && Character.isLowSurrogate(s.charAt(end))) end--;
    return s.substring(0, end) + "…";
  }

  static boolean isContextLengthError(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message == null) continue;
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("context length")
          || lower.contains("context window")
          || lower.contains("context_length_exceeded")
          || lower.contains("maximum context")
          || lower.contains("prompt is too long")
          || lower.contains("input is too long")
          || lower.contains("too many tokens")
          || lower.contains("exceeds the maximum number of tokens")) return true;
    }
    return false;
  }

  static List<Message> hardTrimHistory(List<Message> history) {
    if (history == null || history.size() < 4) return null;
    if (history.size() < 8 && history.stream().noneMatch(m -> m instanceof ToolResponseMessage))
      return null;
    int n = history.size();
    int cut = -1;
    for (int i = Math.max(3, n - 8); i >= 3; i--) {
      if (history.get(i) instanceof AssistantMessage) {
        cut = i;
        break;
      }
    }
    if (cut < 0) {
      for (int i = Math.min(n - 1, n - 9); i >= 3; i--) {
        if (history.get(i) instanceof AssistantMessage) {
          cut = i;
          break;
        }
      }
    }
    if (cut < 0) return truncateOversizedToolResponses(history);
    List<Message> out = new ArrayList<>(n - cut + 2);
    out.add(history.get(0));
    String task =
        history.get(1) instanceof UserMessage um && um.getText() != null ? um.getText() : "";
    out.add(
        new UserMessage(
            task
                + "\n\n--- NOTE: "
                + (cut - 2)
                + " earlier conversation turns were DROPPED to recover from a context-window"
                + " overflow. Re-read any file you are no longer sure about before editing it."
                + " ---"));
    out.addAll(history.subList(cut, n));
    return out;
  }

  private static List<Message> truncateOversizedToolResponses(List<Message> history) {
    List<Message> out = new ArrayList<>(history.size());
    boolean changed = false;
    for (Message message : history) {
      if (!(message instanceof ToolResponseMessage responses)
          || responses.getResponses() == null
          || responses.getResponses().isEmpty()) {
        out.add(message);
        continue;
      }
      List<ToolResponseMessage.ToolResponse> bounded = new ArrayList<>();
      boolean responseChanged = false;
      for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
        String data = response.responseData();
        if (data != null && data.length() > 8_000) {
          data = data.substring(0, 8_000) + "\n[context overflow trim]";
          responseChanged = true;
        }
        bounded.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), data));
      }
      if (responseChanged) {
        out.add(ToolResponseMessage.builder().responses(bounded).build());
        changed = true;
      } else out.add(message);
    }
    return changed ? out : null;
  }
}
