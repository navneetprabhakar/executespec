package com.navneet.executespec.tool;

// Every tool call returns one of these — no exceptions thrown out of tools
public sealed interface ToolResult permits ToolResult.Ok, ToolResult.Err {

    record Ok(String output) implements ToolResult {}
    record Err(String error) implements ToolResult {}

    static ToolResult ok(String output) { return new Ok(output); }
    static ToolResult err(String error) { return new Err(error); }

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
