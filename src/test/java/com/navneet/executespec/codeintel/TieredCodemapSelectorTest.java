package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pins the Phase-1 tiered codemap selector (RL-2026-06-003). */
class TieredCodemapSelectorTest {

  private static final String CODEMAP =
      """
      # acme/widgets

      **Files**: 5

      ## Top-level layout

      - src
      - tests

      ## Files (listing)

      - `src/colors.py`
      - `src/widgets.py`
      - `tests/test_colors.py`

      ## Functions

      Per-file map of public functions. Extracted from 2 files (4 symbols total).

      ### `src/colors.py`

      - `class Colormap`
      - `def to_rgba`

      ### `src/widgets.py`

      - `class Widget`
      - `def render`
      """;

  @Test
  void selectsTheSpecRelevantFileAndDropsTheFlatListing() {
    String out =
        TieredCodemapSelector.select(
            CODEMAP, "Colormap.to_rgba in colors.py mishandles the alpha channel", 6000);

    assertThat(out).isNotNull();
    // T1 — the relevant file's symbols are injected
    assertThat(out).contains("src/colors.py").contains("Colormap").contains("to_rgba");
    // the irrelevant file's symbol block is NOT injected
    assertThat(out).doesNotContain("src/widgets.py").doesNotContain("class Widget");
    // T0 structure is kept; the flat file listing is dropped
    assertThat(out).contains("## Top-level layout");
    assertThat(out).doesNotContain("## Files (listing)");
    assertThat(out).contains("most relevant to this task");
  }

  @Test
  void blankSpecReturnsNullToSignalHeadTruncateFallback() {
    assertThat(TieredCodemapSelector.select(CODEMAP, "   ", 6000)).isNull();
    assertThat(TieredCodemapSelector.select(CODEMAP, null, 6000)).isNull();
  }

  @Test
  void unparseableCodemapReturnsNull() {
    assertThat(TieredCodemapSelector.select("just prose, no sections here", "fix the bug", 6000))
        .isNull();
    assertThat(TieredCodemapSelector.select(null, "spec", 6000)).isNull();
    assertThat(TieredCodemapSelector.select(CODEMAP, "spec", 0)).isNull();
  }

  @Test
  void unrelatedSpecStillReturnsT0SkeletonNotSymbols() {
    String out =
        TieredCodemapSelector.select(CODEMAP, "database migration postgres sql index", 6000);
    assertThat(out).isNotNull();
    assertThat(out).contains("## Top-level layout");
    // nothing matched → no symbol blocks injected
    assertThat(out).doesNotContain("class Colormap").doesNotContain("class Widget");
  }

  @Test
  void respectsTheCharBudget() {
    String out =
        TieredCodemapSelector.select(CODEMAP, "Colormap to_rgba colors widgets render", 400);
    assertThat(out).isNotNull();
    assertThat(out.length()).isLessThanOrEqualTo(400);
  }
}
