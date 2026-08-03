package com.navneet.executespec.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.StateStore.TimelineData;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class PipelineRunSecretSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void timelineSerializationNeverExposesDecryptedScmCredentials() throws Exception {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .githubPat("github-secret-value")
            .bitbucketAppPassword("bitbucket-secret-value")
            .build();

    JsonNode json =
        objectMapper.readTree(
            objectMapper.writeValueAsBytes(
                new TimelineData(run, List.of(), List.of(), List.of(), List.of())));

    assertThat(json.path("run").path("runId").asText()).isEqualTo("run-1");
    assertThat(json.path("run").has("githubPat")).isFalse();
    assertThat(json.path("run").has("bitbucketAppPassword")).isFalse();
    assertThat(json.toString())
        .doesNotContain("github-secret-value")
        .doesNotContain("bitbucket-secret-value");
  }
}
