package com.navneet.executespec.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Factory that creates Spring AI {@link ToolCallback} instances for the 9 worker tools.
 *
 * <p>Each callback defines a JSON Schema for OpenAI's native function calling and
 * delegates execution to the existing {@code WorkerAgent.dispatchTool()} via a
 * function reference, so ALL dispatch logic (truncation, error handling, alias mapping,
 * file-lock checks) remains in one place.
 *
 * <p>Usage: called per-iteration in {@code WorkerAgent.callModel()} when the provider
 * is OpenAI. The {@code runId} and {@code taskId} are captured in the closure so the
 * LLM never sees them.
 */
public class WorkerToolCallbackFactory {

    /**
     * Dispatcher function: (ParsedToolCall, runId, taskId) → result string.
     * Matches the signature of {@code WorkerAgent.dispatchTool()}.
     */
    @FunctionalInterface
    public interface ToolDispatcher {
        String dispatch(String name, String argsJson, String runId, String taskId);
    }

    /**
     * Creates the 9 ToolCallback wrappers bound to a specific run and task.
     *
     * @param runId      current pipeline run ID
     * @param taskId     current task ID
     * @param dispatcher delegates to WorkerAgent.dispatchTool()
     */
    public static List<ToolCallback> create(String runId, String taskId, ToolDispatcher dispatcher) {
        return List.of(
                tool("readFile",
                        "Read the full content of a file. Use this before writing or editing any file.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "Relative path to the file"
                            }
                          },
                          "required": ["path"],
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("writeFile",
                        "Create or fully overwrite a file. ALWAYS write the complete file — never partial content. Claims a file lock automatically.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "Relative path of the file to create or overwrite"
                            },
                            "content": {
                              "type": "string",
                              "description": "Complete content of the file"
                            }
                          },
                          "required": ["path", "content"],
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("editFile",
                        "Replace an exact string in an existing file. oldStr must match exactly (whitespace included). Safer than full rewrite for small changes.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "Relative path to the file"
                            },
                            "oldStr": {
                              "type": "string",
                              "description": "Exact text to replace (must match including whitespace)"
                            },
                            "newStr": {
                              "type": "string",
                              "description": "Replacement text"
                            }
                          },
                          "required": ["path", "oldStr", "newStr"],
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("listFiles",
                        "List directory contents. Returns file names and types.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "Relative path to the directory (default: workspace root)"
                            }
                          },
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("searchFiles",
                        "Recursive grep. Finds pattern occurrences across files in the workspace.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "pattern": {
                              "type": "string",
                              "description": "Text or regex pattern to search for"
                            },
                            "directory": {
                              "type": "string",
                              "description": "Directory to search in (default: workspace root)"
                            }
                          },
                          "required": ["pattern"],
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("run",
                        "Run a shell command in the workspace directory. Allowed: git, ls, cat, grep, find, echo, mkdir, cp, mv, npm, mvn, gradle, python, etc. Use this for building (mvn compile, npm run build) and testing (mvn test, npm test).",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "command": {
                              "type": "string",
                              "description": "Shell command to execute"
                            },
                            "timeoutSeconds": {
                              "type": "integer",
                              "description": "Timeout in seconds (default: 120, max: 300). Use 300 for builds."
                            }
                          },
                          "required": ["command"],
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("status",
                        "Show git working tree status (staged, unstaged, untracked files).",
                        """
                        {
                          "type": "object",
                          "properties": {},
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("diff",
                        "Show diff of changes since last commit.",
                        """
                        {
                          "type": "object",
                          "properties": {},
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher),

                tool("commit",
                        "Stage and commit all current changes. Commit early and often — at least once per file written.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "message": {
                              "type": "string",
                              "description": "Commit message in format: feat(task_id): description"
                            }
                          },
                          "required": ["message"],
                          "additionalProperties": false
                        }
                        """,
                        runId, taskId, dispatcher)
        );
    }

    /**
     * Creates a single ToolCallback that delegates execution to the dispatcher.
     */
    private static ToolCallback tool(String name, String description, String inputSchema,
                                     String runId, String taskId, ToolDispatcher dispatcher) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return dispatcher.dispatch(name, toolInput, runId, taskId);
            }
        };
    }
}
