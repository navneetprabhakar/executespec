package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** Pins the token fields emitted by benchmark generation for manifest/publish attribution. */
class BenchmarkGenerationTokenContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void generateResultExposesInputAndOutputTokens() throws Exception {
    var result =
        new BenchmarkGenerationService.GenerateResult(
            "run-1",
            "task-1",
            "DEEPSEEK",
            "deepseek-v4-pro-native",
            "COMPLETED",
            3,
            5,
            1200,
            0.12,
            4567,
            321,
            "/tmp/submission.patch",
            "/tmp/preds.json",
            null);

    var json = mapper.readTree(mapper.writeValueAsString(result));
    assertThat(json.get("inputTokens").asLong()).isEqualTo(4567L);
    assertThat(json.get("outputTokens").asLong()).isEqualTo(321L);
  }
}
