package com.navneet.executespec.standards.enforcement.lint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * E3.4 — wraps {@code npx eslint --format json}. Parses the JSON
 * output (an array of {@code {filePath, messages: [{ruleId, severity,
 * line, column, message}]}}) into normalised {@link LintViolation}s.
 *
 * <p>severity is ESLint's own scale (1=warn, 2=error). We pass both
 * through; the standard's own severity (BLOCKING / WARNING / ADVISORY)
 * decides whether the violation gates a run — that mapping happens in
 * E3.10 worker integration.
 */
@Component
@Slf4j
public class EslintAdapter implements LintAdapter {

    private final LintProcessRunner runner;
    private final ObjectMapper json;

    public EslintAdapter(LintProcessRunner runner, ObjectMapper json) {
        this.runner = runner;
        this.json = json;
    }

    @Override public String tool() { return "eslint"; }

    @Override
    public LintResult run(Path repoRoot, Path configPath, List<Path> changedFiles) {
        List<String> argv = new ArrayList<>(List.of("npx", "eslint", "--format", "json"));
        if (configPath != null) {
            argv.add("--config");
            argv.add(configPath.toString());
        }
        if (changedFiles != null && !changedFiles.isEmpty()) {
            for (Path p : changedFiles) argv.add(p.toString());
        } else {
            argv.add(".");
        }

        LintProcessRunner.Result res = runner.run(repoRoot, argv, 60);
        if (!res.executed()) {
            return new LintResult(tool(), LintResult.Status.UNAVAILABLE, List.of(),
                    res.unavailableReason());
        }
        // eslint exits non-zero when violations found — still parse the
        // stdout. Only treat empty-stdout-with-stderr as adapter failure.
        if (res.exitCode() != 0
                && (res.stdout() == null || res.stdout().isBlank())
                && res.stderr() != null && !res.stderr().isBlank()) {
            return new LintResult(tool(), LintResult.Status.FAILED, List.of(),
                    "eslint stderr: " + truncate(res.stderr()));
        }
        try {
            return new LintResult(tool(), LintResult.Status.SUCCESS,
                    parseJsonOutput(res.stdout()), null);
        } catch (Exception ex) {
            return new LintResult(tool(), LintResult.Status.FAILED, List.of(),
                    "could not parse eslint json: " + ex.getMessage());
        }
    }

    List<LintViolation> parseJsonOutput(String stdout) throws Exception {
        if (stdout == null || stdout.isBlank()) return List.of();
        JsonNode arr = json.readTree(stdout);
        if (!arr.isArray()) return List.of();
        List<LintViolation> out = new ArrayList<>();
        for (JsonNode fileNode : arr) {
            String filePath = fileNode.path("filePath").asText("");
            JsonNode msgs = fileNode.path("messages");
            if (!msgs.isArray()) continue;
            for (JsonNode msg : msgs) {
                out.add(new LintViolation(
                        filePath,
                        msg.path("line").asInt(0),
                        msg.path("column").asInt(0),
                        msg.path("ruleId").asText("(unknown)"),
                        msg.path("message").asText("")));
            }
        }
        return out;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
