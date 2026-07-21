package com.navneet.executespec.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class RunDetailRoutePolicySerializationTest {

  @Test
  void exposesDurableRequestedRoutePolicyOnTheRunDetailWireContract() throws Exception {
    RunDetail detail =
        RunDetail.builder()
            .runId("run-1")
            .aiProvider("DEEPSEEK")
            .requestedProvider("DEEPSEEK")
            .requestedModel("deepseek-v4-pro-native")
            .routingMode("EXACT_LOCK")
            .routePinned(true)
            .build();

    var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(detail));

    assertThat(json.path("requestedProvider").asText()).isEqualTo("DEEPSEEK");
    assertThat(json.path("requestedModel").asText()).isEqualTo("deepseek-v4-pro-native");
    assertThat(json.path("routingMode").asText()).isEqualTo("EXACT_LOCK");
    assertThat(json.path("routePinned").asBoolean()).isTrue();
  }
}
