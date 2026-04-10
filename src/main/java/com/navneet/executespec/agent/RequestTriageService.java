package com.navneet.executespec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Classifies incoming requests as SINGLE_SHOT, MEDIUM, or MULTI_TASK using a LIGHTWEIGHT LLM call.
 *
 * <p>SINGLE_SHOT requests bypass the planner and worktree system entirely — a synthetic
 * 1-task plan is built and executed directly on the main branch.
 *
 * <p>MEDIUM requests go through a simplified planner targeting 2-4 tasks maximum.
 *
 * <p>Falls back to MULTI_TASK on any error (LLM failure, parse error, timeout).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RequestTriageService {

    public enum TriageResult { SINGLE_SHOT, MEDIUM, MULTI_TASK }

    /** Parsed classification from the LLM response. */
    public record TriageClassification(TriageResult result, double confidence, String reason) {}

    private final ObjectMapper objectMapper;

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;

    @Value("${agent.triage.enabled:true}")
    private boolean triageEnabled;

    @Value("classpath:prompts/triage.txt")
    private Resource triagePrompt;

    /**
     * Classifies the user request. Returns MULTI_TASK if triage is disabled or on any error.
     *
     * @param userRequest the raw user specification / request text
     * @param runId       current run ID for LLM routing
     * @param provider    user's selected AI provider
     * @return SINGLE_SHOT, MEDIUM, or MULTI_TASK
     */
    /** Patterns that indicate a full-stack project — always MULTI_TASK regardless of LLM classification. */
    private static final java.util.regex.Pattern FULL_STACK_PATTERN = java.util.regex.Pattern.compile(
        "(?i)(?:" +
        "full[- ]?stack|fullstack|mern|mean|pern|" +
        "(?:express|fastify|flask|fastapi|django|spring\\s*boot|rails|nestjs|hono|koa)" +
            "\\s.*?(?:react|vue|angular|svelte|next\\.?js|frontend|ui|dashboard)|" +
        "(?:react|vue|angular|svelte|next\\.?js|frontend|ui|dashboard)" +
            "\\s.*?(?:express|fastify|flask|fastapi|django|spring\\s*boot|rails|nestjs|hono|koa)|" +
        "(?:rest\\s*api|api\\s+server|backend)\\s.*?(?:frontend|ui|react|vue|dashboard)|" +
        "(?:todo\\s+app|task\\s+manager|blog\\s+platform|e-?commerce|chat\\s+app)" +
            "\\s.*?(?:with|using|and)\\s.*?(?:express|react|vue|mongo|postgres|mysql|sqlite)" +
        ")"
    );

    public TriageResult classify(String userRequest, String runId, LlmProvider provider) {
        if (!triageEnabled) {
            log.info("[Triage] Disabled via config — defaulting to MULTI_TASK for run {}", runId);
            return TriageResult.MULTI_TASK;
        }

        // Safety check: force MULTI_TASK for requests that match full-stack patterns
        if (FULL_STACK_PATTERN.matcher(userRequest).find()) {
            log.info("[Triage] Run {} matches full-stack pattern — forcing MULTI_TASK", runId);
            return TriageResult.MULTI_TASK;
        }

        try {
            String rawPrompt = triagePrompt.getContentAsString(StandardCharsets.UTF_8);
            // Inject workspace context (best-effort — empty strings if workspace not yet created)
            String fileTree = extractFileTree(runId);
            String frameworks = detectFrameworks(fileTree);
            String systemPrompt = rawPrompt
                    .replace("{file_tree}", fileTree)
                    .replace("{detected_frameworks}", frameworks);

            ResolvedModel resolved = modelResolver.resolve("RequestTriageService", provider);

            Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userRequest)
            ));

            var response = llmRouter.callWithFallback(
                resolved.providerName(), resolved.getModel(), prompt,
                runId, "request-triage",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false
            );

            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                log.warn("[Triage] Empty LLM response for run {} — defaulting to MULTI_TASK", runId);
                return TriageResult.MULTI_TASK;
            }

            // Parse JSON response: {"classification": "SINGLE_SHOT|MEDIUM|MULTI_TASK", "confidence": 0.9, "reason": "..."}
            TriageClassification classification = parseTriageResponse(text.trim(), runId);
            log.info("[Triage] Run {} classified as {} (confidence={}, reason={})",
                    runId, classification.result(), classification.confidence(), classification.reason());

            // Low confidence → escalate to MULTI_TASK for safety
            if (classification.confidence() < 0.7 && classification.result() != TriageResult.MULTI_TASK) {
                log.info("[Triage] Run {} — low confidence ({}) → escalating to MULTI_TASK",
                        runId, classification.confidence());
                return TriageResult.MULTI_TASK;
            }
            return classification.result();

        } catch (Exception e) {
            log.warn("[Triage] Failed for run {} — defaulting to MULTI_TASK: {}", runId, e.getMessage());
            return TriageResult.MULTI_TASK;
        }
    }

    private TriageClassification parseTriageResponse(String text, String runId) {
        try {
            // Strip markdown fences if present
            String json = text;
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
            }
            JsonNode node = objectMapper.readTree(json);
            String classStr = node.path("classification").asText("MULTI_TASK").toUpperCase();
            double confidence = node.path("confidence").asDouble(1.0);
            String reason = node.path("reason").asText("");

            TriageResult result;
            try {
                result = TriageResult.valueOf(classStr);
            } catch (IllegalArgumentException e) {
                // Legacy response without MEDIUM field — fall back to two-tier parsing
                result = classStr.contains("SINGLE_SHOT") ? TriageResult.SINGLE_SHOT : TriageResult.MULTI_TASK;
            }
            return new TriageClassification(result, confidence, reason);
        } catch (Exception e) {
            // Fall back to simple string matching
            if (text.contains("SINGLE_SHOT")) return new TriageClassification(TriageResult.SINGLE_SHOT, 1.0, "");
            if (text.contains("MEDIUM")) return new TriageClassification(TriageResult.MEDIUM, 1.0, "");
            return new TriageClassification(TriageResult.MULTI_TASK, 1.0, "");
        }
    }

    /** Best-effort file tree extraction from workspace/{runId}. Returns empty string if not available. */
    private String extractFileTree(String runId) {
        Path workspace = Path.of("workspace", runId);
        if (!Files.exists(workspace)) return "(workspace not yet created)";
        try {
            List<String> lines = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(workspace, 4)) {
                walk.filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("/node_modules/"))
                    .limit(60)
                    .forEach(p -> lines.add(workspace.relativize(p).toString()));
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            return "(could not read workspace)";
        }
    }

    /** Detects framework names from a file listing string. */
    private String detectFrameworks(String fileTree) {
        List<String> found = new ArrayList<>();
        if (fileTree.contains("pom.xml")) found.add("Spring Boot (Maven)");
        if (fileTree.contains("build.gradle")) found.add("Spring Boot (Gradle)");
        if (fileTree.contains("package.json")) {
            if (fileTree.contains("react") || fileTree.contains("jsx") || fileTree.contains("tsx")) found.add("React");
            if (fileTree.contains("vue")) found.add("Vue");
            if (fileTree.contains("server.js") || fileTree.contains("app.js") || fileTree.contains("index.js")) found.add("Node.js/Express");
            if (fileTree.contains("next.config")) found.add("Next.js");
        }
        if (fileTree.contains("requirements.txt") || fileTree.contains("pyproject.toml")) {
            if (fileTree.contains("fastapi") || fileTree.contains("main.py")) found.add("FastAPI");
            else if (fileTree.contains("flask") || fileTree.contains("app.py")) found.add("Flask");
            else found.add("Python");
        }
        if (fileTree.contains("go.mod")) found.add("Go");
        if (fileTree.contains("Cargo.toml")) found.add("Rust");
        return found.isEmpty() ? "none detected" : String.join(", ", found);
    }
}
