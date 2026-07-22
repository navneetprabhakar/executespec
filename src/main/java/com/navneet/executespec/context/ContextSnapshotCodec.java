package com.navneet.executespec.context;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Discriminated-union JSON codec for Spring AI {@code Message} history, extracted from {@link
 * ContextStore} (RR-501/502/503/504 remediation kept ContextStore under the god-class ceiling).
 *
 * <p>Spring AI's {@code Message} hierarchy (SystemMessage, UserMessage, AssistantMessage,
 * ToolResponseMessage) isn't trivially round-trippable via Jackson — each subclass has its own
 * constructor/builder shape. This emits a minimal {@code type}-tagged format per message and
 * reconstructs the correct subclass on read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ContextSnapshotCodec {

  private final ObjectMapper objectMapper;

  String serialize(List<Message> history, int iteration, String runId, String taskId) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("runId", runId);
    root.put("taskId", taskId);
    root.put("iteration", iteration);
    root.put("savedAtEpochMs", System.currentTimeMillis());
    ArrayNode msgs = root.putArray("history");
    for (Message m : history) {
      msgs.add(serializeMessage(m));
    }
    return root.toString();
  }

  private ObjectNode serializeMessage(Message m) {
    ObjectNode n = objectMapper.createObjectNode();
    if (m instanceof SystemMessage sm) {
      n.put("type", "system");
      n.put("text", sm.getText());
    } else if (m instanceof UserMessage um) {
      n.put("type", "user");
      n.put("text", um.getText());
    } else if (m instanceof AssistantMessage am) {
      n.put("type", "assistant");
      n.put("text", am.getText() == null ? "" : am.getText());
      if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
        ArrayNode tcs = n.putArray("toolCalls");
        for (var tc : am.getToolCalls()) {
          ObjectNode t = tcs.addObject();
          t.put("id", tc.id() == null ? "" : tc.id());
          t.put("type", tc.type() == null ? "function" : tc.type());
          t.put("name", tc.name());
          t.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
        }
      }
    } else if (m instanceof ToolResponseMessage trm) {
      n.put("type", "tool");
      ArrayNode resps = n.putArray("responses");
      for (var resp : trm.getResponses()) {
        ObjectNode r = resps.addObject();
        r.put("id", resp.id());
        r.put("name", resp.name() == null ? "" : resp.name());
        r.put("responseData", resp.responseData() == null ? "" : resp.responseData());
      }
    } else {
      // Unknown message type — store a placeholder so we don't lose the count.
      n.put("type", "unknown");
      n.put("class", m.getClass().getName());
      n.put("text", String.valueOf(m));
    }
    return n;
  }

  SavedContext deserialize(String json) throws Exception {
    JsonNode root = objectMapper.readTree(json);
    String runId = root.path("runId").asText("");
    String taskId = root.path("taskId").asText("");
    int iteration = root.path("iteration").asInt(0);
    long savedAt = root.path("savedAtEpochMs").asLong(0L);
    List<Message> history = new ArrayList<>();
    JsonNode arr = root.path("history");
    if (arr.isArray()) {
      for (JsonNode n : arr) {
        Message m = deserializeMessage(n);
        if (m != null) history.add(m);
      }
    }
    return SavedContext.builder()
        .runId(runId)
        .taskId(taskId)
        .iteration(iteration)
        .history(history)
        .savedAt(Instant.ofEpochMilli(savedAt))
        .sizeBytes(json.getBytes(StandardCharsets.UTF_8).length)
        .build();
  }

  private Message deserializeMessage(JsonNode n) {
    String type = n.path("type").asText("");
    return switch (type) {
      case "system" -> new SystemMessage(n.path("text").asText(""));
      case "user" -> new UserMessage(n.path("text").asText(""));
      case "assistant" -> {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        JsonNode tcs = n.path("toolCalls");
        if (tcs.isArray()) {
          for (JsonNode tc : tcs) {
            toolCalls.add(
                new AssistantMessage.ToolCall(
                    tc.path("id").asText(""),
                    tc.path("type").asText("function"),
                    tc.path("name").asText(""),
                    tc.path("arguments").asText("{}")));
          }
        }
        yield AssistantMessage.builder()
            .content(n.path("text").asText(""))
            .toolCalls(toolCalls)
            .build();
      }
      case "tool" -> {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        JsonNode rs = n.path("responses");
        if (rs.isArray()) {
          for (JsonNode r : rs) {
            responses.add(
                new ToolResponseMessage.ToolResponse(
                    r.path("id").asText(""),
                    r.path("name").asText(""),
                    r.path("responseData").asText("")));
          }
        }
        yield ToolResponseMessage.builder().responses(responses).build();
      }
      default -> {
        log.warn("ContextSnapshotCodec unknown message type on load: {}", type);
        yield null;
      }
    };
  }
}
