package com.navneet.executespec.router;

public enum LlmProvider {
  ANTHROPIC("Premium"),
  OPENAI("Fastest"),
  GEMINI("Cheapest");

  private final String tag;

  LlmProvider(String tag) {
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }
}
