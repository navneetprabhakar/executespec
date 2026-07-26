package com.navneet.executespec.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/** Persistence and provider-window policy for the run-chat transcript. */
@Slf4j
final class ChatHistoryCodec {
  private final ObjectMapper mapper = new ObjectMapper();

  List<ChatService.ChatMessage> boundedForPrompt(
      List<ChatService.ChatMessage> history, int maxMessages, int maxChars) {
    if (history == null || history.isEmpty()) return List.of();
    List<ChatService.ChatMessage> newestFirst = new ArrayList<>();
    int remainingChars = maxChars;
    for (int i = history.size() - 1;
        i >= 0 && newestFirst.size() < maxMessages && remainingChars > 0;
        i--) {
      ChatService.ChatMessage message = history.get(i);
      if (message == null || message.content() == null || message.content().isBlank()) continue;
      String content = message.content();
      if (content.length() > remainingChars) {
        String marker = "\n…[older chat message truncated]";
        int keep = Math.max(0, remainingChars - marker.length());
        content = content.substring(0, keep) + marker.substring(0, remainingChars - keep);
      }
      newestFirst.add(new ChatService.ChatMessage(message.role(), content));
      remainingChars -= content.length();
    }
    Collections.reverse(newestFirst);
    return List.copyOf(newestFirst);
  }

  List<ChatService.ChatMessage> deserialize(String json) {
    if (json == null || json.isBlank()) return new ArrayList<>();
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      log.warn("ChatService failed to parse chat history — starting fresh: {}", e.getMessage());
      return new ArrayList<>();
    }
  }

  String serialize(List<ChatService.ChatMessage> history) {
    try {
      return mapper.writeValueAsString(history);
    } catch (Exception e) {
      log.warn("ChatService failed to serialize chat history: {}", e.getMessage());
      return "[]";
    }
  }
}
