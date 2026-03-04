package com.navneet.agentteam.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FileSystemTool {

    @Value("${agent.workspace}")
    private String workspaceRoot;

    // Config and credential file names that agents must never read
    private static final Set<String> BLOCKED_FILE_NAMES = Set.of(
            "application.yml", "application.yaml", "application.properties",
            "application-dev.yml", "application-dev.yaml", "application-dev.properties",
            "application-prod.yml", "application-prod.yaml", "application-prod.properties",
            "application-gcp.yml", "application-gcp.yaml",
            "application-local.yml", "application-local.yaml",
            ".env", ".env.local", ".env.production",
            "bootstrap.yml", "bootstrap.yaml", "bootstrap.properties",
            "secrets.yml", "secrets.yaml", "credentials.json",
            "service-account.json", "key.json", "keyfile.json");

    // Directory names that agents must never enter
    private static final Set<String> BLOCKED_DIRECTORIES = Set.of(
            ".ssh", ".gnupg", ".aws", ".gcloud", ".config");

    /** Per-run isolated workspace: baseWorkspace/{runId}/ */
    private Path workspace(String runId) {
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
     * Checks whether a resolved path refers to a blocked credential or config file.
     * Returns an error ToolResult if blocked, null if allowed.
     */
    private ToolResult checkCredentialBlock(Path resolved) {
        String fileName = resolved.getFileName() != null
                ? resolved.getFileName().toString().toLowerCase()
                : "";

        if (BLOCKED_FILE_NAMES.contains(fileName)) {
            log.warn("CREDENTIAL ACCESS DENIED: {}", resolved);
            return ToolResult.err("ACCESS DENIED: Cannot read configuration/credential files from workspace");
        }

        // Walk path components to check for blocked directories
        for (Path segment : resolved) {
            if (BLOCKED_DIRECTORIES.contains(segment.toString().toLowerCase())) {
                log.warn("CREDENTIAL ACCESS DENIED (blocked dir): {}", resolved);
                return ToolResult.err("ACCESS DENIED: Cannot access restricted directory: " + segment);
            }
        }
        return null; // allowed
    }

    @Tool(description = "Read the full content of a file. Use this before writing or editing any file.")
    public String readFile(
            @ToolParam(description = "The pipeline run ID (injected from context, do not supply manually if possible, or use the runId)", required = true) String runId,
            @ToolParam(description = "The relative path to the file to read", required = true) String path) {
        try {
            Path resolved = safeResolve(runId, path);
            ToolResult blocked = checkCredentialBlock(resolved);
            if (blocked != null)
                return blocked.value();
            return Files.readString(resolved);
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
            Path target = safeResolve(runId, path);
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
            ToolResult blocked = checkCredentialBlock(resolved);
            if (blocked != null)
                return blocked.value();
            try (var stream = Files.list(resolved)) {
                Path ws = workspace(runId);
                String listing = stream
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

    @Tool(description = "Recursive grep to find patterns in files.")
    public String searchFiles(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Regex pattern to search for", required = true) String pattern,
            @ToolParam(description = "Directory to search in (default: '.')", required = true) String directory) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "grep", "-r", "--include=*", "-n", pattern,
                    safeResolve(runId, directory).toString());
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
