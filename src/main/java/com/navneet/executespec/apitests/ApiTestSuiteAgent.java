package com.navneet.executespec.apitests;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.FileSystemTool;
import com.navneet.executespec.tool.GitTool;
import io.micrometer.context.ContextSnapshotFactory;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates an OpenAPI 3.0 YAML specification for backend-only runs,
 * post-merge.
 *
 * <p>
 * Skips when:
 * <ul>
 * <li>Frontend files (.html, .jsx, .tsx, .vue, .svelte) exist in the
 * workspace.</li>
 * <li>No Spring controller files (ending in *Controller.java / *Resource.java)
 * are found.</li>
 * </ul>
 *
 * <p>
 * On success writes {@code api-docs/openapi.yaml} to the run workspace and
 * marks the run with {@code hasApiSpec=true} via StateStore.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiTestSuiteAgent {

    private final FileSystemTool fsTool;
    private final GitTool gitTool;
    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;
    private final SseBroadcaster broadcaster;
    private final StateStore stateStore;
    private final ApiTestSuiteProperties props;

    @Value("classpath:prompts/api-test-suite.txt")
    private Resource promptResource;

    @Value("${agent.workspace}")
    private String workspaceRoot;

    private static final String[] FRONTEND_EXTENSIONS = { ".html", ".jsx", ".tsx", ".vue", ".svelte" };

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called post-merge via virtual thread from PipelineHarness. Non-fatal. */
    public void generateAsync(PipelineRun run) {
        var snapshot = ContextSnapshotFactory.builder().build().captureAll();
        Thread.ofVirtual()
                .name("api-spec-" + run.getRunId())
                .start(snapshot.wrap(() -> safeGenerate(run)));
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private void safeGenerate(PipelineRun run) {
        try {
            generate(run);
        } catch (Exception e) {
            log.error("ApiTestSuiteAgent failed for run {}: {}", run.getRunId(), e.getMessage(), e);
        }
    }

    private void generate(PipelineRun run) throws IOException {
        if (!props.isEnabled())
            return;
        String runId = run.getRunId();

        Path ws = Path.of(workspaceRoot, runId);

        // Find API route/controller source files (any language)
        // Note: we no longer skip for frontend-only projects — if API controllers exist, generate the spec.
        List<String> apiFiles = findApiRouteFiles(ws);
        if (apiFiles.isEmpty()) {
            log.info("ApiTestSuiteAgent: no API route/controller files — skipping spec gen for run {}", runId);
            return;
        }

        log.info("ApiTestSuiteAgent: generating OpenAPI spec for run {} ({} API file(s))",
                runId, apiFiles.size());
        broadcaster.emit(runId, RunEvent.log(runId, "api-test-suite",
                "Generating OpenAPI 3.0 spec from " + apiFiles.size() + " API file(s)..."));

        // Read API source
        String controllerContent = readFiles(runId, apiFiles);

        // Call LLM
        String system = promptResource.getContentAsString(StandardCharsets.UTF_8);
        ResolvedModel resolved = modelResolver.resolve("ApiTestSuiteAgent", run.getAiProviderEnum());
        AnthropicCacheOptions cacheOpts = AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                .build();
        AnthropicChatOptions opts = AnthropicChatOptions.builder()
                .temperature(0.0)
                .model(resolved.getModel())
                .cacheOptions(cacheOpts)
                .build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage("Generate OpenAPI 3.0 YAML for these backend API source files:\n\n" + controllerContent)), opts);

        var llmResponse = llmRouter.callWithFallback(
                resolved.providerName(), resolved.getModel(),
                prompt, runId, "api-test-suite",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false);

        // Track tokens for billing accuracy
        try {
            int inTok = llmResponse.getMetadata().getUsage().getPromptTokens().intValue();
            int outTok = llmResponse.getMetadata().getUsage().getCompletionTokens().intValue();
            stateStore.addTokensToRun(runId, inTok, outTok);
            log.debug("ApiTestSuiteAgent tokens: run={} in={} out={}", runId, inTok, outTok);
        } catch (Exception e) {
            log.warn("ApiTestSuiteAgent: failed to track tokens for run {}: {}", runId, e.getMessage());
        }

        String yaml = llmResponse.getResult().getOutput().getText();

        // Strip markdown fences if LLM added them
        yaml = yaml.trim()
                .replaceAll("(?s)^```ya?ml\\s*", "")
                .replaceAll("(?s)```$", "")
                .trim();

        if (yaml.isBlank() || !yaml.startsWith("openapi")) {
            log.warn("ApiTestSuiteAgent: unexpected LLM response for run {} — not saved", runId);
            return;
        }

        // Persist spec to filesystem
        fsTool.writeFile(runId, "api-docs/openapi.yaml", yaml);

        // Commit the spec to git on the current branch (main, post-merge).
        // This prevents a retried worker's `git add .` from accidentally staging
        // the untracked file onto a task branch, which would cause merge conflicts
        // or silently overwrite the spec during subsequent retry merges.
        gitTool.commitIfDirty(runId, "chore: update api-docs/openapi.yaml [auto]");

        stateStore.markApiSpecReady(runId);
        broadcaster.emit(runId, RunEvent.apiSpecReady(runId));
        log.info("ApiTestSuiteAgent: OpenAPI spec written and committed for run {}", runId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    boolean hasFrontendFiles(Path ws) {
        try (Stream<Path> s = Files.walk(ws)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains(".git"))
                    .anyMatch(p -> {
                        String name = p.getFileName().toString();
                        for (String ext : FRONTEND_EXTENSIONS) {
                            if (name.endsWith(ext))
                                return true;
                        }
                        return false;
                    });
        } catch (IOException e) {
            return false;
        }
    }

    /** Detect API route/controller files across all supported languages. */
    private List<String> findApiRouteFiles(Path ws) {
        // File-name patterns that strongly indicate REST API route definitions
        Set<String> javaPatterns = Set.of("Controller.java", "Resource.java");
        Set<String> exactNames = Set.of(
                "urls.py",         // Django
                "views.py",        // Django REST
                "main.go"          // Go (often single-file API servers)
        );

        try (Stream<Path> s = Files.walk(ws)) {
            List<Path> candidates = s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains(".git"))
                    .filter(p -> !p.toString().contains("__pycache__"))
                    .filter(p -> !p.toString().contains("vendor"))
                    .collect(Collectors.toList());

            // First pass: collect files matching name patterns
            List<String> results = new java.util.ArrayList<>();
            for (Path p : candidates) {
                String name = p.getFileName().toString();
                String rel = ws.relativize(p).toString();

                // Java Spring controllers
                if (javaPatterns.stream().anyMatch(name::endsWith)) {
                    results.add(rel);
                    continue;
                }
                // Exact name matches
                if (exactNames.contains(name)) {
                    results.add(rel);
                    continue;
                }
                // Python: files with route decorators (FastAPI/Flask)
                if (name.endsWith(".py") && (name.contains("route") || name.contains("api") || name.equals("app.py") || name.equals("server.py"))) {
                    results.add(rel);
                    continue;
                }
                // Node/TS: route files
                if ((name.endsWith(".js") || name.endsWith(".ts")) && (name.contains("route") || name.contains("controller") || name.equals("server.js") || name.equals("server.ts") || name.equals("app.js") || name.equals("app.ts") || name.equals("index.js") || name.equals("index.ts"))) {
                    results.add(rel);
                    continue;
                }
                // Go: handler files
                if (name.endsWith(".go") && (name.contains("handler") || name.contains("route") || name.contains("controller") || name.contains("api"))) {
                    results.add(rel);
                    continue;
                }
                // Rust: route/handler files
                if (name.endsWith(".rs") && (name.contains("route") || name.contains("handler") || name.equals("main.rs") || name.equals("lib.rs"))) {
                    results.add(rel);
                    continue;
                }
            }

            // If no files found by name, do content scan for route patterns in Python/Node entry files
            if (results.isEmpty()) {
                results = scanForRoutePatterns(ws, candidates);
            }

            return results;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Fallback: scan source files for common route decorator/function patterns. */
    private List<String> scanForRoutePatterns(Path ws, List<Path> candidates) {
        List<String> results = new java.util.ArrayList<>();
        Set<String> routePatterns = Set.of(
                "@app.route", "@app.get", "@app.post", "@router.",  // Python Flask/FastAPI
                "app.get(", "app.post(", "app.put(", "app.delete(",  // Node Express
                "router.get(", "router.post(",                        // Node Express Router
                "HandleFunc(", "gin.Default(", "echo.New(",           // Go
                "#[get(", "#[post(", "web::resource"                  // Rust Actix
        );

        for (Path p : candidates) {
            String name = p.getFileName().toString();
            if (!(name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".ts") ||
                    name.endsWith(".go") || name.endsWith(".rs"))) continue;

            try {
                String content = Files.readString(p);
                if (content.length() > 50_000) continue; // skip large files
                for (String pattern : routePatterns) {
                    if (content.contains(pattern)) {
                        results.add(ws.relativize(p).toString());
                        break;
                    }
                }
            } catch (IOException ignored) {}

            if (results.size() >= 10) break; // cap to avoid reading too many files
        }
        return results;
    }

    private String readFiles(String runId, List<String> relativePaths) {
        var sb = new StringBuilder();
        for (String f : relativePaths) {
            String result = fsTool.readFile(runId, f);
            if (!result.startsWith("ERROR")) {
                sb.append("=== ").append(f).append(" ===\n")
                        .append(result).append("\n\n");
            }
        }
        return sb.toString();
    }
}
