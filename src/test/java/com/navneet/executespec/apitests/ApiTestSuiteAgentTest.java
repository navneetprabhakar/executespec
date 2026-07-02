package com.navneet.executespec.apitests;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiTestSuiteAgentTest {

  @Test
  void stripMarkdownFencesHandlesYamlAndBareFences() {
    String yaml = "openapi: \"3.0.3\"\ninfo:\n  title: API\n";

    assertThat(ApiTestSuiteAgent.stripMarkdownFences("```yaml\n" + yaml + "```"))
        .isEqualTo(yaml.trim());
    assertThat(ApiTestSuiteAgent.stripMarkdownFences("```\n" + yaml + "```"))
        .isEqualTo(yaml.trim());
    assertThat(ApiTestSuiteAgent.stripMarkdownFences(yaml)).isEqualTo(yaml.trim());
  }
}
