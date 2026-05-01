package com.navneet.executespec.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** R-339 — pins the {@code {{slot}}} substitution rules. */
class SpecComposerTest {

  @Test
  void substitutesSlotWithUserValue() {
    String tpl = "Charge in {{currency}} on success.";
    String out = SpecComposer.compose(tpl, Map.of("currency", "USD"), Map.of());
    assertEquals("Charge in USD on success.", out);
  }

  @Test
  void fallsBackToDefaultWhenUserValueAbsent() {
    String tpl = "Path: {{path}}.";
    String out = SpecComposer.compose(tpl, Map.of(), Map.of("path", "/auth/callback"));
    assertEquals("Path: /auth/callback.", out);
  }

  @Test
  void userValueOverridesDefault() {
    String tpl = "Currency: {{currency}}.";
    String out = SpecComposer.compose(tpl, Map.of("currency", "INR"), Map.of("currency", "USD"));
    assertEquals("Currency: INR.", out);
  }

  @Test
  void blankUserValueFallsBackToDefault() {
    String tpl = "Path: {{path}}.";
    String out = SpecComposer.compose(tpl, Map.of("path", "  "), Map.of("path", "/default"));
    assertEquals("Path: /default.", out);
  }

  @Test
  void unknownPlaceholderLeftAsIs() {
    String tpl = "We don't know about {{mystery_slot}}.";
    String out = SpecComposer.compose(tpl, Map.of(), Map.of());
    assertEquals("We don't know about {{mystery_slot}}.", out);
  }

  @Test
  void multipleOccurrencesAllSubstituted() {
    String tpl = "{{x}} and {{x}} again.";
    String out = SpecComposer.compose(tpl, Map.of("x", "42"), Map.of());
    assertEquals("42 and 42 again.", out);
  }

  @Test
  void allowsWhitespaceInPlaceholder() {
    String tpl = "{{  currency  }} please.";
    String out = SpecComposer.compose(tpl, Map.of("currency", "EUR"), Map.of());
    assertEquals("EUR please.", out);
  }

  @Test
  void emptyTemplate_returnsEmpty() {
    assertEquals("", SpecComposer.compose("", Map.of("x", "y"), Map.of()));
    assertEquals("", SpecComposer.compose(null, Map.of("x", "y"), Map.of()));
  }

  @Test
  void noPlaceholders_returnsTemplateUnchanged() {
    String tpl = "Plain text with no slots.";
    assertEquals(tpl, SpecComposer.compose(tpl, Map.of(), Map.of()));
  }

  @Test
  void declaredButBlankSlot_substitutesEmpty() {
    // Slot is declared (in defaults map) but empty + no user
    // value — substitute "" (validation upstream caught required+blank).
    String tpl = "Greeting: '{{greet}}'.";
    Map<String, String> defaults = new java.util.HashMap<>();
    defaults.put("greet", "");
    String out = SpecComposer.compose(tpl, Map.of(), defaults);
    assertEquals("Greeting: ''.", out);
  }
}
