package com.navneet.executespec.agent;
import com.navneet.executespec.config.ModelConstants;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.agent.model.TaskReview;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.deployment.WorkspaceFileService;
import com.navneet.executespec.deployment.preview.PreviewService;
import com.navneet.executespec.deployment.preview.PreviewSession;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Post-run conversational AI agent.
 *
 * <p>After a run reaches COMPLETE or PARTIAL the user can ask questions about the generated
 * code, debug preview issues, or request improvement goals. This agent holds a conversational
 * context window that includes:
 * <ul>
 *   <li>A system prompt describing the completed project with code snippets.</li>
 *   <li>Preview/Docker status and error information for debugging.</li>
 *   <li>The full conversation history (user / assistant turns) accumulated so far.</li>
 *   <li>The new user message.</li>
 * </ul>
 *
 * <p>Uses the CAPABLE_CHAIN (Sonnet) for deeper code understanding and diagnostic ability.
 * The conversation is capped at {@link #MAX_MESSAGES} user messages.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationAgent {

    /** Maximum number of USER messages allowed per run conversation. */
    public static final int MAX_MESSAGES = 50;

    private static final String PRIMARY_PROVIDER = "anthropic";
    private static final String PRIMARY_MODEL    = ModelConstants.SONNET;
    private static final int    MAX_OUTPUT_TOKENS = 4096;
    private static final int    MAX_CONTEXT_CHARS = 32_000; // ~8000 tokens of workspace context
    private static final int    MAX_FILE_CHARS    = 3000;   // per source file

    /** Key files to auto-include in context (order matters — first found wins per category). */
    private static final List<String> ENTRY_POINT_CANDIDATES = List.of(
            "src/index.js", "src/index.ts", "src/main.jsx", "src/main.tsx",
            "src/App.jsx", "src/App.tsx", "src/app.js", "src/app.ts",
            "index.js", "index.ts", "server.js", "app.js", "app.ts",
            "main.py", "app.py", "manage.py",
            "src/main/java"  // marker — handled separately for Spring Boot
    );

    /** Config files always worth including. */
    private static final Set<String> CONFIG_FILE_NAMES = Set.of(
            "package.json", "vite.config.js", "vite.config.ts",
            "tsconfig.json", "pom.xml", "requirements.txt",
            ".env.example", "docker-compose.yml", "Dockerfile"
    );

    /** Max number of source files to include beyond the essentials. */
    private static final int MAX_SOURCE_FILES = 8;

    /** Max tasks in a suggestion mini-plan. If exceeded, throw ApplySuggestionTooLargeException. */
    private static final int MAX_SUGGESTION_TASKS = 6;

    private final LlmRouter llmRouter;
    private final WorkspaceFileService workspaceFileService;
    private final PreviewService previewService;
    private final ObjectMapper objectMapper;

    /**
     * Sends a user message in the context of a completed run and returns the assistant reply.
     *
     * @param run        the completed PipelineRun — used to build the system prompt context
     * @param history    previous conversation messages in {role, content} form
     * @param newMessage the current user message to respond to
     * @return the assistant's reply text
     */
    public String chat(PipelineRun run, List<ChatMessageRecord> history, String newMessage) {
        log.info("[ConversationAgent] run={} historySize={} model={}", run.getRunId(), history.size(), PRIMARY_MODEL);

        String systemPrompt = buildSystemPrompt(run);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        // Replay conversation history
        for (ChatMessageRecord m : history) {
            if ("user".equalsIgnoreCase(m.role())) {
                messages.add(new UserMessage(m.content()));
            } else {
                messages.add(new AssistantMessage(m.content()));
            }
        }

        // Add the current user message
        messages.add(new UserMessage(newMessage));

        // Build prompt with Sonnet + max tokens
        var opts = org.springframework.ai.anthropic.AnthropicChatOptions.builder()
            .model(PRIMARY_MODEL)
            .maxTokens(MAX_OUTPUT_TOKENS)
            .build();
        Prompt prompt = new Prompt(messages, opts);

        var response = llmRouter.callWithFallback(
            PRIMARY_PROVIDER, PRIMARY_MODEL, prompt,
            run.getRunId(), "conversation",
            LlmRouter.FallbackMode.IMMEDIATE, 0, false
        );

        String reply = response.getResult().getOutput().getText();
        log.debug("[ConversationAgent] run={} reply length={}", run.getRunId(), reply != null ? reply.length() : 0);
        return reply != null ? reply.trim() : "I'm sorry, I couldn't generate a response. Please try again.";
    }

    // ── Apply suggestion: mini-plan generation ──────────────────────────────

    /**
     * Generates a mini TaskPlan (≤6 tasks) from a suggestion text.
     * Used by both Chat Copilot "Apply to Run" and Review "Apply Review" flows.
     *
     * @param run        the completed run providing workspace context
     * @param suggestion the improvement suggestion to turn into tasks
     * @return a TaskPlan with ≤6 tasks
     * @throws ApplySuggestionTooLargeException if the LLM generates >6 tasks
     */
    public TaskPlan generateSuggestionPlan(PipelineRun run, String suggestion) {
        log.info("[ConversationAgent] Generating suggestion plan for run={}", run.getRunId());

        String workspaceContext = buildWorkspaceContext(run.getRunId());

        String systemPrompt = """
            You are a task planner for an automated coding system.
            Given an existing project and a suggestion for improvement, generate a focused task plan.

            ORIGINAL USER REQUEST:
            %s

            PROJECT FILES AND SOURCE CODE:
            %s

            RULES:
            1. Generate AT MOST %d tasks. Fewer is better — only create tasks that are necessary.
            2. Each task must be small and focused (LOW or MEDIUM complexity).
            3. Use the exact JSON format below — no extra text, just JSON.
            4. Assign all tasks the model "claude-haiku-4-5-20251001".
            5. Task IDs should be short snake_case identifiers (e.g., "fix_port_config", "add_error_handling").
            6. Set depends_on to enforce ordering where needed (empty array if independent).
            7. owned_files and shared_files should list the specific file paths each task will modify.

            OUTPUT FORMAT (strict JSON, no markdown fences):
            {
              "plan_summary": "Brief description of what these tasks will fix/improve",
              "tasks": [
                {
                  "task_id": "fix_something",
                  "title": "Fix the something",
                  "objective": "Detailed description of what to do",
                  "boundaries": "Only modify file X. Do not change Y.",
                  "output_format": "Modified source file",
                  "acceptance_criteria": "The thing works correctly after the fix",
                  "depends_on": [],
                  "complexity": "LOW",
                  "assigned_model": "claude-haiku-4-5-20251001",
                  "owned_files": ["src/file.js"],
                  "shared_files": []
                }
              ]
            }
            """.formatted(
                run.getUserRequest() != null ? run.getUserRequest() : "(unknown)",
                truncate(workspaceContext, 16_000),
                MAX_SUGGESTION_TASKS
            );

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage("Generate a task plan for the following improvement:\n\n" + suggestion));

        var opts = org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                .model(PRIMARY_MODEL)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .build();

        var response = llmRouter.callWithFallback(
                PRIMARY_PROVIDER, PRIMARY_MODEL, new Prompt(messages, opts),
                run.getRunId(), "suggestion-plan",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false
        );

        String raw = response.getResult().getOutput().getText();
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("Empty response from LLM for suggestion plan");
        }

        // Strip markdown code fences if present
        raw = raw.trim();
        if (raw.startsWith("```")) {
            raw = raw.replaceFirst("^```[a-z]*\\n?", "").replaceFirst("\\n?```$", "").trim();
        }

        try {
            TaskPlan plan = objectMapper.readValue(raw, TaskPlan.class);

            if (plan.getTasks() == null || plan.getTasks().isEmpty()) {
                throw new RuntimeException("LLM generated an empty task plan");
            }

            if (plan.getTasks().size() > MAX_SUGGESTION_TASKS) {
                // Compile updated goal for a fresh run
                String compiledGoal = compileGoal(run.getUserRequest(), suggestion);
                throw new ApplySuggestionTooLargeException(compiledGoal);
            }

            log.info("[ConversationAgent] Generated {} suggestion tasks for run={}",
                    plan.getTasks().size(), run.getRunId());
            return plan;
        } catch (ApplySuggestionTooLargeException e) {
            throw e; // re-throw without wrapping
        } catch (Exception e) {
            log.error("[ConversationAgent] Failed to parse suggestion plan: {}", e.getMessage());
            throw new RuntimeException("Failed to parse suggestion plan from LLM: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts unresolved review issues from the run's review JSON and compiles
     * them into a structured suggestion string suitable for {@link #generateSuggestionPlan}.
     *
     * @param run the completed run with review_json
     * @return a compiled suggestion text, or null if no actionable issues found
     */
    public String extractReviewSuggestion(PipelineRun run) {
        if (run.getReviewJson() == null || run.getReviewJson().isBlank()) {
            return null;
        }

        try {
            // Try V2 format first (has verdict field)
            if (run.getReviewJson().contains("\"verdict\"")) {
                ReviewReportV2 v2 = objectMapper.readValue(run.getReviewJson(), ReviewReportV2.class);
                if (v2.getIssues() == null || v2.getIssues().isEmpty()) return null;

                StringBuilder sb = new StringBuilder("Fix the following code review issues:\n\n");
                int idx = 1;
                for (com.navneet.executespec.reviewer.model.ReviewIssue issue : v2.getIssues()) {
                    // Skip auto-fixed issues that succeeded
                    if (issue.isAutoFixed() && issue.isFixSucceeded()) continue;

                    sb.append(idx++).append(". [").append(issue.getSeverity()).append("] ");
                    if (issue.getFile() != null) sb.append("File: ").append(issue.getFile()).append(" — ");
                    sb.append(issue.getDescription());
                    if (issue.getFixInstruction() != null) sb.append("\n   Fix: ").append(issue.getFixInstruction());
                    sb.append("\n\n");
                }
                return idx > 1 ? sb.toString() : null;
            }

            // V1 format (has recommendation field)
            ReviewReport v1 = objectMapper.readValue(run.getReviewJson(), ReviewReport.class);
            StringBuilder sb = new StringBuilder("Fix the following code review issues:\n\n");
            int idx = 1;

            if (v1.getTaskReviews() != null) {
                for (TaskReview tr : v1.getTaskReviews()) {
                    if (tr.getIssues() != null) {
                        for (var issue : tr.getIssues()) {
                            sb.append(idx++).append(". [").append(issue.getSeverity()).append("] ");
                            if (issue.getFile() != null) sb.append("File: ").append(issue.getFile()).append(" — ");
                            sb.append(issue.getDescription()).append("\n\n");
                        }
                    }
                }
            }

            // Also include merge blockers
            if (v1.getMergeBlockers() != null) {
                for (String blocker : v1.getMergeBlockers()) {
                    sb.append(idx++).append(". [BLOCKER] ").append(blocker).append("\n\n");
                }
            }

            return idx > 1 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("[ConversationAgent] Failed to parse review JSON for run {}: {}", run.getRunId(), e.getMessage());
            return null;
        }
    }

    /**
     * Compiles the original user request + suggestion into a combined goal
     * suitable for starting a fresh run.
     */
    private String compileGoal(String originalRequest, String suggestion) {
        StringBuilder sb = new StringBuilder();
        if (originalRequest != null && !originalRequest.isBlank()) {
            sb.append(originalRequest.trim());
        }
        sb.append("\n\nAdditionally:\n");
        sb.append(suggestion.trim());
        return sb.toString();
    }

    // ── System prompt builder ────────────────────────────────────────────────

    private String buildSystemPrompt(PipelineRun run) {
        String status  = run.getStatus();
        String request = run.getUserRequest() != null ? run.getUserRequest() : "(unknown)";
        String model   = run.getPlannerModel() != null ? run.getPlannerModel() : "unknown model";

        String workspaceContext = buildWorkspaceContext(run.getRunId());
        String previewContext   = buildPreviewContext(run.getRunId());

        String planSection = "";
        if (run.getPlanJson() != null && !run.getPlanJson().isBlank()) {
            planSection = "PLAN:\n" + truncate(run.getPlanJson(), 5000) + "\n";
        }
        String reviewSection = "";
        if (run.getReviewJson() != null && !run.getReviewJson().isBlank()) {
            reviewSection = "CODE REVIEW:\n" + truncate(run.getReviewJson(), 3000) + "\n";
        }
        String failureSection = "";
        if (run.getFailureReason() != null && !run.getFailureReason().isBlank()) {
            failureSection = "FAILURE REASON:\n" + run.getFailureReason() + "\n";
        }

        return """
            You are an expert software engineering assistant reviewing a completed automated coding run.
            You have deep access to the project's source code, build plan, review, and preview/Docker status.

            THE PROJECT THAT WAS BUILT:
            ─────────────────────────────
            User's original request: %s

            Run ID: %s
            Planned by: %s
            Status: %s

            %s
            %s
            %s

            PREVIEW / DOCKER STATUS:
            ─────────────────────────────
            %s

            PROJECT FILES AND SOURCE CODE:
            ─────────────────────────────
            %s

            YOUR ROLE:
            ─────────────────────────────
            You are the user's technical co-pilot for this run. You can:

            1. **Debug issues** — Diagnose preview failures, build errors, runtime errors, and
               port misconfigurations by inspecting the source code and Docker/preview status above.
               If you see port mismatches (e.g., Vite proxy targets port X but server runs on port Y),
               config errors, or missing dependencies, explain the root cause clearly.

            2. **Suggest improvements** — Proactively identify code quality issues, missing error
               handling, security concerns, performance bottlenecks, or architectural improvements.
               Frame each suggestion as a concrete improvement goal with a clear description of
               what to change and why.

            3. **Answer questions** — Explain what was implemented, the architecture, design choices,
               and how different parts of the code interact. Reference specific files and line numbers
               when relevant.

            4. **Create improvement goals** — When you identify issues or the user asks for changes,
               describe them as actionable goals. The user can then click "Implement as New Run" to
               apply changes via the automated pipeline.

            RESPONSE FORMAT:
            ─────────────────────────────
            • Use **markdown** formatting for readability (headings, code blocks, bullet points).
            • When referencing code, use ``` code blocks with the language specified.
            • When suggesting improvements, use a clear format like:
              **Improvement:** <title>
              **File(s):** <file paths>
              **What to change:** <specific description>
            • Be concise but thorough. Assume the user is a developer.
            • If you identify a bug or misconfiguration, lead with the diagnosis.

            CONVERSATION LIMIT:
            ─────────────────────────────
            This conversation is limited to %d messages. When suggesting substantial changes,
            encourage the user to click "Implement as New Run" to apply them automatically.
            """.formatted(request, run.getRunId(), model, status,
                    planSection, reviewSection, failureSection,
                    previewContext, workspaceContext, MAX_MESSAGES);
    }

    // ── Workspace context: file tree + key source files ─────────────────────

    private String buildWorkspaceContext(String runId) {
        StringBuilder sb = new StringBuilder();
        try {
            List<String> files = workspaceFileService.listFiles(runId);
            if (files.isEmpty()) {
                sb.append("(workspace files not available)\n");
                return sb.toString();
            }

            // 1. File tree
            sb.append("File tree:\n");
            for (String f : files) {
                sb.append("  ").append(f).append('\n');
            }
            sb.append('\n');

            // 2. README and context docs
            tryAppendFile(sb, runId, "README.md", "README.md", 4000);
            tryAppendFile(sb, runId, ".agent-context/CODEBASE_CONTEXT.md", "Codebase Context", 3000);

            // 3. Config files (package.json, vite.config, etc.)
            int included = 0;
            for (String file : files) {
                String fileName = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
                if (CONFIG_FILE_NAMES.contains(fileName) && included < MAX_SOURCE_FILES) {
                    // Skip if already included (README, context)
                    if (file.equals("README.md") || file.startsWith(".agent-context")) continue;
                    tryAppendFile(sb, runId, file, file, MAX_FILE_CHARS);
                    included++;
                }
            }

            // 4. Entry point files and key source files
            for (String file : files) {
                if (included >= MAX_SOURCE_FILES) break;
                if (isKeySourceFile(file) && !isAlreadyIncluded(file)) {
                    tryAppendFile(sb, runId, file, file, MAX_FILE_CHARS);
                    included++;
                }
            }

        } catch (Exception e) {
            log.warn("[ConversationAgent] Could not read workspace for run {}: {}", runId, e.getMessage());
            sb.append("(workspace files not available)\n");
        }
        return truncate(sb.toString(), MAX_CONTEXT_CHARS);
    }

    /**
     * Determine if a file is a key source file worth including in the context.
     * Targets entry points, server configs, and API route files.
     */
    private boolean isKeySourceFile(String filePath) {
        String lower = filePath.toLowerCase();

        // Skip non-source files
        if (lower.endsWith(".lock") || lower.endsWith(".json") && lower.contains("lock")) return false;
        if (lower.endsWith(".css") || lower.endsWith(".svg") || lower.endsWith(".png")) return false;
        if (lower.startsWith(".git") || lower.startsWith(".agent-context")) return false;

        // Entry points
        String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        if (fileName.equals("index.js") || fileName.equals("index.ts") ||
            fileName.equals("main.jsx") || fileName.equals("main.tsx") ||
            fileName.equals("app.jsx") || fileName.equals("app.tsx") ||
            fileName.equals("app.js") || fileName.equals("app.ts") ||
            fileName.equals("server.js") || fileName.equals("main.py") ||
            fileName.equals("app.py")) return true;

        // Route/API files
        if (lower.contains("/routes/") || lower.contains("/api/")) return true;

        // Config files in subdirectories
        if (fileName.equals("vite.config.js") || fileName.equals("vite.config.ts")) return true;
        if (fileName.equals("package.json")) return true;

        // Service/component files (limit to first-level src files)
        if (lower.contains("/services/") || lower.contains("/lib/")) return true;

        return false;
    }

    private boolean isAlreadyIncluded(String file) {
        return file.equals("README.md") || file.startsWith(".agent-context");
    }

    // ── Preview / Docker context ────────────────────────────────────────────

    private String buildPreviewContext(String runId) {
        try {
            PreviewSession session = previewService.getStatus(runId);
            if (session == null) {
                return "No preview has been started for this run.\n";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Preview status: ").append(session.status()).append('\n');
            sb.append("Project type: ").append(session.projectType()).append('\n');
            sb.append("Service: ").append(session.cloudRunServiceName()).append('\n');
            sb.append("URL: ").append(session.url()).append('\n');

            if (session.errorMessage() != null && !session.errorMessage().isBlank()) {
                sb.append("Error: ").append(session.errorMessage()).append('\n');
            }

            // Include Docker logs for debugging if preview failed or is running
            if (session.status() != null) {
                String statusName = session.status().name();
                if ("FAILED".equals(statusName) || "RUNNING".equals(statusName) || "STARTING".equals(statusName)) {
                    String logs = previewService.getLogs(runId, 50, null);
                    if (logs != null && !logs.isBlank()) {
                        sb.append("\nDocker container logs (last 50 lines):\n```\n");
                        sb.append(truncate(logs, 4000));
                        sb.append("\n```\n");
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.debug("[ConversationAgent] Could not get preview status for run {}: {}", runId, e.getMessage());
            return "Preview status unavailable.\n";
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void tryAppendFile(StringBuilder sb, String runId, String path, String label, int maxChars) {
        try {
            String content = workspaceFileService.readFile(runId, path);
            if (content != null && !content.isBlank()) {
                // Detect language for code fence
                String lang = detectLanguage(path);
                sb.append("── ").append(label).append(" ──\n");
                if (!lang.isEmpty()) {
                    sb.append("```").append(lang).append('\n');
                    sb.append(truncate(content, maxChars));
                    sb.append("\n```\n\n");
                } else {
                    sb.append(truncate(content, maxChars)).append("\n\n");
                }
            }
        } catch (IOException e) {
            // File doesn't exist — skip
        }
    }

    private static String detectLanguage(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".md")) return "";  // markdown files rendered as-is
        return "";
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text != null ? text : "";
        return text.substring(0, maxChars) + "\n...[truncated]";
    }

    /**
     * Simple record representing a single conversation message.
     *
     * @param role    "user" or "assistant"
     * @param content the message text
     */
    public record ChatMessageRecord(String role, String content) {}
}
