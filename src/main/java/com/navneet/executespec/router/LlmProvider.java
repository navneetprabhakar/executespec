package com.navneet.executespec.router;

public enum LlmProvider {
  ANTHROPIC("Premium"),
  OPENAI("Fastest"),
  GEMINI("Cheapest"),
  // Enterprise-only OpenAI-wire-compatible gateway. Routes the matrix's tier models through the
  // org's OpenRouter key; intentionally NOT part of the anthropic→openai→gemini failover chain.
  OPENROUTER("Gateway"),
  // 2026-05-28 (RL-2026-05-052) — DeepSeek native (api.deepseek.com), OpenAI-wire-compatible.
  // Same DeepSeek V4 models that OpenRouter routes to, but direct → eliminates OpenRouter's
  // routing latency (observed ~3-4× slower per instance via OpenRouter in the mixed10 eval).
  // ENTERPRISE BYOK; first hop in the DeepSeek -> OpenRouter -> Fireworks recovery chain.
  DEEPSEEK("Cheap-fast"),
  // 2026-06-09 — Fireworks AI (api.fireworks.ai), OpenAI-wire-compatible route for Chinese open
  // models (DeepSeek / Kimi / GLM). Added to A/B against OpenRouter latency on Chinese models.
  // ENTERPRISE BYOK; final hop in the DeepSeek recovery chain (never default-selected).
  FIREWORKS("Fast-US"),
  GANESH("Self-hosted");

  private final String tag;

  LlmProvider(String tag) {
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }
}
