package com.navneet.agentteam.apitests;

import com.navneet.agentteam.agent.LlmRouter;
import com.navneet.agentteam.sse.RunEvent;
import com.navneet.agentteam.sse.SseBroadcaster;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.tool.FileSystemTool;
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
import java.util.List;
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
    private final LlmRouter llmRouter;
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
        Thread.ofVirtual()
                .name("api-spec-" + run.getRunId())
                .start(() -> safeGenerate(run));
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

        // Skip if frontend files detected
        if (hasFrontendFiles(ws)) {
            log.info("ApiTestSuiteAgent: frontend detected — skipping spec gen for run {}", runId);
            return;
        }

        // Find controller source files
        List<String> controllers = findControllerFiles(ws);
        if (controllers.isEmpty()) {
            log.info("ApiTestSuiteAgent: no controller files — skipping spec gen for run {}", runId);
            return;
        }

        log.info("ApiTestSuiteAgent: generating OpenAPI spec for run {} ({} controller(s))",
                runId, controllers.size());
        broadcaster.emit(runId, RunEvent.log(runId, "api-test-suite",
                "📄 Generating OpenAPI 3.0 spec from " + controllers.size() + " controller(s)..."));

        // Read controller source
        String controllerContent = readFiles(runId, controllers);

        // Call LLM
        String system = promptResource.getContentAsString(StandardCharsets.UTF_8);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage("Generate OpenAPI 3.0 YAML for these controllers:\n\n" + controllerContent)));

        String yaml = llmRouter.callWithFallback(
                props.getProvider(), props.getModel(),
                prompt, runId, "api-test-suite",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false).getResult().getOutput().getText();

        // Strip markdown fences if LLM added them
        yaml = yaml.trim()
                .replaceAll("(?s)^```ya?ml\\s*", "")
                .replaceAll("(?s)```$", "")
                .trim();

        if (yaml.isBlank() || !yaml.startsWith("openapi")) {
            log.warn("ApiTestSuiteAgent: unexpected LLM response for run {} — not saved", runId);
            return;
        }

        // Persist spec + update run
        fsTool.writeFile(runId, "api-docs/openapi.yaml", yaml);
        stateStore.markApiSpecReady(runId);
        broadcaster.emit(runId, RunEvent.apiSpecReady(runId));
        log.info("ApiTestSuiteAgent: OpenAPI spec written for run {}", runId);
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

    private List<String> findControllerFiles(Path ws) {
        try (Stream<Path> s = Files.walk(ws)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("Controller.java") || name.endsWith("Resource.java");
                    })
                    .map(p -> ws.relativize(p).toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
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
