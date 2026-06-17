package com.navneet.executespec.tool;

final class ProjectCommandSchemas {

  static final String OPTIONAL_COMMAND_SCHEMA =
      """
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "Optional explicit command. If omitted, ExecuteSpec detects a sensible project command."
    },
    "plannerCommand": {
      "type": "string",
      "description": "Backward-compatible alias for command, used by older backend build prompts."
    },
    "timeoutSeconds": {
      "type": "integer",
      "description": "Optional timeout in seconds.",
      "minimum": 1,
      "maximum": 600
    }
  },
  "additionalProperties": false
}
""";

  private ProjectCommandSchemas() {}
}
