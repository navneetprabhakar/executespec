package com.navneet.executespec.agent;

import java.util.Locale;

/** Detects provider transport/tool syntax that must never be rendered as a customer answer. */
public final class ToolProtocolLeakGuard {

  private ToolProtocolLeakGuard() {}

  public static boolean containsProtocolMarkup(String text) {
    if (text == null || text.isBlank()) return false;
    String normalized = text.toLowerCase(Locale.ROOT);
    return normalized.contains("<｜｜dsml｜｜tool_calls>")
        || normalized.contains("<｜｜dsml｜｜invoke ")
        || normalized.contains("<tool_call>")
        || normalized.contains("</tool_call>")
        || normalized.contains("<tool_calls>")
        || normalized.contains("<function_call>");
  }
}
