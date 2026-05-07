package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the language-convention hint mapping introduced for Fix D (run a076c876 T2 diagnosis,
 * 2026-05-03). The planner uses this block via {@code buildMultiRepoSummary} → workspaceSummary →
 * outline-planner.md prompt to translate user-spec filename conventions into the repo's detected
 * language.
 *
 * <p>The block is critical: T2's failure mode was the planner templating TypeScript
 * (`src/config/index.ts`, `process.env.SCAN_CONFIDENCE_THRESHOLD`) into a Python repo's task
 * description, after which the worker had to spend 10 iterations reconciling the spec's TypeScript
 * wording against the actual Python code on disk. With this hint visible to the planner, the
 * description gets translated up-front (`src/config.py`, {@code os.getenv(...)}).
 *
 * <p>This test pins the hint mapping for known languages + the conservative fallback. The actual
 * block-rendering logic (per-repo iteration, JSON parsing) lives in {@code
 * buildLanguageParityBlock} which is exercised by the {@code RunOrchestrator}'s integration path;
 * these are unit tests of the pure mapping function.
 */
class LanguageParityBlockTest {

  @Test
  void python_hint_mentions_snake_case_and_pytest() {
    String hint = RunOrchestrator.languageConventionHint("Python", "FastAPI");
    assertTrue(hint.contains("snake_case"), "Python hint must mention snake_case: " + hint);
    assertTrue(hint.contains(".py"), "Python hint must mention .py extension: " + hint);
    assertTrue(hint.contains("pytest"), "Python hint must mention pytest: " + hint);
    assertFalse(hint.contains(".ts"), "Python hint must not mention .ts extension: " + hint);
  }

  @Test
  void typescript_hint_mentions_ts_and_vitest_or_jest() {
    String hint = RunOrchestrator.languageConventionHint("TypeScript/JS", "React");
    assertTrue(hint.contains(".ts"), "TS hint must mention .ts: " + hint);
    assertTrue(
        hint.contains("vitest") || hint.contains("jest"),
        "TS hint must mention test framework: " + hint);
    assertTrue(hint.contains("process.env"), "TS hint must mention process.env: " + hint);
  }

  @Test
  void go_hint_mentions_go_extension_and_test_suffix() {
    String hint = RunOrchestrator.languageConventionHint("Go", null);
    assertTrue(hint.contains(".go"));
    assertTrue(hint.contains("_test.go"), "Go hint should mention _test.go convention: " + hint);
  }

  @Test
  void rust_hint_mentions_rs_and_cfg_test() {
    String hint = RunOrchestrator.languageConventionHint("Rust", "Axum");
    assertTrue(hint.contains(".rs"));
    assertTrue(hint.contains("snake_case"), "Rust hint must mention snake_case: " + hint);
  }

  @Test
  void java_hint_mentions_PascalCase_and_junit() {
    String hint = RunOrchestrator.languageConventionHint("Java", "Spring Boot");
    assertTrue(hint.contains("PascalCase"));
    assertTrue(hint.contains("JUnit"));
  }

  @Test
  void unknown_language_falls_back_to_generic_hint() {
    String hint = RunOrchestrator.languageConventionHint("Brainfuck", null);
    assertTrue(
        hint.contains("match the existing repo"),
        "Unknown languages should fall back to a generic hint: " + hint);
    // No false claims about file extensions.
    assertFalse(hint.contains(".py"));
    assertFalse(hint.contains(".ts"));
  }

  @Test
  void null_language_falls_back_to_generic_hint() {
    String hint = RunOrchestrator.languageConventionHint(null, null);
    assertTrue(hint.contains("match the existing repo"));
  }

  @Test
  void blank_language_falls_back_to_generic_hint() {
    String hint = RunOrchestrator.languageConventionHint("   ", "");
    assertTrue(hint.contains("match the existing repo"));
  }

  @Test
  void mapping_is_case_insensitive() {
    String hint1 = RunOrchestrator.languageConventionHint("python", null);
    String hint2 = RunOrchestrator.languageConventionHint("PYTHON", null);
    String hint3 = RunOrchestrator.languageConventionHint("Python", null);
    assertEquals(hint1, hint2);
    assertEquals(hint2, hint3);
  }
}
