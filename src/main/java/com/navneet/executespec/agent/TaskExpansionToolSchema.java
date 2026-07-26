package com.navneet.executespec.agent;

final class TaskExpansionToolSchema {

  static final String SUBMIT_TASK_DETAIL =
      """
{
  "type": "object",
  "properties": {
    "taskId": {"type": "string"},
    "description": {"type": "string", "maxLength": 4000},
    "additionalTouchesFiles": {"type": "array", "maxItems": 24, "items": {"type": "string", "maxLength": 160}},
    "additionalHardConstraints": {"type": "array", "maxItems": 12, "items": {"type": "string", "maxLength": 360}}
  },
  "required": ["taskId", "description"]
}
""";

  private TaskExpansionToolSchema() {}
}
