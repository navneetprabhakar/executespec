package com.navneet.executespec.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FileSystemTool {

    @Value("${agent.workspace}")
    private String workspaceRoot;

    @Autowired(required = false)
    private FileLockTool lockTool;

    @Autowired(required = false)
    private com.navneet.executespec.security.CodeAbuseScanner codeAbuseScanner;

    // File names that trigger a WARN log when written (potential secret leakage).
    // NOT blocked — agents legitimately write these for generated projects.
    // Security boundary is safeResolve() which sandboxes all paths to the workspace.
    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".env", ".env.local", ".env.production",
            "credentials.json", "service-account.json",
            "key.json", "keyfile.json", "secrets.yml", "secrets.yaml");

    // Directory names that are always hidden from agent file listings.
    // "worktrees" holds sibling task worktrees; "target" / "build" / "node_modules"
    // are build artifacts — agents don't need to see them and seeing them causes confusion.
    private static final Set<String> HIDDEN_DIRECTORIES = Set.of(
            "worktrees", "target", "build", "node_modules", ".git");

    /**
     * Returns the working directory for this task.
     * If a worktree override is active on this thread (set by WorkspaceContext),
     * returns that path; otherwise falls back to the shared run workspace root.
     */
    private Path workspace(String runId) {
        String override = WorkspaceContext.getWorkDir(runId);
        if (override != null) return Path.of(override).toAbsolutePath().normalize();
        return Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
    }

    /** Guard: all paths must stay inside the run's workspace directory. */
    private Path safeResolve(String runId, String relativePath) {
        Path ws = workspace(runId);
        Path resolved = ws.resolve(relativePath).normalize();
        if (!resolved.startsWith(ws)) {
            throw new SecurityException("Path escape attempt: " + relativePath);
        }
        return resolved;
    }

    /**
     * Checks if the current task is allowed to write to the given path.
     * Returns an error message if blocked by another task's file lock, null if OK.
     */
    private String checkFileLock(String path) {
        String currentTask = WorkspaceContext.getTaskId();
        if (currentTask != null && lockTool != null) {
            Optional<String> owner = lockTool.getOwner(path);
            if (owner.isPresent() && !owner.get().equals(currentTask)) {
                return "WRITE BLOCKED: File '" + path + "' is owned by task '" + owner.get() + "'. "
                    + "This task (" + currentTask + ") cannot modify it.";
            }
        }
        return null;
    }


    @Tool(description = "Read the full content of a file. Use this before writing or editing any file.")
    public String readFile(
            @ToolParam(description = "The pipeline run ID (injected from context, do not supply manually if possible, or use the runId)", required = true) String runId,
            @ToolParam(description = "The relative path to the file to read", required = true) String path) {
        try {
            Path resolved = safeResolve(runId, path);
            return Files.readString(resolved);
        } catch (SecurityException e) {
            return "ACCESS DENIED: " + e.getMessage();
        } catch (IOException e) {
            return "Cannot read " + path + ": " + e.getMessage();
        }
    }

    /**
     * Read a range of lines from a file. Returns lines with line numbers.
     * Used by WorkerAgent when the model requests a specific section of a large file.
     */
    public String readFileRange(String runId, String path, int startLine, int endLine) {
        try {
            Path resolved = safeResolve(runId, path);
            List<String> allLines = Files.readAllLines(resolved);
            int totalLines = allLines.size();

            // Clamp to valid range
            int start = Math.max(1, startLine);
            int end = Math.min(totalLines, endLine);
            if (start > totalLines) {
                return "File has only " + totalLines + " lines (requested start=" + startLine + ")";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[Lines ").append(start).append("-").append(end)
              .append(" of ").append(totalLines).append("]\n");
            for (int i = start - 1; i < end; i++) {
                sb.append(String.format("%4d | %s%n", i + 1, allLines.get(i)));
            }
            return sb.toString();
        } catch (SecurityException e) {
            return "ACCESS DENIED: " + e.getMessage();
        } catch (IOException e) {
            return "Cannot read " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Create or fully overwrite a file. ALWAYS write the complete file — never partial content.")
    public String writeFile(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Path of the file to write", required = true) String path,
            @ToolParam(description = "Complete content of the file", required = true) String content) {
        try {
            String lockBlock = checkFileLock(path);
            if (lockBlock != null) return lockBlock;
            Path target = safeResolve(runId, path);
            String fileName = target.getFileName() != null
                    ? target.getFileName().toString().toLowerCase() : "";
            if (SENSITIVE_FILE_NAMES.contains(fileName)) {
                log.warn("[Security] Agent writing sensitive file: {} (runId={})", path, runId);
            }
            // Scan for malicious code patterns (crypto mining, reverse shells, etc.)
            // BLOCKING: flagged content is rejected — not written to disk.
            if (codeAbuseScanner != null) {
                var scanResult = codeAbuseScanner.scan(path, content);
                if (scanResult.flagged()) {
                    log.warn("[Security] WRITE BLOCKED — code abuse detected in {} (runId={}, category={}, detail={})",
                            path, runId, scanResult.category(), scanResult.detail());
                    return "WRITE BLOCKED: File '" + path + "' contains " + scanResult.category()
                            + " patterns and cannot be written. Remove the malicious content and retry.";
                }
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return "Written: " + path + " (" + content.length() + " chars)";
        } catch (SecurityException e) {
            return "ACCESS DENIED: " + e.getMessage();
        } catch (IOException e) {
            return "Cannot write " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Replace an exact string in an existing file. oldStr must match exactly.")
    public String editFile(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Path to the file", required = true) String path,
            @ToolParam(description = "Exact string to replace", required = true) String oldStr,
            @ToolParam(description = "New string to insert", required = true) String newStr) {
        try {
            String lockBlock = checkFileLock(path);
            if (lockBlock != null) return lockBlock;
            Path target = safeResolve(runId, path);
            String current = Files.readString(target);
            if (!current.contains(oldStr)) {
                return "String not found in " + path + ". No edit made.";
            }
            Files.writeString(target, current.replace(oldStr, newStr));
            return "Edited: " + path;
        } catch (SecurityException e) {
            return "ACCESS DENIED: " + e.getMessage();
        } catch (IOException e) {
            return "Edit failed on " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "List directory contents.")
    public String listFiles(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Path of the directory to list (usually '.')", required = true) String path) {
        try {
            Path resolved = safeResolve(runId, path);
            try (var stream = Files.list(resolved)) {
                Path ws = workspace(runId);
                String listing = stream
                        .filter(p -> {
                            String name = p.getFileName() != null ? p.getFileName().toString() : "";
                            // Hide worktrees/ (sibling task isolation dirs) and build artifacts
                            // so agents see a clean project structure.
                            return !HIDDEN_DIRECTORIES.contains(name);
                        })
                        .map(p -> (Files.isDirectory(p) ? "[DIR] " : "      ") + ws.relativize(p))
                        .collect(Collectors.joining("\n"));
                return listing.isEmpty() ? "(empty)" : listing;
            }
        } catch (SecurityException e) {
            return "ACCESS DENIED: " + e.getMessage();
        } catch (IOException e) {
            return "Cannot list " + path + ": " + e.getMessage();
        }
    }

    /**
     * Recursive workspace listing for callers (like the accuracy assessor) that need
     * to see the full tree, not just top-level entries. Excludes build artifacts,
     * vendor dirs, and dot-files. Caps total lines to keep the output bounded.
     */
    public String listFilesRecursive(String runId, int maxEntries) {
        try {
            Path ws = workspace(runId);
            if (!Files.isDirectory(ws)) return "(workspace not found)";
            java.util.Set<String> skip = java.util.Set.of(
                    ".git", ".agent-context", "node_modules", "target", "build",
                    "dist", ".gradle", ".idea", ".vscode", "__pycache__", "venv", ".venv",
                    "worktrees");
            StringBuilder sb = new StringBuilder();
            int[] count = {0};
            try (var stream = Files.walk(ws)) {
                stream.filter(p -> !p.equals(ws))
                      .filter(p -> {
                          for (Path part : ws.relativize(p)) {
                              String n = part.toString();
                              if (skip.contains(n)) return false;
                              if (n.startsWith(".") && !".env.example".equals(n)) return false;
                          }
                          return true;
                      })
                      .sorted()
                      .forEach(p -> {
                          if (count[0] >= maxEntries) return;
                          sb.append(Files.isDirectory(p) ? "[DIR] " : "      ")
                            .append(ws.relativize(p).toString())
                            .append('\n');
                          count[0]++;
                      });
            }
            if (sb.length() == 0) return "(empty)";
            if (count[0] >= maxEntries) sb.append("...(truncated at ").append(maxEntries).append(" entries)\n");
            return sb.toString();
        } catch (IOException e) {
            return "Cannot walk workspace: " + e.getMessage();
        }
    }

    @Tool(description = "Recursive grep to find patterns in files.")
    public String searchFiles(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Regex pattern to search for", required = true) String pattern,
            @ToolParam(description = "Directory to search in (default: '.')", required = true) String directory) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "grep", "-r", "--include=*", "-n",
                    "--exclude-dir=worktrees", "--exclude-dir=target",
                    "--exclude-dir=build", "--exclude-dir=node_modules", "--exclude-dir=.git",
                    pattern, safeResolve(runId, directory).toString());
            pb.directory(workspace(runId).toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return output.isBlank() ? "(no matches)" : output;
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }
}
