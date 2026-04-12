package com.navneet.executespec.v2.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.v2.tool.ExecutionContext;
import com.navneet.executespec.v2.tool.Tool;
import com.navneet.executespec.v2.tool.ToolRegistryV2;
import com.navneet.executespec.v2.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 Phase 1 verification endpoint.
 *
 * <p>Creates a temporary workspace, exercises every {@link Tool} registered in
 * {@link ToolRegistryV2}, and reports per-tool pass/fail. This is the equivalent
 * of Phase 0's {@code /native-tools/all} but for the tool layer instead of LLM
 * native tool calling.
 *
 * <p>Removed once V2 has integration tests covering the same surface.
 */
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class ToolRegistryTestController {

    private final ToolRegistryV2 registry;
    private final ObjectMapper objectMapper;

    @GetMapping("/tool-registry")
    public Map<String, Object> testToolRegistry() throws IOException {
        // Build a throwaway workspace populated with one editable file.
        Path workspace = Files.createTempDirectory("v2-tool-test-");
        try {
            Files.writeString(workspace.resolve("hello.txt"), "line 1\nline 2\nline 3\nNEEDLE here\nline 5\n");
            // Initialize git repo so the git tools have something to talk to.
            runGit(workspace, "init", "-q");
            runGit(workspace, "-c", "user.email=test@v2", "-c", "user.name=v2-test", "add", "-A");
            runGit(workspace, "-c", "user.email=test@v2", "-c", "user.name=v2-test", "commit", "-q", "-m", "init");

            ExecutionContext ctx = new ExecutionContext("test-run", "test-task", workspace.toRealPath());
            List<Map<String, Object>> results = new ArrayList<>();

            results.add(invoke("listFiles", "{\"path\":\".\"}", ctx));
            results.add(invoke("readFile", "{\"path\":\"hello.txt\"}", ctx));
            results.add(invoke("readFile", "{\"path\":\"hello.txt\",\"startLine\":2,\"endLine\":3}", ctx));
            results.add(invoke("searchFiles", "{\"pattern\":\"NEEDLE\"}", ctx));
            results.add(invoke("writeFile", "{\"path\":\"new.txt\",\"content\":\"created by v2\"}", ctx));
            results.add(invoke("editFile", "{\"path\":\"hello.txt\",\"edits\":[{\"oldStr\":\"NEEDLE here\",\"newStr\":\"REPLACED\"}]}", ctx));
            results.add(invoke("shell", "{\"command\":\"echo v2-shell-ok\"}", ctx));
            results.add(invoke("gitStatus", "{}", ctx));
            results.add(invoke("gitDiff", "{}", ctx));
            results.add(invoke("gitCommit", "{\"message\":\"v2-test commit\"}", ctx));
            results.add(invoke("buildProject", "{\"plannerCommand\":\"echo v2-build-ok && true\"}", ctx));

            // Negative test: writeFile rejects existing file
            results.add(invoke("writeFile", "{\"path\":\"hello.txt\",\"content\":\"X\"}", ctx,
                    "expect-error: writeFile on existing file"));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("workspace", workspace.toString());
            out.put("registeredTools", registry.toolNames());
            out.put("totalTested", results.size());
            out.put("passed", results.stream().filter(r -> Boolean.TRUE.equals(r.get("ok"))).count());
            out.put("failed", results.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).count());
            out.put("results", results);
            return out;
        } finally {
            // Best-effort cleanup; not critical if it fails.
            deleteTree(workspace);
        }
    }

    private Map<String, Object> invoke(String toolName, String argsJson, ExecutionContext ctx) {
        return invoke(toolName, argsJson, ctx, null);
    }

    private Map<String, Object> invoke(String toolName, String argsJson, ExecutionContext ctx, String label) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tool", toolName);
        r.put("args", argsJson);
        if (label != null) r.put("label", label);
        Tool tool = registry.find(toolName);
        if (tool == null) {
            r.put("ok", false);
            r.put("error", "tool not registered");
            return r;
        }
        try {
            ToolResult result = tool.execute(objectMapper.readTree(argsJson), ctx);
            switch (result) {
                case ToolResult.Ok ok -> {
                    boolean expectError = label != null && label.startsWith("expect-error:");
                    r.put("ok", !expectError);
                    String content = ok.content();
                    r.put("content", content.length() > 200 ? content.substring(0, 200) + "..." : content);
                }
                case ToolResult.Err err -> {
                    boolean expectError = label != null && label.startsWith("expect-error:");
                    r.put("ok", expectError);
                    r.put("kind", err.kind().name());
                    r.put("message", err.message());
                }
            }
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return r;
    }

    private static void runGit(Path workspace, String... args) throws IOException {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.waitFor();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("git init interrupted", ie);
        }
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.exists(root)) return;
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        } catch (IOException ignored) {}
    }
}
