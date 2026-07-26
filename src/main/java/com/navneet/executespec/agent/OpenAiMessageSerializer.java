package com.navneet.executespec.agent;

import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Serializes Spring AI conversation messages onto the OpenAI chat-completions wire contract. */
final class OpenAiMessageSerializer {
  private OpenAiMessageSerializer() {}

  static void append(ArrayNode messages, Message message) {
    if (message instanceof SystemMessage system) {
      messages.addObject().put("role", "system").put("content", system.getText());
    } else if (message instanceof UserMessage user) {
      appendUser(messages, user);
    } else if (message instanceof AssistantMessage assistant) {
      appendAssistant(messages, assistant);
    } else if (message instanceof ToolResponseMessage responses) {
      for (var response : responses.getResponses()) {
        messages
            .addObject()
            .put("role", "tool")
            .put("tool_call_id", response.id())
            .put("content", response.responseData() == null ? "" : response.responseData());
      }
    } else {
      throw new IllegalArgumentException(
          "Unsupported message type for OpenAI wrapper: " + message.getClass().getName());
    }
  }

  private static void appendUser(ArrayNode messages, UserMessage user) {
    ObjectNode node = messages.addObject().put("role", "user");
    if (user.getMedia() == null || user.getMedia().isEmpty()) {
      node.put("content", user.getText());
      return;
    }
    ArrayNode parts = node.putArray("content");
    if (user.getText() != null && !user.getText().isBlank()) {
      parts.addObject().put("type", "text").put("text", user.getText());
    }
    for (var media : user.getMedia()) {
      parts
          .addObject()
          .put("type", "image_url")
          .putObject("image_url")
          .put(
              "url",
              "data:"
                  + media.getMimeType()
                  + ";base64,"
                  + Base64.getEncoder().encodeToString(media.getDataAsByteArray()));
    }
  }

  private static void appendAssistant(ArrayNode messages, AssistantMessage assistant) {
    ObjectNode node = messages.addObject().put("role", "assistant");
    String text = assistant.getText();
    if (text == null || text.isEmpty()) node.putNull("content");
    else node.put("content", text);
    Object reasoning =
        assistant.getMetadata() == null
            ? null
            : assistant.getMetadata().get(OpenAiNativeToolClient.REASONING_CONTENT_META_KEY);
    if (reasoning instanceof String value && !value.isEmpty()) {
      node.put("reasoning_content", value);
    }
    if (assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) return;
    ArrayNode calls = node.putArray("tool_calls");
    for (var call : assistant.getToolCalls()) {
      ObjectNode encoded = calls.addObject();
      encoded.put(
          "id",
          call.id() == null || call.id().isBlank()
              ? "call_" + UUID.randomUUID().toString().replace("-", "")
              : call.id());
      encoded.put("type", "function");
      encoded
          .putObject("function")
          .put("name", call.name())
          .put("arguments", call.arguments() == null ? "{}" : call.arguments());
    }
  }
}
