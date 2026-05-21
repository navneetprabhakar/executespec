package com.navneet.executespec.router;

public enum LlmProvider {
  ANTHROPIC("Premium"),
  OPENAI("Fastest"),
  GEMINI("Cheapest"),
  // Enterprise-only OpenAI-wire-compatible gateway. Routes the matrix's tier models through the
  // org's OpenRouter key; intentionally NOT part of the anthropic→openai→gemini failover chain.
  OPENROUTER("Gateway");

  private final String tag;

  LlmProvider(String tag) {
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }
}
