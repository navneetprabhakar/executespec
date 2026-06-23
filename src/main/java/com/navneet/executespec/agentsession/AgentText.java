package com.navneet.executespec.agentsession;

final class AgentText {

  private AgentText() {}

  static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static String trim(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
  }

  static String leaf(String path) {
    if (path == null || path.isBlank()) return null;
    String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    int slash = p.lastIndexOf('/');
    return slash >= 0 ? p.substring(slash + 1) : p;
  }
}
