package com.navneet.executespec.agent;

import com.fasterxml.jackson.core.JsonProcessingException;

/** Bounded retry classification and attribution for malformed planner tool arguments. */
final class OutlinePlannerSchemaRepairPolicy {

  static final String FEEDBACK =
      "Your previous submitOutline tool arguments were not valid JSON. Re-emit the complete plan"
          + " exactly once as strict JSON matching the provided tool schema. Do not include"
          + " comments, markdown, unescaped quotes, trailing commas, or prose outside the tool"
          + " call.";

  private OutlinePlannerSchemaRepairPolicy() {}

  static boolean isMalformedToolPayload(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof JsonProcessingException) return true;
      String message = current.getMessage();
      if (message != null && message.contains("submitPlan/submitOutline args are not valid JSON"))
        return true;
    }
    return false;
  }

  static String stepId(String retryFeedback) {
    if (retryFeedback == null) return "planner.outline";
    return FEEDBACK.equals(retryFeedback)
        ? "planner.outline.schema-repair"
        : "planner.outline.validation-repair";
  }
}
