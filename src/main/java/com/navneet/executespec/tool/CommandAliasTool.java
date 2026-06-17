package com.navneet.executespec.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thin named alias over another command-style tool. Used when the model/client vocabulary needs
 * multiple natural names but execution must stay on the same hardened implementation.
 */
final class CommandAliasTool implements Tool {

  private final String name;
  private final String description;
  private final Tool delegate;

  CommandAliasTool(String name, String description, Tool delegate) {
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
    return delegate.inputSchema();
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    return delegate.execute(args, ctx);
  }
}
