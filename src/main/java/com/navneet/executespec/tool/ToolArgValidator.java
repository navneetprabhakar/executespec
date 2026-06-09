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
 *   <li><b>Absent-only</b> — a field is "missing" only when the key is entirely absent. A
 *       present-but-{@code null} value is left for the tool's own handler to judge.
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
      if (field.isTextual() && !provided.has(field.asText())) {
        missing.add(field.asText());
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
