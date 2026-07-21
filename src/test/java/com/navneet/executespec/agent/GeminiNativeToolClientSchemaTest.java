package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;

import org.junit.jupiter.api.Test;

class GeminiNativeToolClientSchemaTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final GeminiNativeToolClient client =
      new GeminiNativeToolClient(
          mapper,
          mock(FeatureFlagService.class),
          mock(RunCostTracker.class),
          mock(RunStepUsageRecorder.class));

  @Test
  void addsGeminiRequiredItemsToGenericArrayProperties() throws Exception {
    JsonNode sanitized =
        client.sanitizeSchemaForGemini(
            mapper.readTree(
                """
{"type":"object","properties":{"plan":{"type":"array"},"items":{"type":"array"},"tags":{"type":"array"}},"additionalProperties":true}
"""));

    assertThat(sanitized.has("additionalProperties")).isFalse();
    assertThat(sanitized.path("properties").path("plan").path("items").path("type").asText())
        .isEqualTo("object");
    assertThat(sanitized.path("properties").path("items").path("items").path("type").asText())
        .isEqualTo("object");
    assertThat(sanitized.path("properties").path("tags").path("items").path("type").asText())
        .isEqualTo("string");
  }

  @Test
  void preservesAndRecursivelySanitizesExplicitArrayItems() throws Exception {
    JsonNode sanitized =
        client.sanitizeSchemaForGemini(
            mapper.readTree(
                """
{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"labels":{"type":"array"}}}}
"""));

    assertThat(sanitized.path("items").path("additionalProperties").isMissingNode()).isTrue();
    assertThat(
            sanitized
                .path("items")
                .path("properties")
                .path("labels")
                .path("items")
                .path("type")
                .asText())
        .isEqualTo("string");
  }
}
