package com.navneet.executespec.integration;

import java.io.StringWriter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/** Canonical, typed and delimiter-safe bytes used to identify an exact plan decision request. */
record DecisionRequestBytesV1(
    int schemaVersion,
    String decision,
    String actorUserId,
    String resolutionSource,
    String resolutionReason,
    String executionMode,
    Integer creditsConfirmed,
    String actualPlanFingerprint,
    String suppliedPlanFingerprint,
    String breakingChangeAck,
    String breakingChangeReportDigest) {

  static final int SCHEMA_VERSION = 1;

  String canonicalJson() {
    try {
      StringWriter out = new StringWriter(512);
      JsonGenerator json = new JsonFactory().createGenerator(out);
      json.writeStartObject();
      json.writeNumberField("schemaVersion", schemaVersion);
      string(json, "decision", decision);
      string(json, "actorUserId", actorUserId);
      string(json, "resolutionSource", resolutionSource);
      string(json, "resolutionReason", resolutionReason);
      string(json, "executionMode", executionMode);
      if (creditsConfirmed == null) json.writeNullField("creditsConfirmed");
      else json.writeNumberField("creditsConfirmed", creditsConfirmed);
      string(json, "actualPlanFingerprint", actualPlanFingerprint);
      string(json, "suppliedPlanFingerprint", suppliedPlanFingerprint);
      string(json, "breakingChangeAck", breakingChangeAck);
      string(json, "breakingChangeReportDigest", breakingChangeReportDigest);
      json.writeEndObject();
      json.close();
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize plan-decision request", e);
    }
  }

  String sha256() {
    return DecisionEvidenceV1.sha256(canonicalJson());
  }

  private static void string(JsonGenerator json, String name, String value) throws Exception {
    if (value == null) json.writeNullField(name);
    else json.writeStringField(name, value);
  }
}
