package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StringEditMatcher cascade")
class StringEditMatcherTest {

  private static StringEditMatcher.Applied applied(StringEditMatcher.Result r) {
    assertThat(r).isInstanceOf(StringEditMatcher.Applied.class);
    return (StringEditMatcher.Applied) r;
  }

  private static StringEditMatcher.Failed failed(StringEditMatcher.Result r) {
    assertThat(r).isInstanceOf(StringEditMatcher.Failed.class);
    return (StringEditMatcher.Failed) r;
  }

  @Nested
  @DisplayName("exact tier")
  class Exact {
    @Test
    void single_occurrence_replaced() {
      var r = StringEditMatcher.apply("int a = 1;\nint b = 2;\n", "int b = 2;", "int b = 3;", false, null);
      var a = applied(r);
      assertThat(a.how()).isEqualTo("exact");
      assertThat(a.updated()).isEqualTo("int a = 1;\nint b = 3;\n");
    }

    @Test
    void multiple_occurrences_without_flag_is_rejected() {
      var r = StringEditMatcher.apply("x\nx\n", "x", "y", false, null);
      assertThat(failed(r).message()).contains("occurs 2 times");
    }

    @Test
    void replace_all_replaces_every_occurrence() {
      var r = StringEditMatcher.apply("x\nx\nx\n", "x", "y", true, null);
      var a = applied(r);
      assertThat(a.how()).isEqualTo("exact-all(3)");
      assertThat(a.updated()).isEqualTo("y\ny\ny\n");
    }

    @Test
    void occurrence_picks_the_kth() {
      var r = StringEditMatcher.apply("x\nx\nx\n", "x", "Y", false, 2);
      var a = applied(r);
      assertThat(a.how()).isEqualTo("occurrence(2)");
      assertThat(a.updated()).isEqualTo("x\nY\nx\n");
    }

    @Test
    void occurrence_out_of_range_rejected() {
      var r = StringEditMatcher.apply("x\nx\n", "x", "y", false, 5);
      assertThat(failed(r).message()).contains("out of range");
    }

    @Test
    void empty_oldStr_rejected() {
      assertThat(failed(StringEditMatcher.apply("abc", "", "z", false, null)).message())
          .contains("must not be empty");
    }
  }

  @Nested
  @DisplayName("whitespace-tolerant tier (exact found nothing)")
  class WhitespaceTolerant {
    @Test
    void trailing_whitespace_drift_matches() {
      // Model's oldStr carries trailing spaces the file doesn't have -> exact fails, fuzzy catches.
      String file = "def f():\n    return 1\n";
      var r = StringEditMatcher.apply(file, "    return 1   ", "    return 2", false, null);
      var a = applied(r);
      assertThat(a.how()).isEqualTo("whitespace-tolerant");
      assertThat(a.updated()).isEqualTo("def f():\n    return 2\n");
    }

    @Test
    void multiline_block_with_trailing_drift() {
      String file = "a = 1  \nb = 2\t\nc = 3\n";
      var r = StringEditMatcher.apply(file, "a = 1\nb = 2\nc = 3", "a = 1\nb = 9\nc = 3", false, null);
      assertThat(applied(r).updated()).isEqualTo("a = 1\nb = 9\nc = 3\n");
    }

    @Test
    void ambiguous_fuzzy_match_is_rejected_not_corrupting() {
      String file = "return 1\nreturn 1\n";
      // Trailing spaces in oldStr defeat the exact tier; fuzzy then finds 2 regions -> reject.
      var r = StringEditMatcher.apply(file, "return 1  ", "return 2", false, null);
      assertThat(failed(r).message()).contains("matched 2 regions");
    }
  }

  @Nested
  @DisplayName("indent-flexible tier (re-indents newStr)")
  class IndentFlexible {
    @Test
    void indentation_drift_matches_and_reindents() {
      // File is indented 6 spaces; model used 4.
      String file = "class C:\n      x = 1\n";
      var r = StringEditMatcher.apply(file, "    x = 1", "    x = 2", false, null);
      var a = applied(r);
      assertThat(a.how()).isEqualTo("indent-flex");
      // newStr re-indented from 4 -> 6 spaces to match the file's block.
      assertThat(a.updated()).isEqualTo("class C:\n      x = 2\n");
    }

    @Test
    void multiline_reindent_preserves_relative_indentation() {
      String file = "def outer():\n        if x:\n            go()\n";
      String oldStr = "    if x:\n        go()";
      String newStr = "    if x:\n        stop()";
      var r = StringEditMatcher.apply(file, oldStr, newStr, false, null);
      assertThat(applied(r).updated()).isEqualTo("def outer():\n        if x:\n            stop()\n");
    }
  }

  @Nested
  @DisplayName("no match")
  class NoMatch {
    @Test
    void not_found_returns_actionable_failure() {
      var r = StringEditMatcher.apply("hello world\n", "goodbye", "hi", false, null);
      assertThat(failed(r).message()).contains("not found").contains("Re-read the file");
    }

    @Test
    void eof_block_without_trailing_newline_preserved() {
      String file = "line1\nlast";
      var r = StringEditMatcher.apply(file, "last ", "LAST", false, null); // trailing-space drift
      assertThat(applied(r).updated()).isEqualTo("line1\nLAST");
    }
  }
}
