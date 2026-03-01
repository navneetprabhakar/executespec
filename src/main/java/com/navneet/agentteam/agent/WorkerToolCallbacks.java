package com.navneet.agentteam.agent;

import com.navneet.agentteam.state.InboxService;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.tool.FileLockTool;
import com.navneet.agentteam.tool.FileSystemTool;
import com.navneet.agentteam.tool.GitTool;
import com.navneet.agentteam.tool.ShellTool;
import com.navneet.agentteam.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds Spring AI {@link ToolCallback} definitions for all tools available to the WorkerAgent.
 *
 * <p>Tools are defined as proper JSON-schema entries sent to the LLM API (native tool use),
 * replacing the XML {@code <tool_call>} format used previously (WP-5).
 *
 * <p>Callbacks are created per-task invocation so they close over the runtime
 * {@code runId} and {@code taskId} values.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerToolCallbacks {

    // ── Input parameter records ──────────────────────────────────────────────

    /** read_file parameters */
    public record ReadFileInput(String path) {}

    /** write_file parameters */
    public record WriteFileInput(String path, String content) {}

    /** edit_file parameters */
    public record EditFileInput(String path, String old_str, String new_str) {}

    /** list_directory parameters */
    public record ListDirectoryInput(String path) {}

    /** search_files parameters */
    public record SearchFilesInput(String pattern, String directory) {}

    /** run_command / run_build / run_tests parameters */
    public record CommandInput(String command) {}

    /** git_commit parameters */
    public record GitCommitInput(String message) {}

    /** No-argument tools */
    public record EmptyInput() {}

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates the full set of tool callbacks for one worker task invocation.
     *
     * @param runId  the pipeline run ID (workspace root)
     * @param taskId the current agent task ID (for file-lock ownership)
     */
    public List<ToolCallback> createCallbacks(
            String runId, String taskId,
            FileSystemTool fsTool, ShellTool shellTool, GitTool gitTool,
            FileLockTool lockTool, InboxService inboxService, StateStore stateStore) {

        return List.of(

            FunctionToolCallback.<ReadFileInput, String>builder(
                    "read_file",
                    input -> fsTool.readFile(runId, input.path()).value())
                .description("Read the full content of a file in the workspace. " +
                    "Always read a file before writing or editing it.")
                .inputType(ReadFileInput.class)
                .build(),

            FunctionToolCallback.<WriteFileInput, String>builder(
                    "write_file",
                    input -> {
                        boolean claimed = lockTool.claim(input.path(), runId, taskId);
                        if (!claimed) {
                            String owner = lockTool.getOwner(input.path()).orElse("unknown");
                            inboxService.sendFileLockConflict(runId,
                                "worker-" + taskId, "worker-" + owner, input.path());
                            stateStore.logMessage(runId, "worker-" + taskId, "worker-" + owner,
                                "COORDINATION", "Conflict on " + input.path() +
                                " — owner: " + owner, 0);
                            return ToolResult.err("File " + input.path() +
                                " is locked by worker-" + owner +
                                ". Coordination message sent. Choose a different file or wait.").value();
                        }
                        return fsTool.writeFile(runId, input.path(), input.content()).value();
                    })
                .description("Create or fully overwrite a file in the workspace. " +
                    "Claims a file lock automatically. Always write the complete file content.")
                .inputType(WriteFileInput.class)
                .build(),

            FunctionToolCallback.<EditFileInput, String>builder(
                    "edit_file",
                    input -> {
                        boolean claimed = lockTool.claim(input.path(), runId, taskId);
                        if (!claimed) return ToolResult.err("File locked: " + input.path()).value();
                        return fsTool.editFile(runId, input.path(), input.old_str(), input.new_str()).value();
                    })
                .description("Replace an exact string in a file. " +
                    "old_str must match exactly (including whitespace). " +
                    "Use for small targeted edits instead of full rewrites.")
                .inputType(EditFileInput.class)
                .build(),

            FunctionToolCallback.<ListDirectoryInput, String>builder(
                    "list_files",
                    input -> fsTool.listDirectory(runId,
                        input.path() != null ? input.path() : ".").value())
                .description("List the contents of a directory in the workspace. " +
                    "Use \".\" for the workspace root.")
                .inputType(ListDirectoryInput.class)
                .build(),

            FunctionToolCallback.<SearchFilesInput, String>builder(
                    "search_files",
                    input -> fsTool.searchFiles(runId, input.pattern(),
                        input.directory() != null ? input.directory() : ".").value())
                .description("Recursively search files for a pattern. " +
                    "pattern: search string; directory: starting path (default \".\").")
                .inputType(SearchFilesInput.class)
                .build(),

            FunctionToolCallback.<CommandInput, String>builder(
                    "run_command",
                    input -> shellTool.run(runId, input.command(), 120).value())
                .description("Run a shell command in the workspace with a 2-minute timeout. " +
                    "Allowed: git, ls, cat, grep, find, echo, mkdir, cp, mv, curl.")
                .inputType(CommandInput.class)
                .build(),

            FunctionToolCallback.<CommandInput, String>builder(
                    "run_build",
                    input -> shellTool.run(runId, input.command(), 300).value())
                .description("Run a build command with a 5-minute timeout. " +
                    "Allowed: mvn, gradle, gradlew, npm, npx, python, pytest, pip. " +
                    "Run after writing code to verify it compiles.")
                .inputType(CommandInput.class)
                .build(),

            FunctionToolCallback.<CommandInput, String>builder(
                    "run_tests",
                    input -> shellTool.run(runId, input.command(), 300).value())
                .description("Run the test suite with a 5-minute timeout. " +
                    "Same allowed commands as run_build. " +
                    "Run before reporting task as complete.")
                .inputType(CommandInput.class)
                .build(),

            FunctionToolCallback.<EmptyInput, String>builder(
                    "git_status",
                    input -> gitTool.status(runId).value())
                .description("Show the current git working tree status.")
                .inputType(EmptyInput.class)
                .build(),

            FunctionToolCallback.<EmptyInput, String>builder(
                    "git_diff",
                    input -> gitTool.diff(runId).value())
                .description("Show the diff of all changes since the last commit.")
                .inputType(EmptyInput.class)
                .build(),

            FunctionToolCallback.<GitCommitInput, String>builder(
                    "git_commit",
                    input -> gitTool.commit(runId,
                        input.message() != null ? input.message() : "wip").value())
                .description("Commit all current changes with the given message. " +
                    "Commit format: \"feat(task_id): description\" or \"fix(task_id): description\".")
                .inputType(GitCommitInput.class)
                .build()
        );
    }
}
