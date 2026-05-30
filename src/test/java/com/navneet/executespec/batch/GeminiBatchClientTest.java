package com.navneet.executespec.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * B1 (audit 2026-05-30) — pure JSON shaping of {@link GeminiBatchClient}: the inline-batch create
 * body (contents / systemInstruction / functionDeclarations + per-request metadata key), the
 * Operation→state map, and the inlined-response parse. The batch envelope/endpoint themselves carry
 * elevated uncertainty (see the class javadoc) and need a live smoke; these tests pin the shaping
 * that IS deterministic.
 */
class GeminiBatchClientTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final GeminiBatchClient client =
      new GeminiBatchClient(
          mapper,
          mock(ApiKeyResolver.class),
          mock(PipelineRunRepository.class),
          "https://generativelanguage.googleapis.com",
          "k-system");

  private static ToolCallback tool(String name, String schemaJson) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name(name)
            .description(name + " desc")
            .inputSchema(schemaJson)
            .build();
    ToolCallback tc = mock(ToolCallback.class);
    when(tc.getToolDefinition()).thenReturn(def);
    return tc;
  }

  @Test
  void mapOperationState_coversDonePendingError() {
    assertThat(GeminiBatchClient.mapOperationState(null))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
    assertThat(GeminiBatchClient.mapOperationState(read("{\"done\":false}")))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
    assertThat(GeminiBatchClient.mapOperationState(read("{\"done\":true}")))
        .isEqualTo(BatchJob.V2BatchJobState.COMPLETED);
    assertThat(GeminiBatchClient.mapOperationState(read("{\"done\":true,\"error\":{\"code\":13}}")))
        .isEqualTo(BatchJob.V2BatchJobState.FAILED);
  }

  @Test
  void buildBatchBody_shapesInlineRequestsWithMetadataKey() {
    BatchRequest req =
        new BatchRequest(
            "task-9",
            "gemini-3.5-flash",
            List.of(new SystemMessage("sys"), new UserMessage("do it")),
            List.of(tool("writeFile", "{\"type\":\"object\"}")),
            null,
            2048);

    ObjectNode body = client.buildBatchBody(List.of(req));
    JsonNode entry = body.get("batch").get("input_config").get("requests").get("requests").get(0);

    assertThat(entry.get("metadata").get("key").asText()).isEqualTo("task-9");
    JsonNode r = entry.get("request");
    // system → systemInstruction; user → contents[role=user].
    assertThat(r.get("systemInstruction").get("parts").get(0).get("text").asText())
        .isEqualTo("sys");
    assertThat(r.get("contents").get(0).get("role").asText()).isEqualTo("user");
    assertThat(r.get("contents").get(0).get("parts").get(0).get("text").asText())
        .isEqualTo("do it");
    assertThat(r.get("generationConfig").get("maxOutputTokens").asInt()).isEqualTo(2048);
    assertThat(r.get("tools").get(0).get("functionDeclarations").get(0).get("name").asText())
        .isEqualTo("writeFile");
  }

  @Test
  void parseInlinedResponses_mapsTextToolCallAndError() {
    JsonNode op =
        read(
            """
            {"done":true,"response":{"inlinedResponses":{"inlinedResponses":[
              {"metadata":{"key":"t1"},"response":{"candidates":[{"content":{"parts":[
                {"text":"ok "},{"functionCall":{"name":"writeFile","args":{"path":"a.txt"}}}]}}],
                "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":9}}},
              {"metadata":{"key":"t2"},"error":{"message":"quota"}}
            ]}}}
            """);

    List<BatchTaskResult> results = client.parseInlinedResponses(op);
    assertThat(results).hasSize(2);

    BatchTaskResult ok = results.get(0);
    assertThat(ok.taskId()).isEqualTo("t1");
    assertThat(ok.success()).isTrue();
    assertThat(ok.assistantMessage().getText()).isEqualTo("ok ");
    assertThat(ok.assistantMessage().getToolCalls().get(0).name()).isEqualTo("writeFile");
    assertThat(ok.assistantMessage().getToolCalls().get(0).arguments()).contains("a.txt");
    assertThat(ok.inputTokens()).isEqualTo(7);
    assertThat(ok.outputTokens()).isEqualTo(9);

    assertThat(results.get(1).success()).isFalse();
    assertThat(results.get(1).errorMessage()).contains("quota");
  }

  @Test
  void parseInlinedResponses_supportsFlatFallbackShape() {
    JsonNode op =
        read(
            """
            {"done":true,"response":{"inlinedResponses":[
              {"metadata":{"key":"t1"},"response":{"candidates":[{"content":{"parts":[
                {"text":"hi"}]}}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2}}}
            ]}}
            """);
    List<BatchTaskResult> results = client.parseInlinedResponses(op);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).assistantMessage().getText()).isEqualTo("hi");
  }

  private JsonNode read(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
