package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SpreadsheetSafeCsvTest {

  @Test
  void neutralizesFormulaPrefixesAndQuotesRfc4180Cells() {
    SpreadsheetSafeCsv csv = new SpreadsheetSafeCsv();
    csv.row(
        SpreadsheetSafeCsv.text("=HYPERLINK(\"https://evil\")"),
        SpreadsheetSafeCsv.text("  +cmd|' /C calc'!A0"),
        SpreadsheetSafeCsv.text("safe,quoted"),
        SpreadsheetSafeCsv.number(-12.5));

    byte[] bytes = csv.bytes();
    assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    String row = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    assertThat(SpreadsheetSafeCsv.parseRowForTest(row))
        .containsExactly(
            "'=HYPERLINK(\"https://evil\")", "'  +cmd|' /C calc'!A0", "safe,quoted", "-12.5");
  }
}
