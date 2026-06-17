package com.navneet.executespec.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Alias over {@link BuildProjectTool} that accepts the public client field name {@code command}
 * while preserving the backend's historical {@code plannerCommand} field.
 */
final class BuildAliasTool implements Tool {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final String name;
  private final String description;
  private final BuildProjectTool delegate;

  BuildAliasTool(String name, String description, BuildProjectTool delegate) {
    this.name = name;
    this.description = description;
    this.delegate = delegate;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public String inputSchema() {
    return ProjectCommandSchemas.OPTIONAL_COMMAND_SCHEMA;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    ObjectNode forwarded = JSON.createObjectNode();
    if (args != null && args.hasNonNull("plannerCommand")) {
      forwarded.put("plannerCommand", args.get("plannerCommand").asText());
    } else if (args != null && args.hasNonNull("command")) {
      forwarded.put("plannerCommand", args.get("command").asText());
    }
    return delegate.execute(forwarded, ctx);
  }
}
