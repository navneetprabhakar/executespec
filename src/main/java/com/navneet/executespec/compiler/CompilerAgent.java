package com.navneet.executespec.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.tool.FileSystemTool;
import com.navneet.executespec.tool.ShellTool;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the project build after all worker tasks complete, before the
 * ReviewerAgent sees the code.
 *
 * <p>
 * Flow per run:
 * <ol>
 * <li>Detect build tool (Maven / Gradle / npm) from workspace sentinel
 * files.</li>
 * <li>Run compile command.</li>
 * <li>If errors: send errors + affected source files to an LLM, apply
 * JSON-patch fixes.</li>
 * <li>Repeat up to {@code maxAttempts} times.</li>
 * <li>Always returns — never throws — so a stubborn compile failure does NOT
 * block the pipeline.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompilerAgent {

    private final ShellTool shellTool;
    private final FileSystemTool fsTool;
    private final LlmRouter llmRouter;
    private final SseBroadcaster broadcaster;
    private final CompilerProperties compilerProps;
    private final ObjectMapper mapper;
    private final StateStore stateStore;
    private final ObservationRegistry observationRegistry;

    @Value("classpath:prompts/compiler.txt")
    private Resource compilerPrompt;

    @Value("${agent.workspace}")
    private String workspaceRoot;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Entry point called by {@link com.navneet.executespec.harness.PipelineHarness}
     * after all workers finish.
     * Non-fatal: catches all exceptions and always returns a {@link CompileResult}.
     */
    public CompileResult runCompileAndFix(String runId) {
        Observation obs = Observation.createNotStarted("executespec.run.phase.compile", observationRegistry)
            .lowCardinalityKeyValue("run.id", runId != null ? runId : "unknown")
            .start();
        try {
            CompileResult result = doCompileAndFix(runId);
            obs.lowCardinalityKeyValue("outcome", result.passed() ? "passed" : "failed");
            obs.highCardinalityKeyValue("attempts", String.valueOf(result.attempts()));
            return result;
        } catch (Exception e) {
            obs.lowCardinalityKeyValue("outcome", "error");
            obs.error(e);
            log.error("CompilerAgent unexpected error for run {}: {}", runId, e.getMessage(), e);
            broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                    "⚠️ CompilerAgent internal error: " + e.getMessage() + " — proceeding anyway"));
            return CompileResult.failed(0, e.getMessage());
        } finally {
            obs.stop();
        }
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private CompileResult doCompileAndFix(String runId) throws IOException {
        if (!compilerProps.isEnabled())
            return CompileResult.skip();

        String buildTool = detectBuildTool(runId);
        if (buildTool == null) {
            log.info("CompilerAgent: no build tool found for run {} — skipping", runId);
            return CompileResult.skip();
        }

        String cmd = buildCommand(buildTool);
        int maxAttempts = compilerProps.getMaxAttempts();
        int timeout = compilerProps.getTimeoutSeconds();

        log.info("CompilerAgent: starting compile for run {} using {}", runId, buildTool);
        broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                "🔨 Compiling project (" + buildTool + ")..."));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String result = shellTool.run(runId, cmd, timeout);

            if (!result.startsWith("ERROR")) {
                broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                        "✅ Compile passed" + (attempt > 1 ? " on attempt " + attempt : "")));
                log.info("CompilerAgent: compile passed for run {} (attempt {})", runId, attempt);
                return CompileResult.passed(attempt);
            }

            String errors = result;
            log.warn("CompilerAgent: compile errors (attempt {}/{}) for run {}", attempt, maxAttempts, runId);
            broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                    "⚠️ Compile errors (attempt " + attempt + "/" + maxAttempts + "):\n"
                            + truncate(errors, 600)));

            if (attempt < maxAttempts) {
                broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                        "🔧 Analysing errors and applying LLM fixes..."));
                try {
                    applyLlmFixes(runId, errors, buildTool);
                } catch (Exception e) {
                    log.warn("CompilerAgent: LLM fix failed for run {} attempt {}: {}",
                            runId, attempt, e.getMessage());
                }
            }
        }

        String summary = "Compile still failing after " + maxAttempts + " attempt(s) — proceeding to reviewer";
        broadcaster.emit(runId, RunEvent.log(runId, "compiler", "⚠️ WARNING: " + summary));
        log.warn("CompilerAgent: {} for run {}", summary, runId);
        return CompileResult.failed(maxAttempts, summary);
    }

    private void applyLlmFixes(String runId, String errors, String buildTool) throws IOException {
        String system = compilerPrompt.getContentAsString(StandardCharsets.UTF_8);
        String context = extractRelevantFiles(runId, errors);
        String user = "Build tool: " + buildTool
                + "\n\nCOMPILE ERRORS:\n" + truncate(errors, 2000)
                + "\n\nRELEVANT SOURCE FILES:\n" + context
                + "\n\nOutput ONLY a JSON array: [{\"path\":\"...\",\"content\":\"...\"}]";

        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));

        LlmRouter.LlmResponse lr = llmRouter.callWithFallbackTracked(
                compilerProps.getProvider(), compilerProps.getModel(),
                prompt, runId, "compiler",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false);
        String resp = lr.response().getResult().getOutput().getText();

        // Track compiler token usage
        try {
            int inTok = lr.response().getMetadata().getUsage().getPromptTokens().intValue();
            int outTok = lr.response().getMetadata().getUsage().getCompletionTokens().intValue();
            stateStore.addTokensToRun(runId, inTok, outTok);
            stateStore.setAgentTokens(runId, "compiler", inTok, outTok, lr.provider(), lr.model());
        } catch (Exception ex) {
            log.warn("[Compiler] Could not extract token counts: {}", ex.getMessage());
        }

        applyJsonFixes(runId, resp);
    }

    private void applyJsonFixes(String runId, String llmResponse) {
        try {
            String cleaned = llmResponse.trim()
                    .replaceAll("(?s)^```json\\s*", "")
                    .replaceAll("(?s)```$", "")
                    .trim();
            int s = cleaned.indexOf('[');
            int e = cleaned.lastIndexOf(']') + 1;
            if (s < 0 || e <= s)
                return;

            JsonNode arr = mapper.readTree(cleaned.substring(s, e));
            if (!arr.isArray())
                return;

            int fixCount = 0;
            for (JsonNode fix : arr) {
                String path = fix.path("path").asText(null);
                String content = fix.path("content").asText(null);
                if (path != null && !path.isBlank() && content != null) {
                    fsTool.writeFile(runId, path, content);
                    fixCount++;
                    log.debug("CompilerAgent fixed: {}", path);
                }
            }
            if (fixCount > 0) {
                broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                        "  Applied " + fixCount + " file fix(es)"));
            }
        } catch (Exception e) {
            log.warn("CompilerAgent: could not parse LLM fix JSON: {}", e.getMessage());
        }
    }

    private String extractRelevantFiles(String runId, String errors) {
        Pattern p = Pattern.compile("([\\w/]+\\.(?:java|ts|js|py))");
        Matcher m = p.matcher(errors);
        var seen = new LinkedHashSet<String>();
        while (m.find() && seen.size() < 5)
            seen.add(m.group(1));
        var sb = new StringBuilder();
        for (String f : seen) {
            String r = fsTool.readFile(runId, f);
            if (!r.startsWith("ERROR")) {
                sb.append("=== ").append(f).append(" ===\n").append(r).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    String detectBuildTool(String runId) {
        Path ws = Path.of(workspaceRoot, runId);
        if (Files.exists(ws.resolve("pom.xml")))
            return "mvn";
        if (Files.exists(ws.resolve("build.gradle")))
            return "gradle";
        if (Files.exists(ws.resolve("build.gradle.kts")))
            return "gradle";
        if (Files.exists(ws.resolve("package.json")))
            return "npm";
        return null;
    }

    private String buildCommand(String tool) {
        return switch (tool) {
            case "mvn" -> "mvn compile -q 2>&1";
            case "gradle" -> "gradle compileJava -q 2>&1";
            case "npm" -> "npm run build 2>&1";
            default -> throw new IllegalArgumentException("Unknown build tool: " + tool);
        };
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
