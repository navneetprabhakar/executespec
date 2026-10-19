package com.navneet.executespec.agent;

import com.fasterxml.jackson.core.JsonProcessingException;

/** Bounded retry classification and attribution for malformed planner tool arguments. */
final class OutlinePlannerSchemaRepairPolicy {

  static final String FEEDBACK =
      "Your previous submitOutline tool arguments violated the JSON schema. Re-emit the complete"
          + " plan exactly once as strict JSON matching the provided tool schema. Do not include"
          + " comments, markdown, unescaped quotes, trailing commas, or prose outside the tool"
          + " call. The tasks field must be a non-empty JSON array of executable task objects.";

  private OutlinePlannerSchemaRepairPolicy() {}

  static boolean isMalformedToolPayload(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof JsonProcessingException || current instanceof EmptyOutlinePayload)
        return true;
      String message = current.getMessage();
      if (message != null && message.contains("submitPlan/submitOutline args are not valid JSON"))
        return true;
    }
    return false;
  }

  static Plan requireExecutable(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty())
      throw new EmptyOutlinePayload();
    return plan;
  }

  private static final class EmptyOutlinePayload extends IllegalStateException {
    private EmptyOutlinePayload() {
      super("submitOutline schema requires a non-empty tasks array");
    }
  }

  static String stepId(String retryFeedback) {
    if (retryFeedback == null) return "planner.outline";
    return FEEDBACK.equals(retryFeedback)
        ? "planner.outline.schema-repair"
        : "planner.outline.validation-repair";
  }
}
