package com.navneet.agentteam.tool;

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
        "service-account.json", "key.json", "keyfile.json"
    );

    // Directory names that agents must never enter
    private static final Set<String> BLOCKED_DIRECTORIES = Set.of(
        ".ssh", ".gnupg", ".aws", ".gcloud", ".config"
    );

    /** Per-run isolated workspace: baseWorkspace/{runId}/ */
    private Path workspace(String runId) {
        return Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
    }

    /** Guard: all paths must stay inside the run's workspace directory. */
    private Path safeResolve(String runId, String relativePath) {
        Path ws      = workspace(runId);
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

    public ToolResult readFile(String runId, String path) {
        try {
            Path resolved = safeResolve(runId, path);
            ToolResult blocked = checkCredentialBlock(resolved);
            if (blocked != null) return blocked;
            return ToolResult.ok(Files.readString(resolved));
        } catch (SecurityException e) {
            return ToolResult.err("ACCESS DENIED: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.err("Cannot read " + path + ": " + e.getMessage());
        }
    }

    public ToolResult writeFile(String runId, String path, String content) {
        try {
            Path target = safeResolve(runId, path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return ToolResult.ok("Written: " + path + " (" + content.length() + " chars)");
        } catch (SecurityException e) {
            return ToolResult.err("ACCESS DENIED: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.err("Cannot write " + path + ": " + e.getMessage());
        }
    }

    public ToolResult editFile(String runId, String path, String oldStr, String newStr) {
        try {
            Path target  = safeResolve(runId, path);
            String current = Files.readString(target);
            if (!current.contains(oldStr)) {
                return ToolResult.err("String not found in " + path + ". No edit made.");
            }
            Files.writeString(target, current.replace(oldStr, newStr));
            return ToolResult.ok("Edited: " + path);
        } catch (SecurityException e) {
            return ToolResult.err("ACCESS DENIED: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.err("Edit failed on " + path + ": " + e.getMessage());
        }
    }

    public ToolResult listDirectory(String runId, String path) {
        try {
            Path resolved = safeResolve(runId, path);
            ToolResult blocked = checkCredentialBlock(resolved);
            if (blocked != null) return blocked;
            try (var stream = Files.list(resolved)) {
                Path ws = workspace(runId);
                String listing = stream
                    .map(p -> (Files.isDirectory(p) ? "[DIR] " : "      ") + ws.relativize(p))
                    .collect(Collectors.joining("\n"));
                return ToolResult.ok(listing.isEmpty() ? "(empty)" : listing);
            }
        } catch (SecurityException e) {
            return ToolResult.err("ACCESS DENIED: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.err("Cannot list " + path + ": " + e.getMessage());
        }
    }

    public ToolResult searchFiles(String runId, String pattern, String directory) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "grep", "-r", "--include=*", "-n", pattern,
                safeResolve(runId, directory).toString());
            pb.directory(workspace(runId).toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return ToolResult.ok(output.isBlank() ? "(no matches)" : output);
        } catch (Exception e) {
            return ToolResult.err("Search failed: " + e.getMessage());
        }
    }
}
