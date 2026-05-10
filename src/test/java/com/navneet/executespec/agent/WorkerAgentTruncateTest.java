package com.navneet.executespec.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Pins the surrogate-pair-safe truncate behaviour added 2026-05-10. Caller-visible regression
 * showed up in the LiveLog where supplementary-plane emoji from the LLM (💭 📝 💻 🔨 📦 🧠 💸 —
 * 4-byte UTF-8, encoded as a high+low surrogate pair in Java's UTF-16 String) were being rendered
 * as {@code �} (U+FFFD REPLACEMENT CHARACTER) whenever the LLM placed one near the truncation
 * boundary in {@code previewThought()} or {@code extractPathOrCommand()}. The naive {@code
 * substring(0, max - 1)} cut inside the surrogate pair, leaving an orphaned high surrogate.
 *
 * <p>The fix backs up one {@code char} when the cut point sits between the two halves of a
 * surrogate pair, so we discard both halves before appending the ellipsis.
 */
class WorkerAgentTruncateTest {

  /** Regression case — the exact failure mode reported on dev: emoji at the cut boundary. */
  @Test
  void truncate_keepsSurrogatePairIntact_whenSupplementaryEmojiHitsBoundary() {
    // "abc💭xyz" — 💭 is U+1F4AD (supplementary plane), encoded as a surrogate pair in Java,
    // so "abc💭xyz".length() is 8, not 7. With max=5, the old impl would substring(0, 4) and
    // produce "abc<HIGH SURROGATE>" → renders as `abc�…` in the browser.
    String result = WorkerAgent.truncate("abc💭xyz", 5);
    // Must NOT contain an unpaired high surrogate.
    assertFalse(
        containsUnpairedSurrogate(result),
        "truncate must not leave an unpaired surrogate (got: " + result + ")");
    // Should back off one char and produce "abc…".
    assertEquals("abc…", result);
  }

  /** Cut just past the pair (max=6) — pair survives intact, then ellipsis. */
  @Test
  void truncate_keepsCompleteSurrogatePair_whenCutFallsAfterIt() {
    // "abc💭xyz" — chars: a(0) b(1) c(2) HIGH(3) LOW(4) x(5) y(6) z(7).
    // max=6, end = max - 1 = 5. charAt(4) is LOW, charAt(5) is 'x' (no pair to split).
    // Wait — check is "high at end-1, low at end". end-1=4=LOW, end=5='x'. No surrogate pair
    // straddling the cut. Substring(0, 5) = "abc💭" (4 visible chars, 5 char-units). Then "…".
    String result = WorkerAgent.truncate("abc💭xyz", 6);
    assertFalse(containsUnpairedSurrogate(result));
    assertEquals("abc💭…", result);
  }

  /** Cut just before the pair (max=4) — pair never enters the substring, no risk. */
  @Test
  void truncate_excludesSurrogatePair_whenCutFallsBeforeIt() {
    // "abc💭xyz" — max=4, end=3. charAt(2)='c', charAt(3)=HIGH. The check is "high at end-1, low
    // at end". end-1=2='c'. Not a high surrogate. So no back-off. Substring(0, 3) = "abc". + "…".
    String result = WorkerAgent.truncate("abc💭xyz", 4);
    assertFalse(containsUnpairedSurrogate(result));
    assertEquals("abc…", result);
  }

  /** Plain ASCII — back-off branch must not fire on non-surrogate boundaries. */
  @Test
  void truncate_plainAscii_truncatesToMaxMinusOnePlusEllipsis() {
    assertEquals("abcd…", WorkerAgent.truncate("abcdefgh", 5));
  }

  /** Variation-selector compound emoji (BMP base + U+FE0F) — not a surrogate pair, not affected. */
  @Test
  void truncate_variationSelectorEmoji_unaffected() {
    // "✏️abcd" — ✏ is U+270F (BMP, single char), ️ is U+FE0F (BMP, single char). Total length = 6.
    // No surrogate pair anywhere. max=4 → end=3 → substring(0, 3) = "✏️a" (3 char-units), + "…".
    String result = WorkerAgent.truncate("✏️abcd", 4);
    assertFalse(containsUnpairedSurrogate(result));
    assertEquals("✏️a…", result);
  }

  /** Boundary cases — short input passes through, exact-length input passes through, null safe. */
  @Test
  void truncate_shortInput_returnsAsIs() {
    assertEquals("abc", WorkerAgent.truncate("abc", 5));
  }

  @Test
  void truncate_exactLengthInput_returnsAsIs() {
    assertEquals("abcde", WorkerAgent.truncate("abcde", 5));
  }

  @Test
  void truncate_null_returnsEmpty() {
    assertEquals("", WorkerAgent.truncate(null, 5));
  }

  /** Two emoji back-to-back — long-text case where the cut lands mid-pair on the second one. */
  @Test
  void truncate_twoEmojiBackToBack_cutInsideSecondPair() {
    // "💭💭xyz" — chars: HIGH(0) LOW(1) HIGH(2) LOW(3) x(4) y(5) z(6). Length=7.
    // max=4 → end=3. charAt(2)=HIGH, charAt(3)=LOW → surrogate-pair-split → back off to end=2.
    // Substring(0, 2) = "💭" (just the first pair). + "…". Result: "💭…".
    String result = WorkerAgent.truncate("💭💭xyz", 4);
    assertFalse(containsUnpairedSurrogate(result));
    assertEquals("💭…", result);
  }

  /**
   * Util — scans the string for unpaired high or low surrogates. A correct UTF-16 string never has
   * either; if {@link WorkerAgent#truncate} ever leaves one behind, this returns true and the test
   * fails with a descriptive message.
   */
  private static boolean containsUnpairedSurrogate(String s) {
    if (s == null) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c)) {
        // Must be followed by a low surrogate.
        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
        i++; // skip the paired low
      } else if (Character.isLowSurrogate(c)) {
        // Low surrogate without a preceding high — shouldn't be possible if we got here.
        return true;
      }
    }
    return false;
  }
}
