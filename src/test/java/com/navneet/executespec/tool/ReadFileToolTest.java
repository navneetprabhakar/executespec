package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ReadFileTool#clampFullRead} — the RL-2026-05-062 truncation fix. The old behaviour
 * kept a 5KB head+tail and dropped the file's middle (with a marker that doesn't exist on disk),
 * which made {@link EditFileTool}'s exact match impossible for any region the model never saw. The
 * new behaviour truncates HEAD-FIRST at a line boundary so every byte returned is contiguous and
 * byte-exact from line 1, with the notice appended at the END.
 */
@DisplayName("ReadFileTool.clampFullRead")
class ReadFileToolTest {

  @Test
  void small_content_is_returned_unchanged() {
    String content = "line1\nline2\nline3\n";
    assertThat(ReadFileTool.clampFullRead(content)).isEqualTo(content);
  }

  @Test
  void null_is_passed_through() {
    assertThat(ReadFileTool.clampFullRead(null)).isNull();
  }

  @Test
  void over_line_cap_truncates_head_first_byte_exact_with_notice() {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 2500; i++) sb.append("L").append(i).append("\n");
    String big = sb.toString();

    String out = ReadFileTool.clampFullRead(big);

    assertThat(out).isNotEqualTo(big);
    // Head is contiguous + byte-exact from line 1 (so editFile can match it).
    assertThat(out).startsWith("L1\nL2\nL3\n");
    // Notice is appended at the END, not spliced into the middle, and reports honest counts.
    int noticeAt = out.indexOf("...[truncated: showing the first");
    assertThat(noticeAt).isGreaterThan(0);
    assertThat(out).contains("first " + ReadFileTool.MAX_FULL_READ_LINES + " of 2501 lines");
    // No "omitted"-style middle hole in the returned content body.
    assertThat(out.substring(0, noticeAt)).doesNotContain("omitted");
  }

  @Test
  void single_huge_line_over_char_cap_is_truncated() {
    String huge = "x".repeat(ReadFileTool.MAX_FULL_READ_CHARS + 10_000);
    String out = ReadFileTool.clampFullRead(huge);
    assertThat(out.length()).isLessThan(huge.length());
    assertThat(out).startsWith("xxxxx").contains("truncated");
  }
}
