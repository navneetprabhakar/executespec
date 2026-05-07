package com.navneet.executespec.anonrun;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * R-111 follow-up — pre-flight content safety check for the anonymous-trial endpoint. Cheap
 * heuristic gate that runs BEFORE any LLM dispatch so the obvious-abuse cases (prompt-injection
 * markers, banned-content keywords, empty/garbage payloads) cost zero LLM tokens.
 *
 * <p>The full LLM-backed abuse classifier ships later — this is the pre-filter that drops the 80%
 * of trivial-abuse traffic at the door.
 *
 * <p>Deliberately simple — no regex catastrophes, just substring matching on a lower-cased view of
 * the input. False-negative-tolerant by design: anything that gets past this still has to clear the
 * cost cap + the dispatch-time spend cap.
 */
@Component
public class AbuseHeuristic {

  /**
   * Markers that suggest the visitor is trying to make our LLM ignore its system prompt or leak the
   * prompt itself.
   */
  private static final List<String> PROMPT_INJECTION_MARKERS =
      List.of(
          "ignore previous instructions",
          "ignore all previous",
          "disregard your instructions",
          "system prompt",
          "you are now",
          "act as",
          "jailbreak",
          "developer mode");

  /**
   * Topical bans — we don't want our anonymous trial used as a free bypass for content moderation
   * on other surfaces. The list is intentionally narrow; the LLM-backed classifier handles the long
   * tail.
   */
  private static final List<String> BANNED_TOPICS =
      List.of("csam", "child sexual", "build a bomb", "make a bioweapon", "synthesise nerve agent");

  public Decision check(String spec) {
    if (spec == null || spec.isBlank()) {
      return Decision.reject(
          "empty_spec", "Tell us what you want to build — even one sentence works.");
    }
    String trimmed = spec.trim();
    if (trimmed.length() < 8) {
      return Decision.reject(
          "too_short", "Add a bit more detail so we can build something useful.");
    }
    String lower = trimmed.toLowerCase();
    for (String m : PROMPT_INJECTION_MARKERS) {
      if (lower.contains(m)) {
        return Decision.reject(
            "prompt_injection",
            "We can't process that prompt. Try describing the app you want to build instead.");
      }
    }
    for (String b : BANNED_TOPICS) {
      if (lower.contains(b)) {
        return Decision.reject(
            "banned_topic",
            "We can't help with that. Try a different idea — apps, tools, websites all welcome.");
      }
    }
    return Decision.allow();
  }

  public record Decision(boolean allowed, String code, String message) {
    public static Decision allow() {
      return new Decision(true, null, null);
    }

    public static Decision reject(String code, String message) {
      return new Decision(false, code, message);
    }
  }
}
