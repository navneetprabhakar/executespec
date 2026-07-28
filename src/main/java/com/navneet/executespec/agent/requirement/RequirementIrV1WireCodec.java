package com.navneet.executespec.agent.requirement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.navneet.executespec.agent.requirement.RequirementIrCodec.RequirementIrCodecException;

/** Frozen strict codec for persisted RequirementIR schema-v1 approval receipts. */
final class RequirementIrV1WireCodec {
  private final ObjectMapper mapper;

  RequirementIrV1WireCodec(ObjectMapper objectMapper) {
    if (objectMapper == null) throw new IllegalArgumentException("objectMapper is required");
    // Do not copy the application mapper. Its naming strategy, mix-ins, inclusion rules, modules,
    // and coercion settings are rolling deployment configuration; inheriting them would let an
    // unrelated mapper change reinterpret or re-canonicalize an already-open V1 gate.
    mapper =
        new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  RequirementIrV1WireDocument read(String json) {
    if (json == null || json.isBlank()) {
      throw new RequirementIrCodecException("requirement IR V1 JSON is required");
    }
    try {
      JsonNode root = mapper.readTree(json);
      if (!root.isObject()
          || !root.path("schemaVersion").isIntegralNumber()
          || !root.path("schemaVersion").canConvertToInt()
          || root.path("schemaVersion").intValue() != RequirementIrV1WireDocument.SCHEMA_VERSION
          || !root.path("compilerFingerprint").isTextual()
          || !RequirementIrV1WireDocument.COMPILER_FINGERPRINT.equals(
              root.path("compilerFingerprint").textValue())) {
        throw new RequirementIrCodecException(
            "requirement IR V1 schema/compiler identity is unsupported");
      }
      return mapper.treeToValue(root, RequirementIrV1WireDocument.class);
    } catch (RequirementIrCodecException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new RequirementIrCodecException("requirement IR V1 deserialization failed", failure);
    }
  }

  String writeCanonical(RequirementIrV1WireDocument document) {
    if (document == null) throw new RequirementIrCodecException("V1 document is required");
    try {
      return mapper.writeValueAsString(document);
    } catch (Exception failure) {
      throw new RequirementIrCodecException("requirement IR V1 serialization failed", failure);
    }
  }
}
