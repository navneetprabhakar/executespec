package com.navneet.executespec.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Small JSON boundary for the append-only correction action ledger. */
final class CorrectionActionJson {

  private CorrectionActionJson() {}

  static ArrayNode read(ObjectMapper objectMapper, String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (json == null || json.isBlank()) return objectMapper.createArrayNode();
    JsonNode parsed = objectMapper.readTree(json);
    if (parsed != null && parsed.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      parsed.forEach(copy::add);
      return copy;
    }
    return objectMapper.createArrayNode();
  }
}
