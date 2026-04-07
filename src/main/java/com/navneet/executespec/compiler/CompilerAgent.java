package com.navneet.executespec.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
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
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
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
    private final ModelResolver modelResolver;
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
                            + truncate(errors, maxErrorCharsForSse(errors))));

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

    /** Max chars of compile error output to include in LLM prompt / SSE events. */
    private static final int MAX_ERROR_CHARS = 2000;

    /** Max files to include in a single compiler LLM pass. Remaining files fixed in subsequent retry passes. */
    private static final int MAX_COMPILER_FILES = 10;

    private void applyLlmFixes(String runId, String errors, String buildTool) throws IOException {
        String system = compilerPrompt.getContentAsString(StandardCharsets.UTF_8);
        // Scope enforcement: if many files have errors, prioritize foundational files
        // (models/entities first) and limit to MAX_COMPILER_FILES per pass.
        String scopedErrors = scopeErrors(errors, MAX_COMPILER_FILES);
        String context = extractRelevantFiles(runId, scopedErrors);
        String user = "Build tool: " + buildTool
                + "\n\nCOMPILE ERRORS:\n" + truncate(scopedErrors, MAX_ERROR_CHARS)
                + "\n\nRELEVANT SOURCE FILES:\n" + context
                + "\n\nOutput ONLY a JSON array: [{\"path\":\"...\",\"content\":\"...\"}]";

        ResolvedModel resolved = modelResolver.resolve("CompilerAgent",
                stateStore.findRun(runId).map(r -> r.getAiProviderEnum())
                        .orElse(com.navneet.executespec.router.LlmProvider.ANTHROPIC));
        AnthropicCacheOptions cacheOpts = AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                .build();
        AnthropicChatOptions opts = AnthropicChatOptions.builder()
                .temperature(0.0)
                .model(resolved.getModel())
                .cacheOptions(cacheOpts)
                .build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), opts);

        LlmRouter.LlmResponse lr = llmRouter.callWithFallbackTracked(
                resolved.providerName(), resolved.getModel(),
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

    /**
     * Scope compile errors to at most {@code maxFiles} unique files, prioritized by
     * dependency order (models/entities first, tests last). This prevents the LLM from
     * trying to fix 30+ files in one pass, producing a massive truncated response.
     * Remaining files are fixed in subsequent retry passes once foundational fixes resolve
     * cascading errors.
     */
    private String scopeErrors(String errors, int maxFiles) {
        Pattern filePattern = Pattern.compile("([\\w/.-]+\\.(?:java|ts|js|tsx|jsx|py|go|rs))");
        Matcher m = filePattern.matcher(errors);
        var allFiles = new LinkedHashSet<String>();
        while (m.find()) allFiles.add(m.group(1));

        if (allFiles.size() <= maxFiles) return errors;

        log.info("CompilerAgent: {} files with errors — scoping to {} (priority order)", allFiles.size(), maxFiles);

        java.util.Comparator<String> priority = java.util.Comparator.comparingInt(
                CompilerAgent::filePriority);

        var prioritizedFiles = allFiles.stream()
                .sorted(priority)
                .limit(maxFiles)
                .collect(java.util.stream.Collectors.toSet());

        // Filter error lines to only include lines mentioning prioritized files
        StringBuilder scoped = new StringBuilder();
        for (String line : errors.split("\n")) {
            boolean include = prioritizedFiles.stream().anyMatch(line::contains);
            // Also include lines that don't mention any file (general error context)
            if (include || allFiles.stream().noneMatch(line::contains)) {
                scoped.append(line).append("\n");
            }
        }
        return scoped.toString();
    }

    /**
     * Assigns a priority rank to a file path for compiler fix ordering.
     * Lower numbers = higher priority (fixed first to resolve cascading errors).
     *
     * 0 = model/entity/dto (foundational types)
     * 1 = repository/repo (data access)
     * 2 = service (business logic)
     * 3 = controller/resource (API layer)
     * 4 = config (configuration)
     * 5 = test (test files)
     * 6 = other (everything else)
     */
    private static int filePriority(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("/model/") || lower.contains("/entity/") || lower.contains("/dto/")) return 0;
        if (lower.contains("/repository/") || lower.contains("/repo/")) return 1;
        if (lower.contains("/service/")) return 2;
        if (lower.contains("/controller/") || lower.contains("/resource/")) return 3;
        if (lower.contains("/config/")) return 4;
        if (lower.contains("/test/") || lower.contains("Test.")) return 5;
        return 6;
    }

    private String extractRelevantFiles(String runId, String errors) {
        Pattern p = Pattern.compile("([\\w/]+\\.(?:java|ts|js|py))");
        Matcher m = p.matcher(errors);
        var seen = new LinkedHashSet<String>();
        while (m.find() && seen.size() < MAX_COMPILER_FILES)
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

    /**
     * Dynamic SSE error truncation limit based on how many files have errors.
     * Few files → show more context per file. Many files → keep it brief.
     */
    private int maxErrorCharsForSse(String errors) {
        int fileCount = countErrorFiles(errors);
        if (fileCount <= 3) return 2000;
        if (fileCount <= 6) return 1200;
        return 800;
    }

    /** Counts unique source file paths mentioned in compile error output. */
    private int countErrorFiles(String errors) {
        Pattern filePattern = Pattern.compile("([\\w/.-]+\\.(?:java|ts|js|tsx|jsx|py|go|rs))");
        Matcher m = filePattern.matcher(errors);
        var files = new java.util.HashSet<String>();
        while (m.find()) files.add(m.group(1));
        return files.size();
    }

    // ── Quick syntax check (per-tier, no LLM fix) ─────────────────────────────

    /**
     * Quick compile-and-fix pass — runs up to 2 attempts with LLM fix between them.
     * Used by SequentialTaskExecutor after each tier merges to catch and resolve
     * syntax errors before the next tier starts.
     *
     * @return null if check passed or was skipped; error summary string if still failing after 2 attempts
     */
    public String quickCompileAndFix(String runId) {
        if (!compilerProps.isEnabled()) return null;

        String buildTool = detectBuildTool(runId);
        if (buildTool == null) return null;

        String cmd = buildCommand(buildTool);
        int timeout = compilerProps.getTimeoutSeconds();

        try {
            // Attempt 1: compile
            String result = shellTool.run(runId, cmd, timeout);
            if (!result.startsWith("ERROR")) return null; // passed first try

            log.warn("[TierCompile] Syntax errors after tier merge for run {} — attempting LLM fix", runId);
            broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                    "⚠️ Syntax errors detected — auto-fixing..."));

            // LLM fix
            try {
                applyLlmFixes(runId, result, buildTool);
            } catch (Exception e) {
                log.warn("[TierCompile] LLM fix failed for run {}: {}", runId, e.getMessage());
                return result;
            }

            // Attempt 2: re-compile after fix
            String result2 = shellTool.run(runId, cmd, timeout);
            if (!result2.startsWith("ERROR")) {
                log.info("[TierCompile] Syntax errors fixed on second pass for run {}", runId);
                broadcaster.emit(runId, RunEvent.log(runId, "compiler", "✅ Syntax errors auto-fixed"));
                return null; // fixed
            }

            log.warn("[TierCompile] Still failing after 2 passes for run {}: {}",
                    runId, truncate(result2, 500));
            broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                    "⚠️ Syntax errors persist after auto-fix — will retry at final compile phase"));
            return result2;
        } catch (Exception e) {
            log.debug("[TierCompile] Error during quick compile for run {}: {}", runId, e.getMessage());
            return null; // non-fatal
        }
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
            case "mvn" -> "mvn compile -q -T 1C 2>&1";
            case "gradle" -> "gradle compileJava -q --parallel 2>&1";
            // Use `npm run build` if build script exists; otherwise try `npx vite build`
            // to catch import/syntax errors in Vite projects that lack a build script.
            case "npm" -> "npm install --legacy-peer-deps 2>&1 && " +
                    "if node -e \"process.exit(require('./package.json').scripts?.build ? 0 : 1)\" 2>/dev/null; " +
                    "then npm run build 2>&1; " +
                    "elif npx vite --version >/dev/null 2>&1; " +
                    "then npx vite build 2>&1; fi";
            default -> throw new IllegalArgumentException("Unknown build tool: " + tool);
        };
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
