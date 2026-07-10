package com.navneet.executespec.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ProviderDispatchAuthorization;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * B1 (audit 2026-05-30) — pure JSON shaping of {@link OpenAiBatchClient}: JSONL input-line envelope
 * + chat body, status→state map, output-JSONL parse. HTTP (file upload/create/poll/ download) is
 * out of scope (no network here).
 */
class OpenAiBatchClientTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final OpenAiBatchClient client =
      new OpenAiBatchClient(
          mapper,
          mock(ApiKeyResolver.class),
          mock(PipelineRunRepository.class),
          mock(ProviderDispatchAuthorization.class),
          "https://api.openai.com",
          "sk-system");

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
  void mapStatus_coversEveryBranch() {
    assertThat(OpenAiBatchClient.mapStatus("completed"))
        .isEqualTo(BatchJob.V2BatchJobState.COMPLETED);
    assertThat(OpenAiBatchClient.mapStatus("failed")).isEqualTo(BatchJob.V2BatchJobState.FAILED);
    assertThat(OpenAiBatchClient.mapStatus("expired")).isEqualTo(BatchJob.V2BatchJobState.FAILED);
    assertThat(OpenAiBatchClient.mapStatus("cancelled"))
        .isEqualTo(BatchJob.V2BatchJobState.CANCELLED);
    assertThat(OpenAiBatchClient.mapStatus("in_progress"))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
    assertThat(OpenAiBatchClient.mapStatus("validating"))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
    assertThat(OpenAiBatchClient.mapStatus(null)).isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
  }

  @Test
  void buildInputJsonl_shapesOneChatRequestPerLine() throws Exception {
    BatchRequest req =
        new BatchRequest(
            "task-7",
            "gpt-5.4",
            List.of(new SystemMessage("you are a worker"), new UserMessage("write a.txt")),
            List.of(tool("writeFile", "{\"type\":\"object\",\"properties\":{\"path\":{}}}")),
            0.2,
            4096);

    String jsonl = client.buildInputJsonl(List.of(req));
    JsonNode line = mapper.readTree(jsonl); // single line

    assertThat(line.get("custom_id").asText()).isEqualTo("task-7");
    assertThat(line.get("method").asText()).isEqualTo("POST");
    assertThat(line.get("url").asText()).isEqualTo("/v1/chat/completions");
    JsonNode body = line.get("body");
    assertThat(body.get("model").asText()).isEqualTo("gpt-5.4");
    assertThat(body.get("max_tokens").asInt()).isEqualTo(4096);
    assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
    assertThat(body.get("messages")).hasSize(2);
    assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
    assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
    // OpenAI tool shape: {type:function, function:{name, description, parameters}}.
    JsonNode fn = body.get("tools").get(0);
    assertThat(fn.get("type").asText()).isEqualTo("function");
    assertThat(fn.get("function").get("name").asText()).isEqualTo("writeFile");
    assertThat(fn.get("function").get("parameters").get("type").asText()).isEqualTo("object");
  }

  @Test
  void buildInputJsonl_defaultsMaxTokensAndOmitsNullTemperature() throws Exception {
    BatchRequest req =
        new BatchRequest(
            "t1", "gpt-5.4-mini", List.of(new UserMessage("hi")), List.of(), null, null);
    JsonNode body = mapper.readTree(client.buildInputJsonl(List.of(req))).get("body");
    assertThat(body.get("max_tokens").asInt()).isEqualTo(8192);
    assertThat(body.has("temperature")).isFalse();
  }

  @Test
  void parseOutputJsonl_mapsSuccessErrorAndHttpError() {
    String jsonl =
        """
{"custom_id":"t1","response":{"status_code":200,"body":{"choices":[{"message":{"content":"hi","tool_calls":[{"id":"c1","type":"function","function":{"name":"writeFile","arguments":"{\\"path\\":\\"a.txt\\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20}}}}
{"custom_id":"t2","error":{"message":"rate limited"}}
{"custom_id":"t3","response":{"status_code":400,"body":{}}}
""";

    List<BatchTaskResult> results = client.parseOutputJsonl(jsonl);
    assertThat(results).hasSize(3);

    BatchTaskResult ok = results.get(0);
    assertThat(ok.success()).isTrue();
    assertThat(ok.assistantMessage().getText()).isEqualTo("hi");
    assertThat(ok.assistantMessage().getToolCalls().get(0).name()).isEqualTo("writeFile");
    assertThat(ok.assistantMessage().getToolCalls().get(0).arguments()).contains("a.txt");
    assertThat(ok.inputTokens()).isEqualTo(10);
    assertThat(ok.outputTokens()).isEqualTo(20);

    assertThat(results.get(1).success()).isFalse();
    assertThat(results.get(1).errorMessage()).contains("rate limited");
    assertThat(results.get(2).success()).isFalse();
    assertThat(results.get(2).errorMessage()).contains("400");
  }
}
