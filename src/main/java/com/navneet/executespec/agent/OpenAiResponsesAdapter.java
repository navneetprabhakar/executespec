package com.navneet.executespec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stateless Responses wire conversion for GPT-6; the router retains dispatch and spend ownership.
 */
final class OpenAiResponsesAdapter {
  static final String OUTPUT = "openai_responses_output";
  static final String MODEL = "openai_responses_model";

  private OpenAiResponsesAdapter() {}

  static boolean required(String model, String baseUrl) {
    if (model == null || !(model.startsWith("gpt-6-") || model.startsWith("gpt-6.1-")))
      return false;
    var host = java.net.URI.create(baseUrl).getHost();
    return "api.openai.com".equalsIgnoreCase(host);
  }

  static ObjectNode request(
      ObjectMapper mapper, ObjectNode chat, OpenAiNativeToolClient.OpenAiToolCallRequest request) {
    ObjectNode body = mapper.createObjectNode();
    body.set("model", chat.get("model"));
    body.put("store", false);
    body.putArray("include").add("reasoning.encrypted_content");
    body.putObject("reasoning").put("effort", "low");
    if (chat.has("max_completion_tokens"))
      body.set("max_output_tokens", chat.get("max_completion_tokens"));
    ArrayNode input = body.putArray("input");
    // Replay encrypted reasoning and its function calls together, bound to their original model.
    int messageIndex = 0;
    for (JsonNode message : chat.withArray("messages")) {
      String role = message.path("role").asText();
      if ("tool".equals(role)) {
        input
            .addObject()
            .put("type", "function_call_output")
            .put("call_id", message.path("tool_call_id").asText())
            .put("output", message.path("content").asText());
        continue;
      }
      if ("assistant".equals(role) && request.messages() != null) {
        while (messageIndex < request.messages().size()
            && !(request.messages().get(messageIndex)
                instanceof org.springframework.ai.chat.messages.AssistantMessage)) messageIndex++;
        if (messageIndex < request.messages().size()) {
          var assistant = request.messages().get(messageIndex++);
          var metadata = assistant.getMetadata();
          if (request.model().equals(metadata.get(MODEL)) && metadata.containsKey(OUTPUT)) {
            JsonNode output = mapper.valueToTree(metadata.get(OUTPUT));
            if (output.isArray()) {
              output.forEach(input::add);
              continue;
            }
          }
        }
      }
      JsonNode content = message.get("content");
      if (content != null
          && !content.isNull()
          && !(content.isTextual() && content.asText().isEmpty())) {
        ObjectNode item = input.addObject().put("role", role);
        if (content.isArray()) {
          ArrayNode parts = item.putArray("content");
          for (JsonNode part : content) {
            if ("image_url".equals(part.path("type").asText())) {
              parts
                  .addObject()
                  .put("type", "input_image")
                  .put("image_url", part.path("image_url").path("url").asText());
            } else
              parts
                  .addObject()
                  .put("type", "assistant".equals(role) ? "output_text" : "input_text")
                  .put("text", part.path("text").asText());
          }
        } else item.set("content", content);
      }
      for (JsonNode call : message.path("tool_calls")) {
        input
            .addObject()
            .put("type", "function_call")
            .put("call_id", call.path("id").asText())
            .put("name", call.path("function").path("name").asText())
            .put("arguments", call.path("function").path("arguments").asText("{}"));
      }
    }
    if (chat.has("tools")) {
      ArrayNode tools = body.putArray("tools");
      for (JsonNode tool : chat.path("tools")) {
        ObjectNode fn = ((ObjectNode) tool.path("function")).deepCopy();
        fn.put("type", "function");
        // Existing schemas deliberately include optional arguments; do not infer strict mode.
        fn.put("strict", false);
        tools.add(fn);
      }
      body.set("tool_choice", chat.get("tool_choice"));
    }
    return body;
  }

  static ObjectNode response(ObjectMapper mapper, JsonNode response) {
    String status = response.path("status").asText();
    if (!("completed".equals(status) || "incomplete".equals(status))
        || !response.path("output").isArray()) {
      throw new IllegalStateException(
          "OpenAI Responses returned an unsuccessful or malformed response");
    }
    ObjectNode chat = mapper.createObjectNode();
    chat.set("id", response.path("id"));
    chat.set("model", response.path("model"));
    ObjectNode choice = chat.putArray("choices").addObject();
    ObjectNode message = choice.putObject("message").put("role", "assistant");
    StringBuilder text = new StringBuilder();
    ArrayNode calls = message.putArray("tool_calls");
    boolean refusal = false;
    for (JsonNode item : response.path("output")) {
      if ("function_call".equals(item.path("type").asText()) && "completed".equals(status)) {
        String id = item.path("call_id").asText();
        if (id.isBlank() || item.path("name").asText().isBlank())
          throw new IllegalStateException("OpenAI Responses tool call lacks identity");
        ObjectNode call = calls.addObject().put("id", id).put("type", "function");
        call.putObject("function")
            .setAll(
                mapper
                    .createObjectNode()
                    .put("name", item.path("name").asText())
                    .put("arguments", item.path("arguments").asText("{}")));
      }
      if ("message".equals(item.path("type").asText())) {
        for (JsonNode part : item.path("content")) {
          if ("refusal".equals(part.path("type").asText())) {
            refusal = true;
            text.append(part.path("refusal").asText());
          } else if ("output_text".equals(part.path("type").asText()))
            text.append(part.path("text").asText());
        }
      }
    }
    message.put("content", text.toString());
    if ("completed".equals(status) && !refusal) {
      if (text.toString().isBlank() && calls.isEmpty()) {
        throw new IllegalStateException("OpenAI Responses completed without usable text or tools");
      }
      message.set(OUTPUT, response.path("output"));
      message.set(MODEL, response.path("model"));
    }
    choice.put(
        "finish_reason",
        refusal
            ? "content_filter"
            : "incomplete".equals(status) ? "length" : calls.isEmpty() ? "stop" : "tool_calls");
    JsonNode usage = response.path("usage");
    // Missing usage stays missing; never manufacture a successful zero-token billing record.
    if (usage.isObject()
        && usage.path("input_tokens").isIntegralNumber()
        && usage.path("output_tokens").isIntegralNumber()) {
      ObjectNode normalized = chat.putObject("usage");
      normalized.set("prompt_tokens", usage.path("input_tokens"));
      normalized.set("completion_tokens", usage.path("output_tokens"));
      normalized.set("completion_tokens_details", usage.path("output_tokens_details"));
      normalized.set("prompt_tokens_details", usage.path("input_tokens_details"));
    }
    return chat;
  }
}
