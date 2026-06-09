package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B4 (RL-2026-06, vix-leverage W1.3) — pins the conservative, fail-open, absent-only contract of
 * {@link ToolArgValidator}: it must catch a genuinely-absent declared-required field (naming it)
 * and must NOT block anything else (no required list, unparseable schema, advisory-only absence, or
 * a present-but-null required field).
 */
@DisplayName("ToolArgValidator (B4 — conservative required-field validation)")
class ToolArgValidatorTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** path required, reason advisory. */
  private static final String SCHEMA_PATH_REQUIRED =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
          + "\"reason\":{\"type\":\"string\"}},\"required\":[\"path\"]}";

  @Test
  @DisplayName("required field present → valid (empty)")
  void requiredPresent() {
    assertThat(ToolArgValidator.validate(SCHEMA_PATH_REQUIRED, json("{\"path\":\"a.txt\"}")))
        .isEmpty();
  }

  @Test
  @DisplayName("required field absent → error names the field")
  void requiredAbsent() {
    Optional<String> err =
        ToolArgValidator.validate(SCHEMA_PATH_REQUIRED, json("{\"reason\":\"x\"}"));
    assertThat(err).isPresent();
    assertThat(err.get()).contains("path");
  }

  @Test
  @DisplayName("only the advisory (non-required) field absent → valid (empty)")
  void advisoryAbsentAllowed() {
    assertThat(ToolArgValidator.validate(SCHEMA_PATH_REQUIRED, json("{\"path\":\"a.txt\"}")))
        .isEmpty();
  }

  @Test
  @DisplayName("multiple required absent → all named")
  void multipleMissing() {
    String schema = "{\"type\":\"object\",\"required\":[\"oldStr\",\"newStr\"]}";
    Optional<String> err = ToolArgValidator.validate(schema, json("{}"));
    assertThat(err).isPresent();
    assertThat(err.get()).contains("oldStr").contains("newStr");
  }

  @Test
  @DisplayName("schema with no required array → valid (skip)")
  void noRequired() {
    assertThat(ToolArgValidator.validate("{\"type\":\"object\"}", json("{}"))).isEmpty();
  }

  @Test
  @DisplayName("unparseable schema → fail-open (empty)")
  void unparseableSchema() {
    assertThat(ToolArgValidator.validate("{ not json", json("{}"))).isEmpty();
  }

  @Test
  @DisplayName("blank / null schema → fail-open (empty)")
  void blankSchema() {
    assertThat(ToolArgValidator.validate("", json("{}"))).isEmpty();
    assertThat(ToolArgValidator.validate(null, json("{}"))).isEmpty();
  }

  @Test
  @DisplayName("null args + required field → error names the field")
  void nullArgs() {
    Optional<String> err = ToolArgValidator.validate(SCHEMA_PATH_REQUIRED, null);
    assertThat(err).isPresent();
    assertThat(err.get()).contains("path");
  }

  @Test
  @DisplayName("present-but-null required field → allowed (absent-only policy; tool owns the null)")
  void presentButNull() {
    assertThat(ToolArgValidator.validate(SCHEMA_PATH_REQUIRED, json("{\"path\":null}"))).isEmpty();
  }
}
