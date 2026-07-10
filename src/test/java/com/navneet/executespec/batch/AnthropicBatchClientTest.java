package com.navneet.executespec.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * B1 (audit 2026-05-29) — covers the pure JSON-shaping logic of {@link AnthropicBatchClient}: the
 * create-body envelope, the {@code processing_status} → state map, and the JSONL results parse. The
 * four HTTP calls are out of scope here (no network in this env — they need a live smoke).
 */
class AnthropicBatchClientTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final AnthropicBatchClient client =
      new AnthropicBatchClient(
          mapper,
          mock(ApiKeyResolver.class),
          mock(PipelineRunRepository.class),
          mock(ProviderDispatchAuthorization.class),
          "https://api.anthropic.com",
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
  void mapProcessingStatus_coversEveryBranch() {
    assertThat(AnthropicBatchClient.mapProcessingStatus("ended"))
        .isEqualTo(BatchJob.V2BatchJobState.COMPLETED);
    assertThat(AnthropicBatchClient.mapProcessingStatus("in_progress"))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
    assertThat(AnthropicBatchClient.mapProcessingStatus("canceling"))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
    assertThat(AnthropicBatchClient.mapProcessingStatus("something-new"))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
    assertThat(AnthropicBatchClient.mapProcessingStatus(null))
        .isEqualTo(BatchJob.V2BatchJobState.IN_PROGRESS);
  }

  @Test
  void buildBatchBody_shapesCustomIdAndParams() {
    BatchRequest req =
        new BatchRequest(
            "task-7",
            "claude-sonnet-4-6",
            List.of(new SystemMessage("you are a worker"), new UserMessage("write a.txt")),
            List.of(tool("writeFile", "{\"type\":\"object\",\"properties\":{\"path\":{}}}")),
            0.2,
            4096);

    ObjectNode body = client.buildBatchBody(List.of(req));

    JsonNode entry = body.get("requests").get(0);
    assertThat(entry.get("custom_id").asText()).isEqualTo("task-7");
    JsonNode params = entry.get("params");
    assertThat(params.get("model").asText()).isEqualTo("claude-sonnet-4-6");
    assertThat(params.get("max_tokens").asInt()).isEqualTo(4096);
    assertThat(params.get("temperature").asDouble()).isEqualTo(0.2);
    // SystemMessage collapses to top-level `system`; only the user message stays in `messages`.
    assertThat(params.get("system").asText()).isEqualTo("you are a worker");
    assertThat(params.get("messages")).hasSize(1);
    assertThat(params.get("messages").get(0).get("role").asText()).isEqualTo("user");
    assertThat(params.get("messages").get(0).get("content").asText()).isEqualTo("write a.txt");
    // Tool → name/description/input_schema (schema parsed to an object, not a string).
    JsonNode toolNode = params.get("tools").get(0);
    assertThat(toolNode.get("name").asText()).isEqualTo("writeFile");
    assertThat(toolNode.get("input_schema").get("type").asText()).isEqualTo("object");
  }

  @Test
  void buildBatchBody_defaultsMaxTokensAndOmitsNullTemperature() {
    BatchRequest req =
        new BatchRequest(
            "t1", "claude-haiku-4-5", List.of(new UserMessage("hi")), List.of(), null, null);

    JsonNode params = client.buildBatchBody(List.of(req)).get("requests").get(0).get("params");

    assertThat(params.get("max_tokens").asInt()).isEqualTo(8192); // DEFAULT_MAX_TOKENS
    assertThat(params.has("temperature")).isFalse();
    assertThat(params.has("system")).isFalse(); // no system message supplied
  }

  @Test
  void parseResultsJsonl_mapsSucceededAndErrored_skipsJunk() {
    String jsonl =
        """
{"custom_id":"t1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"hello "},{"type":"tool_use","id":"tu1","name":"writeFile","input":{"path":"a.txt"}}],"usage":{"input_tokens":12,"output_tokens":34}}}}

{"custom_id":"t2","result":{"type":"errored","error":{"type":"invalid_request","message":"bad params"}}}
not-json-at-all
""";

    List<BatchTaskResult> results = client.parseResultsJsonl(jsonl);

    assertThat(results).hasSize(2); // blank + junk lines skipped

    BatchTaskResult ok = results.get(0);
    assertThat(ok.taskId()).isEqualTo("t1");
    assertThat(ok.success()).isTrue();
    assertThat(ok.assistantMessage().getText()).isEqualTo("hello ");
    assertThat(ok.assistantMessage().getToolCalls()).hasSize(1);
    assertThat(ok.assistantMessage().getToolCalls().get(0).name()).isEqualTo("writeFile");
    assertThat(ok.assistantMessage().getToolCalls().get(0).arguments()).contains("a.txt");
    assertThat(ok.inputTokens()).isEqualTo(12);
    assertThat(ok.outputTokens()).isEqualTo(34);

    BatchTaskResult err = results.get(1);
    assertThat(err.taskId()).isEqualTo("t2");
    assertThat(err.success()).isFalse();
    assertThat(err.errorMessage()).contains("errored").contains("bad params");
  }

  @Test
  void parseResultsJsonl_emptyInputIsEmptyList() {
    assertThat(client.parseResultsJsonl(null)).isEmpty();
    assertThat(client.parseResultsJsonl("   ")).isEmpty();
  }
}
