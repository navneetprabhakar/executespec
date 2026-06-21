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
  // ENTERPRISE BYOK; not in failover chain. Cheaper than Sonnet (~7×) on input rate.
  DEEPSEEK("Cheap-fast"),
  // 2026-06-09 — Fireworks AI (api.fireworks.ai), OpenAI-wire-compatible route for Chinese open
  // models (DeepSeek / Kimi / GLM). Added to A/B against OpenRouter latency on Chinese models.
  // ENTERPRISE BYOK; not in the failover chain (deliberate operator route).
  FIREWORKS("Fast-US"),
  // 2026-06-21 — Sarvam AI (api.sarvam.ai), OpenAI-wire-compatible Indian-language provider
  // (sarvam-105b / sarvam-30b). Native tool calling + JSON + reasoning_content (verified live
  // 2026-06-21: Bearer auth → 200, tool_calls emitted in OpenAI schema). India-market /
  // sovereign-AI
  // + eval/benchmark route; ENTERPRISE/BYOK, intentionally NOT in the failover chain (deliberate
  // operator route, like deepseek/openrouter/fireworks).
  SARVAM("Indic");

  private final String tag;

  LlmProvider(String tag) {
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }
}
