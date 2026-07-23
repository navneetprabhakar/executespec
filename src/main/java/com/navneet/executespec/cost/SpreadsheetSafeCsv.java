package com.navneet.executespec.cost;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC-4180 writer with formula-injection protection for every textual cell. */
final class SpreadsheetSafeCsv {

  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private final StringBuilder body = new StringBuilder();

  void row(Cell... cells) {
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) body.append(',');
      body.append(encode(cells[i]));
    }
    body.append("\r\n");
  }

  byte[] bytes() {
    byte[] content = body.toString().getBytes(StandardCharsets.UTF_8);
    byte[] result = new byte[UTF8_BOM.length + content.length];
    System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
    System.arraycopy(content, 0, result, UTF8_BOM.length, content.length);
    return result;
  }

  static Cell text(Object value) {
    return new Cell(value == null ? "" : String.valueOf(value), true);
  }

  static Cell number(Object value) {
    return new Cell(value == null ? "" : String.valueOf(value), false);
  }

  private static String encode(Cell cell) {
    String value = cell.value();
    if (cell.textual()) value = neutralizeFormula(value);
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private static String neutralizeFormula(String value) {
    if (value.isEmpty()) return value;
    int first = 0;
    while (first < value.length() && Character.isWhitespace(value.charAt(first))) first++;
    if (first >= value.length()) return value;
    char marker = value.charAt(first);
    if (marker == '=' || marker == '+' || marker == '-' || marker == '@') {
      return "'" + value;
    }
    return value;
  }

  record Cell(String value, boolean textual) {}

  /** Test-only parser for the simple quoted rows emitted above. */
  static List<String> parseRowForTest(String row) {
    List<String> cells = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < row.length(); i++) {
      char c = row.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (c == ',' && !quoted) {
        cells.add(current.toString());
        current.setLength(0);
      } else if (c != '\r' && c != '\n') {
        current.append(c);
      }
    }
    cells.add(current.toString());
    return cells;
  }
}
