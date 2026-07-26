package com.navneet.executespec.agent.requirement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Strict schema-versioned JSON codec and canonical digest for requirement artifacts. */
public final class RequirementIrCodec {
  private final ObjectMapper mapper;

  public RequirementIrCodec(ObjectMapper objectMapper) {
    if (objectMapper == null) throw new IllegalArgumentException("objectMapper is required");
    this.mapper =
        objectMapper
            .copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            // Some schema fields are intentionally nullable. Required creator properties are
            // checked explicitly below so permissive caller configuration cannot weaken the codec.
            .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  public String writeCanonical(RequirementIrDocument document) {
    if (document == null) throw new RequirementIrCodecException("document is required");
    requireSupported(document.schemaVersion());
    try {
      return mapper.writeValueAsString(document);
    } catch (Exception e) {
      throw new RequirementIrCodecException("requirement IR serialization failed", e);
    }
  }

  public RequirementIrDocument read(String json) {
    if (json == null || json.isBlank()) {
      throw new RequirementIrCodecException("requirement IR JSON is required");
    }
    try {
      JsonNode root = mapper.readTree(json);
      if (!root.has("schemaVersion") || !root.path("schemaVersion").canConvertToInt()) {
        throw new RequirementIrCodecException("requirement IR schemaVersion is required");
      }
      requireSupported(root.path("schemaVersion").intValue());
      rejectNullRequiredProperties(root);
      return mapper.treeToValue(root, RequirementIrDocument.class);
    } catch (RequirementIrCodecException e) {
      throw e;
    } catch (Exception e) {
      throw new RequirementIrCodecException("requirement IR deserialization failed", e);
    }
  }

  public String digest(RequirementIrDocument document) {
    return RequirementContractDigests.artifactDigest(writeCanonical(document));
  }

  /**
   * SHA-256 of the exact persisted legacy plan JSON bytes; whitespace and property order matter.
   */
  public String exactPlanFingerprint(String exactPlanJson) {
    String fingerprint = ExactPlanFingerprint.sha256(exactPlanJson);
    if (fingerprint == null) {
      throw new RequirementIrCodecException("exact plan JSON is required");
    }
    return fingerprint;
  }

  public String boundDigest(
      RequirementIrDocument document, String exactPlanJson, String exactSource) {
    if (exactPlanJson == null || exactSource == null) {
      throw new RequirementIrCodecException("plan JSON and source are required");
    }
    RequirementIrValidator.ValidationResult validation =
        RequirementIrValidator.validateStructure(document, exactSource);
    if (!validation.valid()) {
      String violations =
          validation.violations().stream()
              .map(violation -> violation.code().name())
              .distinct()
              .sorted()
              .collect(java.util.stream.Collectors.joining(","));
      throw new RequirementIrCodecException(
          "requirement IR cannot be bound to source: " + violations);
    }
    return RequirementContractDigests.boundDigest(
        RequirementIrValidator.sha256(exactPlanJson),
        RequirementIrValidator.sha256(exactSource),
        digest(document));
  }

  private static void requireSupported(int schemaVersion) {
    if (schemaVersion != RequirementIrDocument.CURRENT_SCHEMA_VERSION) {
      throw new RequirementIrCodecException("unsupported requirement IR schema " + schemaVersion);
    }
  }

  private static void rejectNullRequiredProperties(JsonNode root) {
    requireNonNull(
        root,
        "schemaVersion",
        "compilerFingerprint",
        "sourceSha256",
        "requirements",
        "acceptanceCriteria",
        "taskBindings",
        "issues",
        "completeness");
    requireArrayObjects(
        root.path("requirements"),
        "requirements",
        "id",
        "text",
        "kind",
        "origin",
        "state",
        "acceptanceIds",
        "taskIds");
    requireArrayObjects(
        root.path("acceptanceCriteria"),
        "acceptanceCriteria",
        "id",
        "requirementIds",
        "kind",
        "evidenceType",
        "expected");
    requireArrayObjects(root.path("taskBindings"), "taskBindings", "taskId", "requirementIds");
    requireArrayObjects(
        root.path("issues"),
        "issues",
        "code",
        "blocking",
        "message",
        "requirementIds",
        "sourceSpans");
    validateSourceSpans(root.path("requirements"), "sourceSpan");
    validateSourceSpans(root.path("acceptanceCriteria"), "sourceSpan");
    for (JsonNode issue : root.path("issues")) {
      for (JsonNode span : issue.path("sourceSpans")) requireSourceSpan(span);
    }
  }

  private static void validateSourceSpans(JsonNode nodes, String field) {
    for (JsonNode node : nodes) {
      JsonNode span = node.get(field);
      if (span != null && !span.isNull()) requireSourceSpan(span);
    }
  }

  private static void requireSourceSpan(JsonNode span) {
    requireNonNull(
        span, "startOffset", "endOffset", "startLine", "startColumn", "endLine", "endColumn");
  }

  private static void requireArrayObjects(
      JsonNode array, String arrayName, String... requiredProperties) {
    if (!array.isArray()) {
      throw new IllegalArgumentException(arrayName + " must be an array");
    }
    for (JsonNode value : array) {
      if (!value.isObject()) {
        throw new IllegalArgumentException(arrayName + " entries must be objects");
      }
      requireNonNull(value, requiredProperties);
    }
  }

  private static void requireNonNull(JsonNode object, String... properties) {
    if (!object.isObject()) throw new IllegalArgumentException("JSON object is required");
    for (String property : properties) {
      if (!object.has(property) || object.get(property).isNull()) {
        throw new IllegalArgumentException("required creator property is null: " + property);
      }
    }
  }

  public static final class RequirementIrCodecException extends IllegalArgumentException {
    RequirementIrCodecException(String message) {
      super(message);
    }

    RequirementIrCodecException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
