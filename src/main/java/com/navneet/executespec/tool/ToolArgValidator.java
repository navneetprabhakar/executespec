package com.navneet.executespec.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * B4 (RL-2026-06, vix-leverage plan W1.3) — centralized, conservative pre-dispatch validation of a
 * tool call's arguments against the tool's OWN declared {@link Tool#inputSchema()}.
 *
 * <p>Scope is deliberately minimal: it enforces only the <b>presence of fields the schema itself
 * marks {@code "required"}</b>. The goal is to turn a silently-absent required argument — which
 * would otherwise reach the handler as a {@code null}/empty and write garbage — into a clear {@code
 * INVALID_ARGS} error that names the missing field, so the model can self-correct on the next turn.
 *
 * <p>It is intentionally NOT a full JSON-Schema validator: no type coercion, no {@code
 * additionalProperties}, no format/pattern checks. Those risk false-rejecting valid calls (and a
 * model-visible schema change is the exact class of regression RL-2026-05-061 warned about). Two
 * safety properties make it safe to run on the live, all-provider dispatch path:
 *
 * <ul>
 *   <li><b>Fail-open</b> — a blank/unparseable schema, or a schema with no {@code required} array,
 *       is a no-op (returns empty). The validator never blocks on its own uncertainty.
 *   <li><b>Presence + non-null</b> — a required field is "missing" when the key is absent OR
 *       present with an explicit JSON {@code null} (RR-577). A {@code null} for a declared-required
 *       field can never satisfy the schema, so it is rejected here rather than reaching the handler
 *       as garbage.
 * </ul>
 *
 * <p>A schema-compliant call (every declared-required field present) is therefore never blocked.
 */
public final class ToolArgValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ToolArgValidator() {}

  /**
   * @param inputSchema the tool's declared JSON-schema string ({@link Tool#inputSchema()})
   * @param args the parsed tool-call arguments (may be null)
   * @return an error message naming the absent required field(s), or {@link Optional#empty()} when
   *     the call is valid, the schema declares no required fields, or the schema cannot be parsed
   */
  public static Optional<String> validate(String inputSchema, JsonNode args) {
    if (inputSchema == null || inputSchema.isBlank()) {
      return Optional.empty();
    }
    JsonNode schema;
    try {
      schema = MAPPER.readTree(inputSchema);
    } catch (Exception e) {
      return Optional.empty(); // unparseable schema → fail-open, never block a dispatch
    }
    JsonNode required = schema.get("required");
    if (required == null || !required.isArray() || required.isEmpty()) {
      return Optional.empty();
    }
    JsonNode provided = (args == null) ? MAPPER.createObjectNode() : args;
    List<String> missing = new ArrayList<>();
    for (JsonNode field : required) {
      if (!field.isTextual()) {
        continue;
      }
      String fieldName = field.asText();
      JsonNode value = provided.get(fieldName);
      // RR-577 — a required field is satisfied only by a present, non-null value. A
      // present-but-explicit-null value can never satisfy the declared schema (required means "must
      // be provided") and would otherwise reach the handler as a null and write garbage, so absent
      // OR explicit-null both count as missing. Type/enum/bounds/additionalProperties enforcement
      // stays deliberately out of scope (RL-2026-05-061 model-visible false-reject regression
      // risk).
      if (value == null || value.isNull()) {
        missing.add(fieldName);
      }
    }
    if (missing.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "missing required field"
            + (missing.size() == 1 ? "" : "s")
            + ": "
            + String.join(", ", missing));
  }
}
