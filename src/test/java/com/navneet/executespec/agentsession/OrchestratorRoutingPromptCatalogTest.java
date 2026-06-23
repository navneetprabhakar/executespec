package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class OrchestratorRoutingPromptCatalogTest {

  private final OrchestratorRoutingPromptCatalog catalog = new OrchestratorRoutingPromptCatalog();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void providerPromptsExistAndUseSharedRouteContract() throws Exception {
    for (String provider : java.util.List.of("openai", "anthropic", "gemini", "deepseek")) {
      String prompt = catalog.fullPromptFor(provider);

      assertThat(prompt)
          .contains("JSON")
          .contains("CODE_RUN_REQUIRED")
          .contains("SECURITY_REJECT")
          .contains("Find the bug")
          .contains("Fix the bug");
      for (String route : OrchestratorRoutingPromptCatalog.ROUTES) {
        assertThat(prompt).as(provider + " prompt contains " + route).contains(route);
      }
    }

    JsonNode schema = mapper.readTree(catalog.schema());
    JsonNode routeEnum = schema.at("/properties/route/enum");
    assertThat(routeEnum).hasSize(OrchestratorRoutingPromptCatalog.ROUTES.size());
    for (String route : OrchestratorRoutingPromptCatalog.ROUTES) {
      assertThat(routeEnum.toString()).contains(route);
    }
  }
}
