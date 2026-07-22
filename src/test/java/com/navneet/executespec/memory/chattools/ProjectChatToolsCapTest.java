package com.navneet.executespec.memory.chattools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * RR-564 — an oversized tool result must remain VALID JSON. The old code cut the serialized string
 * before parsing it, so a large real artifact (architecture / generated docs / code review /
 * memory) failed re-parse and was reported as an "invalid result envelope". The cap now shrinks the
 * largest string field, preserving a parseable, bounded partial document.
 */
class ProjectChatToolsCapTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void oversizedResultRemainsValidJsonAndIsTruncated() throws Exception {
    ObjectNode result = json.createObjectNode();
    result.put("ok", true);
    result.put("markdown", "M".repeat(60_000)); // far over the 24k cap

    ProjectChatTools.capOversizedFields(result, 24_000);

    // Still parseable (would have thrown before the fix).
    ObjectNode reparsed = (ObjectNode) json.readTree(result.toString());
    assertThat(result.toString().length()).isLessThanOrEqualTo(24_000);
    assertThat(reparsed.path("ok").asBoolean()).isTrue();
    assertThat(reparsed.path("truncated").asBoolean()).isTrue();
    assertThat(reparsed.path("markdown").asText()).endsWith("…[truncated]");
  }

  @Test
  void inBudgetResultIsUntouched() {
    ObjectNode result = json.createObjectNode();
    result.put("ok", true);
    result.put("summary", "short");

    ProjectChatTools.capOversizedFields(result, 24_000);

    assertThat(result.path("summary").asText()).isEqualTo("short");
    assertThat(result.has("truncated")).isFalse();
  }
}
