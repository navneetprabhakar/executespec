package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the streamed-to-canonical folding. The whole design depends on the assembled body being
 * indistinguishable from a non-streaming one, because {@code parseResponse} — which owns tool-call
 * normalization, the abnormal-finish gate and every billing field — is reused untouched.
 */
class OpenAiWireStreamAssemblerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode assemble(String sse) throws Exception {
    OpenAiWireStreamAssembler a = new OpenAiWireStreamAssembler(mapper, 32L * 1024 * 1024);
    a.consume(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), null);
    return a.toCompletionResponse("requested-model");
  }

  @Test
  @DisplayName("tool arguments split across chunks rejoin in arrival order")
  void toolArgumentsRejoin() throws Exception {
    // This is the failure mode that matters: arguments arrive as partial JSON fragments. Joining
    // them wrongly yields a syntactically broken plan that looks like a model failure.
    String sse =
        """
data: {"id":"c1","model":"m","choices":[{"index":0,"delta":{"role":"assistant"}}]}
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"submitPlan","arguments":"{\\"tasks\\":"}}]}}]}
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[{\\"id\\":"}}]}}]}
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"T1\\"}]}"}}]}}]}
data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
data: [DONE]
""";
    ObjectNode out = assemble(sse);
    var fn = out.path("choices").get(0).path("message").path("tool_calls").get(0).path("function");
    assertThat(fn.path("name").asText()).isEqualTo("submitPlan");
    assertThat(fn.path("arguments").asText()).isEqualTo("{\"tasks\":[{\"id\":\"T1\"}]}");
    // and it must be valid JSON once rejoined
    assertThat(mapper.readTree(fn.path("arguments").asText()).path("tasks")).isNotEmpty();
    assertThat(out.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");
  }

  @Test
  @DisplayName("parallel tool calls stay separated by wire index")
  void parallelToolCallsSeparateByIndex() throws Exception {
    String sse =
        """
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"readFile","arguments":"{\\"p\\":1}"}}]}}]}
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"listFiles","arguments":"{\\"p\\":2}"}}]}}]}
data: [DONE]
""";
    var tcs = assemble(sse).path("choices").get(0).path("message").path("tool_calls");
    assertThat(tcs).hasSize(2);
    assertThat(tcs.get(0).path("function").path("arguments").asText()).isEqualTo("{\"p\":1}");
    assertThat(tcs.get(1).path("function").path("arguments").asText()).isEqualTo("{\"p\":2}");
  }

  @Test
  @DisplayName("usage from the final chunk is copied verbatim, nested cache fields intact")
  void usageCopiedVerbatim() throws Exception {
    String sse =
        """
data: {"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}
data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":7},"prompt_cache_hit_tokens":7}}
data: [DONE]
""";
    ObjectNode out = assemble(sse);
    assertThat(out.path("usage").path("prompt_tokens").asInt()).isEqualTo(10);
    assertThat(out.path("usage").path("completion_tokens").asInt()).isEqualTo(3);
    // The cost tracker reads these; folding must not flatten or drop them.
    assertThat(out.path("usage").path("prompt_tokens_details").path("cached_tokens").asInt())
        .isEqualTo(7);
    assertThat(out.path("usage").path("prompt_cache_hit_tokens").asInt()).isEqualTo(7);
  }

  @Test
  @DisplayName("a stream with no usage chunk reports it rather than implying a measured zero")
  void missingUsageIsReportedNotZeroed() throws Exception {
    OpenAiWireStreamAssembler a = new OpenAiWireStreamAssembler(mapper, 1024 * 1024);
    a.consume(
        new ByteArrayInputStream(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n"
                .getBytes(StandardCharsets.UTF_8)),
        null);
    assertThat(a.sawUsage()).isFalse();
    // Absent, not zero — a zeroed usage object would be read downstream as a free call.
    assertThat(a.toCompletionResponse("m").has("usage")).isFalse();
  }

  @Test
  @DisplayName("DeepSeek reasoning_content deltas are accumulated for the round-trip")
  void reasoningContentAccumulated() throws Exception {
    String sse =
        """
        data: {"choices":[{"index":0,"delta":{"reasoning_content":"think"}}]}
        data: {"choices":[{"index":0,"delta":{"reasoning_content":"ing"},"finish_reason":"stop"}]}
        data: [DONE]
        """;
    assertThat(
            assemble(sse).path("choices").get(0).path("message").path("reasoning_content").asText())
        .isEqualTo("thinking");
  }

  @Test
  @DisplayName("an empty stream yields no choices so the existing fail-closed guard still fires")
  void emptyStreamYieldsNoChoices() throws Exception {
    // parseResponse throws on an empty choices array; that guard is what stops a content-free
    // response being metered as a paid success, so the assembler must not fabricate a choice.
    assertThat(assemble("data: [DONE]\n").path("choices")).isEmpty();
  }

  @Test
  @DisplayName("a malformed chunk fails closed instead of silently truncating tool arguments")
  void malformedChunkFailsClosed() {
    assertThatThrownBy(() -> assemble("data: {\"choices\":[\ndata: [DONE]\n"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed chunk");
  }

  @Test
  @DisplayName("the byte ceiling still applies to a stream")
  void byteCeilingApplies() {
    String big =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
            + "x".repeat(5000)
            + "\"}}]}\n";
    OpenAiWireStreamAssembler a = new OpenAiWireStreamAssembler(mapper, 1000);
    assertThatThrownBy(
            () -> a.consume(new ByteArrayInputStream(big.getBytes(StandardCharsets.UTF_8)), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("byte ceiling");
  }
}
