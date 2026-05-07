package com.navneet.executespec.tool;

// Every tool call returns one of these — no exceptions thrown out of tools
public sealed interface GitToolResult permits GitToolResult.Ok, GitToolResult.Err {

  record Ok(String output) implements GitToolResult {}

  record Err(String error) implements GitToolResult {}

  static GitToolResult ok(String output) {
    return new Ok(output);
  }

  static GitToolResult err(String error) {
    return new Err(error);
  }

  default boolean isOk() {
    return this instanceof Ok;
  }

  default String value() {
    return switch (this) {
      case Ok o -> o.output();
      case Err e -> "ERROR: " + e.error();
    };
  }
}
