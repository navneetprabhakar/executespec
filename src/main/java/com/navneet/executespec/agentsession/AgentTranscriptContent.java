package com.navneet.executespec.agentsession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Byte-exact metadata plus a bounded event preview for one durable assistant response. */
final class AgentTranscriptContent {

  static final int EVENT_PREVIEW_CHARS = 16_000;

  private AgentTranscriptContent() {}

  static Metadata metadata(String content) {
    String exact = content == null ? "" : content;
    byte[] bytes = exact.getBytes(StandardCharsets.UTF_8);
    return new Metadata(
        exact.length(), bytes.length, lineCount(exact), sha256(bytes), exact.isEmpty());
  }

  static Map<String, Object> eventPreview(String content) {
    String exact = content == null ? "" : content;
    Metadata metadata = metadata(exact);
    boolean truncated = exact.length() > EVENT_PREVIEW_CHARS;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("text", truncated ? exact.substring(0, EVENT_PREVIEW_CHARS) : exact);
    payload.put("truncated", truncated);
    payload.put("originalChars", metadata.chars());
    payload.put("originalBytes", metadata.bytes());
    payload.put("originalLines", metadata.lines());
    payload.put("sha256", metadata.sha256());
    return payload;
  }

  private static int lineCount(String content) {
    if (content.isEmpty()) return 0;
    int lines = 1;
    for (int index = 0; index < content.length(); index++) {
      if (content.charAt(index) == '\n') lines++;
    }
    return lines;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  record Metadata(int chars, int bytes, int lines, String sha256, boolean empty) {}
}
