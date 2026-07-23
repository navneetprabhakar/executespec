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

  // RR-1281 — only a document that safely parses as YAML AND carries a real OpenAPI shape (version
  // + info + at least one path with an HTTP operation) may be committed as the authoritative
  // contract. A prefix-only string that merely "starts with openapi" is rejected.
  @Test
  void validateOpenApiAcceptsAWellFormedSpec() {
    String yaml =
        "openapi: 3.0.3\n"
            + "info:\n  title: API\n  version: '1.0'\n"
            + "paths:\n  /widgets:\n    get:\n      responses:\n        '200':\n"
            + "          description: ok\n";

    assertThat(ApiTestSuiteAgent.validateOpenApi(yaml).valid()).isTrue();
  }

  @Test
  void validateOpenApiRejectsPrefixLookingButShapelessText() {
    // Starts with "openapi" (the old check passed) but is not a valid OpenAPI document.
    String text = "openapi is a spec format. This paragraph is not a contract at all.";

    ApiTestSuiteAgent.OpenApiValidation result = ApiTestSuiteAgent.validateOpenApi(text);

    assertThat(result.valid()).isFalse();
  }

  @Test
  void validateOpenApiRejectsSpecWithNoOperations() {
    String yaml = "openapi: 3.0.3\ninfo:\n  title: API\n  version: '1.0'\npaths:\n  /widgets: {}\n";

    ApiTestSuiteAgent.OpenApiValidation result = ApiTestSuiteAgent.validateOpenApi(yaml);

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("HTTP operations");
  }

  @Test
  void validateOpenApiRejectsEmptyAndUnparseable() {
    assertThat(ApiTestSuiteAgent.validateOpenApi("").valid()).isFalse();
    assertThat(ApiTestSuiteAgent.validateOpenApi("openapi: [unbalanced").valid()).isFalse();
  }
}
