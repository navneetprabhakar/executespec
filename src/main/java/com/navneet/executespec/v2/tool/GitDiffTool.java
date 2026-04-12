package com.navneet.executespec.v2.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * V2 gitDiff tool. Returns the working-tree diff (unstaged + staged) since HEAD.
 *
 * <p>Output truncated to {@value #MAX_DIFF_CHARS} chars to keep token cost bounded;
 * if a diff is huge, the model can listFiles + readFile specific files for detail.
 */
@Component
@Slf4j
public class GitDiffTool implements Tool {

    static final int MAX_DIFF_CHARS = 3_000;

    @Override
    public String name() {
        return "gitDiff";
    }

    @Override
    public String description() {
        return "Shows the diff of all changes (staged + unstaged) since the last commit. "
                + "Output is truncated at ~3KB; for large diffs, inspect specific files via readFile.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args, ExecutionContext ctx) {
        try {
            GitInvoker.GitResult r = GitInvoker.run(ctx.workspaceRoot(), "diff", "HEAD");
            if (r.exitCode() != 0) {
                return ToolResult.err(ToolResult.ErrorKind.IO_ERROR,
                        "git diff failed (exit=" + r.exitCode() + "): " + r.output());
            }
            String out = r.output();
            if (out.isBlank()) return ToolResult.ok("(no changes)");
            if (out.length() > MAX_DIFF_CHARS) {
                int omitted = out.length() - MAX_DIFF_CHARS;
                out = out.substring(0, MAX_DIFF_CHARS)
                        + "\n...[" + omitted + " chars omitted from diff]";
            }
            return ToolResult.ok(out);
        } catch (IOException ioe) {
            log.warn("gitDiff error", ioe);
            return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
        }
    }
}
