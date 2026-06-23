package com.navneet.executespec.agentsession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Loads provider-specific routing prompts while keeping one shared schema contract. */
@Component
public class OrchestratorRoutingPromptCatalog {

  public static final String PROMPT_VERSION = "2026-06-20";

  public static final Set<String> ROUTES =
      Set.of(
          "CHAT_ONLY",
          "READ_ONLY_CODE_HELP",
          "PLAN_ONLY",
          "REVIEW_ONLY",
          "CLARIFY",
          "RUN_LIFECYCLE_ACTION",
          "LOCAL_TOOL_ACTION",
          "IMPLEMENTATION_PROPOSAL",
          "CODE_RUN_REQUIRED",
          "SECURITY_REJECT");

  public String promptFor(String provider) {
    String normalized =
        provider == null || provider.isBlank() ? "openai" : provider.toLowerCase(Locale.ROOT);
    return load("prompts/orchestrator/routing-" + normalized + ".md");
  }

  public String schema() {
    return load("prompts/orchestrator/routing-schema.json");
  }

  public String reasonCodes() {
    return load("prompts/orchestrator/routing-reason-codes.md");
  }

  public String fullPromptFor(String provider) {
    return promptFor(provider)
        + "\n\n## Shared Output Schema\n\n```json\n"
        + schema()
        + "\n```\n\n"
        + reasonCodes();
  }

  private static String load(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("missing orchestrator routing prompt resource: " + path, e);
    }
  }
}
