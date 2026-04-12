package com.navneet.executespec.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmResponseCleaner;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Staff-engineer-level supervisor that classifies post-merge compile errors and
 * decides whether to PASS (cascading noise), AUTO_FIX (surgical patch), RETRY_TASK
 * (re-run worker with targeted feedback), or CONTINUE_WITH_WARNING (non-owned errors).
 *
 * <p>Invoked by {@link SequentialTaskExecutor} when
 * {@link ProgressiveMergeValidator#quickCheck} returns FAILED, replacing the
 * previous "always revert + retry" logic.
 *
 * <p>Decision flow:
 * <ol>
 *   <li>Rule-based fast path: covers the majority of cases without an LLM call</li>
 *   <li>PLANNING-tier LLM call: only for borderline/mixed-error cases</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Deprecated(since = "v2")
public class TaskCompileSupervisor {

    private final StateStore stateStore;
    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;
    private final PromptAssembler promptAssembler;
    private final ObjectMapper mapper;
    private final SseBroadcaster broadcaster;
    private final MeterRegistry meterRegistry;

    private static final String PROMPT_PATH = "task-compile-supervisor.txt";

    // ── Error type classification ─────────────────────────────────────────────

    private enum ErrorType {
        CASCADING,            // from unmerged sibling tasks — expected noise
        STUB,                 // intentional STUB placeholder — ignore
        MISSING_IMPORT,       // wrong/missing import in owned file — auto-fixable
        SYNTAX,               // bracket/semicolon in owned file — auto-fixable
        TYPE_MISMATCH_SIMPLE, // simple type mismatch in 1 owned file — auto-fixable
        LOGIC,                // missing method impl, logic error — needs retry
        MISSING_FILE,         // required file never created — needs retry
        API_MISMATCH,         // method signature mismatch across files — borderline
        NON_OWNED,            // error in a file not owned by this task
        UNCLEAR               // cannot determine ownership or category — borderline
    }

    private record ErrorClassification(
            int totalErrorCount,
            int ownedErrorCount,
            int cascadingErrorCount,
            int fixableCount,      // AUTO_FIX eligible
            int structuralCount,   // RETRY_TASK indicators
            int stubCount,
            boolean allErrorsInNonOwnedFiles,
            boolean hasBorderlineCase
    ) {}

    // ── Known cascading TS error codes ────────────────────────────────────────

    private static final Set<String> CASCADING_TS_CODES =
            Set.of("TS2307", "TS2304", "TS2305", "TS6059", "TS2339");

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Per-task entry point. Called when {@code quickCheck()} returns FAILED, before
     * any merge revert.
     *
     * @param run           the pipeline run
     * @param task          the task whose merge just failed the compile check
     * @param errorOutput   raw compile error string from quickCheck()
     * @param mergedTaskIds task IDs already merged to main
     * @param providers     available providers for LLM escalation
     */
    @Deprecated(since = "v2")
    public TaskCompileDecision supervise(
            PipelineRun run,
            AgentTask task,
            String errorOutput,
            Set<String> mergedTaskIds,
            Set<LlmProvider> providers) {

        String runId = run.getRunId();
        List<String> ownedFiles = extractOwnedFiles(task);

        List<AgentTask> allTasks = stateStore.getAllTasks(runId);
        List<AgentTask> pendingTasks = allTasks.stream()
                .filter(t -> isPending(t.getStatus()))
                .filter(t -> !mergedTaskIds.contains(t.getTaskId()))
                .filter(t -> !task.getTaskId().equals(t.getTaskId()))
                .toList();

        ErrorClassification classification = classifyErrors(errorOutput, ownedFiles, pendingTasks);

        TaskCompileDecision fastPath = applyRules(classification, errorOutput, ownedFiles);
        if (fastPath != null) {
            emitDecisionEvent(runId, task.getTaskId(), fastPath);
            recordMetric(fastPath.action());
            return fastPath;
        }

        // Borderline: call the LLM staff engineer
        TaskCompileDecision llmDecision = callLlmDecision(run, task, ownedFiles, errorOutput, pendingTasks, mergedTaskIds);
        emitDecisionEvent(runId, task.getTaskId(), llmDecision);
        recordMetric(llmDecision.action());
        return llmDecision;
    }

    /**
     * Post-group entry point. Called after all subtasks of a parent task merge
     * when the post-tier {@code quickCompileAndFix()} still returns errors.
     * Used for observability and logging; the final CompilerAgent pass handles
     * any remaining structural issues.
     */
    @Deprecated(since = "v2")
    public TaskCompileDecision superviseTaskGroup(
            PipelineRun run,
            AgentTask parentTask,
            List<AgentTask> completedSubtasks,
            String compileError,
            Set<String> mergedTaskIds,
            Set<LlmProvider> providers) {

        // Aggregate all owned files from the group
        List<String> groupOwnedFiles = new ArrayList<>(extractOwnedFiles(parentTask));
        for (AgentTask subtask : completedSubtasks) {
            groupOwnedFiles.addAll(extractOwnedFiles(subtask));
        }

        // Build a synthetic task carrying the aggregated owned files for classification
        AgentTask syntheticTask = buildSyntheticTaskWithFiles(parentTask, groupOwnedFiles);
        return supervise(run, syntheticTask, compileError, mergedTaskIds, providers);
    }

    // ── Classification ───────────────────────────────────────────────────────

    @Deprecated(since = "v2")
    private ErrorClassification classifyErrors(String errorOutput,
                                                List<String> ownedFiles,
                                                List<AgentTask> pendingTasks) {
        // Build lookup sets for cascading detection
        Set<String> pendingFileSimpleNames = pendingTasks.stream()
                .flatMap(t -> extractOwnedFiles(t).stream())
                .map(this::simpleFileName)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        Set<String> pendingPackagePrefixes = pendingTasks.stream()
                .flatMap(t -> extractOwnedFiles(t).stream())
                .map(this::toPackagePrefix)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> ownedSimpleNames = ownedFiles.stream()
                .map(this::simpleFileName)
                .collect(Collectors.toSet());

        int total = 0, owned = 0, cascading = 0, fixable = 0, structural = 0, stubs = 0;
        boolean borderline = false;

        for (String line : errorOutput.split("\n")) {
            if (line.isBlank()) continue;
            if (!isErrorLine(line)) continue;
            total++;

            ErrorType type = classifyLine(line, ownedSimpleNames, pendingFileSimpleNames, pendingPackagePrefixes);
            switch (type) {
                case CASCADING -> cascading++;
                case STUB -> stubs++;
                case MISSING_IMPORT, SYNTAX, TYPE_MISMATCH_SIMPLE -> { owned++; fixable++; }
                case LOGIC, MISSING_FILE -> { owned++; structural++; }
                case API_MISMATCH -> { owned++; borderline = true; }
                case NON_OWNED -> { /* not counted as owned */ }
                case UNCLEAR -> { owned++; borderline = true; }
            }
        }

        boolean allNonOwned = (owned == 0) && (total > 0);
        return new ErrorClassification(total, owned, cascading, fixable, structural, stubs,
                allNonOwned, borderline);
    }

    @Deprecated(since = "v2")
    private ErrorType classifyLine(String line, Set<String> ownedSimpleNames,
                                    Set<String> pendingFileSimpleNames,
                                    Set<String> pendingPackagePrefixes) {
        String lower = line.toLowerCase();

        // TypeScript cascading codes
        for (String code : CASCADING_TS_CODES) {
            if (line.contains(code)) return ErrorType.CASCADING;
        }

        // STUB placeholder
        if (line.contains("STUB") && (line.contains("UnsupportedOperationException") || line.contains("not implemented"))) {
            return ErrorType.STUB;
        }

        // Java "cannot find symbol: class X" — check if X is a pending task's file
        Matcher symbolMatcher = Pattern.compile("cannot find symbol.*class\\s+(\\w+)").matcher(lower);
        if (symbolMatcher.find()) {
            String className = symbolMatcher.group(1);
            // Check against pending task file simple names (case-insensitive class name match)
            boolean isPending = pendingFileSimpleNames.stream()
                    .anyMatch(f -> f.toLowerCase().replace(".java", "")
                            .replace(".ts", "").replace(".py", "").equals(className));
            if (isPending) return ErrorType.CASCADING;
        }

        // Java "package X does not exist"
        Matcher pkgMatcher = Pattern.compile("package\\s+([\\w.]+)\\s+does not exist").matcher(lower);
        if (pkgMatcher.find()) {
            String pkg = pkgMatcher.group(1);
            boolean isPending = pendingPackagePrefixes.stream().anyMatch(pkg::startsWith);
            if (isPending) return ErrorType.CASCADING;
        }

        // Python ModuleNotFoundError — check against pending files
        if (lower.contains("modulenotfounderror") || lower.contains("no module named")) {
            Matcher modMatcher = Pattern.compile("no module named '([\\w.]+)'").matcher(lower);
            if (modMatcher.find()) {
                String moduleName = modMatcher.group(1).replace(".", "/");
                boolean isPending = pendingFileSimpleNames.stream()
                        .anyMatch(f -> f.toLowerCase().replace(".py", "").equals(moduleName));
                if (isPending) return ErrorType.CASCADING;
            }
            return ErrorType.CASCADING; // conservative: unknown module → assume cascading
        }

        // Determine if the error is in an owned file
        boolean inOwnedFile = ownedSimpleNames.stream().anyMatch(line::contains);

        if (!inOwnedFile) return ErrorType.NON_OWNED;

        // Owned file — classify by error type
        if (lower.contains("';' expected") || lower.contains("')' expected") ||
                lower.contains("'}' expected") || lower.contains("'(' expected") ||
                lower.contains("reached end of file") || lower.contains("illegal start of")) {
            return ErrorType.SYNTAX;
        }
        if (lower.contains("cannot find symbol") || lower.contains("does not exist") ||
                lower.contains("cannot be resolved")) {
            return ErrorType.MISSING_IMPORT;
        }
        if (lower.contains("incompatible types") || lower.contains("type mismatch") ||
                lower.contains("is not assignable to")) {
            return ErrorType.TYPE_MISMATCH_SIMPLE;
        }
        if (lower.contains("abstract method") || lower.contains("must implement") ||
                lower.contains("is not abstract") || lower.contains("no implementation")) {
            return ErrorType.LOGIC;
        }
        if (lower.contains("no such file") || lower.contains("file not found")) {
            return ErrorType.MISSING_FILE;
        }
        if (lower.contains("method") && (lower.contains("not applicable") || lower.contains("undefined"))) {
            return ErrorType.API_MISMATCH;
        }

        return ErrorType.UNCLEAR;
    }

    @Deprecated(since = "v2")
    private boolean isErrorLine(String line) {
        return line.contains("error:") || line.contains(" ERROR ")
                || Pattern.matches(".*\\.(java|ts|js|tsx|jsx|py):\\d+.*", line)
                || line.startsWith("[ERROR]");
    }

    // ── Rule-based decision tree ──────────────────────────────────────────────

    @Deprecated(since = "v2")
    private TaskCompileDecision applyRules(ErrorClassification c, String errorOutput, List<String> ownedFiles) {
        int total = c.totalErrorCount();
        int owned = c.ownedErrorCount();
        int cascading = c.cascadingErrorCount();

        // Rule 1: No owned errors → CONTINUE_WITH_WARNING
        if (owned == 0 && total > 0) {
            return new TaskCompileDecision(
                    SupervisionAction.CONTINUE_WITH_WARNING,
                    total, 0, cascading, 100,
                    "All " + total + " compile error(s) are in non-owned files.",
                    "This task's files compiled cleanly. Errors belong to other tasks.",
                    null, null);
        }

        // Rule 2: All errors are cascading → PASS
        if (cascading >= total && !c.hasBorderlineCase() && total > 0) {
            return new TaskCompileDecision(
                    SupervisionAction.PASS,
                    total, 0, cascading, 100,
                    "All " + total + " error(s) are cascading from unmerged sibling tasks.",
                    "These errors will self-resolve once sibling tasks merge. No revert needed.",
                    null, null);
        }

        // Rule 3: Zero total errors (shouldn't reach here but defensive)
        if (total == 0) {
            return new TaskCompileDecision(
                    SupervisionAction.PASS,
                    0, 0, 0, 100,
                    "No compile errors detected.",
                    "Compile check passed.", null, null);
        }

        int score = computeFixabilityScore(c);

        // Rule 4: Highly fixable, few owned errors → AUTO_FIX
        if (score >= 80 && owned <= 5 && !c.hasBorderlineCase()) {
            return new TaskCompileDecision(
                    SupervisionAction.AUTO_FIX,
                    total, owned, cascading, score,
                    owned + " owned error(s) are mechanical and auto-fixable (fixability: " + score + "/100).",
                    "Score=" + score + ", ownedErrors=" + owned + " — targeted patch safe without worker re-run.",
                    buildAutoFixHint(errorOutput, ownedFiles), null);
        }

        // Rule 5: Moderate fixability → AUTO_FIX (will escalate if patch fails)
        if (score >= 60 && owned <= 10 && !c.hasBorderlineCase()) {
            return new TaskCompileDecision(
                    SupervisionAction.AUTO_FIX,
                    total, owned, cascading, score,
                    owned + " owned error(s) with moderate fixability (score: " + score + "/100).",
                    "Attempting auto-fix; will escalate to RETRY_TASK if patch is insufficient.",
                    buildAutoFixHint(errorOutput, ownedFiles), null);
        }

        // Rule 6: Low fixability or many owned errors → RETRY_TASK
        if ((score < 60 || owned > 10) && !c.hasBorderlineCase()) {
            return new TaskCompileDecision(
                    SupervisionAction.RETRY_TASK,
                    total, owned, cascading, score,
                    owned + " structural error(s) in owned files require re-implementation.",
                    "fixabilityScore=" + score + ", ownedErrors=" + owned + " — worker re-run with targeted feedback.",
                    null, buildRetryFeedback(errorOutput));
        }

        // Borderline: return null to trigger LLM decision
        return null;
    }

    @Deprecated(since = "v2")
    private int computeFixabilityScore(ErrorClassification c) {
        if (c.ownedErrorCount() == 0) return 100;
        int base = (int) (100.0 * c.fixableCount() / c.ownedErrorCount());
        int structuralPenalty = Math.min(40, c.structuralCount() * 10);
        int countPenalty = c.ownedErrorCount() > 5 ? Math.min(20, (c.ownedErrorCount() - 5) * 4) : 0;
        return Math.max(0, base - structuralPenalty - countPenalty);
    }

    // ── LLM staff-engineer decision ───────────────────────────────────────────

    @Deprecated(since = "v2")
    private TaskCompileDecision callLlmDecision(PipelineRun run, AgentTask task,
                                                  List<String> ownedFiles, String errorOutput,
                                                  List<AgentTask> pendingTasks,
                                                  Set<String> mergedTaskIds) {
        String runId = run.getRunId();
        log.info("[Supervisor] Borderline case for task {} — consulting LLM (PLANNING tier)", task.getTaskId());

        try {
            ResolvedModel model = modelResolver.resolve("TaskCompileSupervisor", run.getAiProviderEnum());

            String pendingFilesCtx = pendingTasks.stream()
                    .map(t -> t.getTaskId() + ": " + extractOwnedFiles(t))
                    .collect(Collectors.joining("\n"));

            String systemPrompt = promptAssembler.assemble(PROMPT_PATH, Map.of());
            String userMessage = buildLlmUserMessage(task, ownedFiles, errorOutput,
                    pendingFilesCtx, mergedTaskIds);

            AnthropicChatOptions opts = AnthropicChatOptions.builder()
                    .temperature(0.0)
                    .model(model.getModel())
                    .build();
            Prompt prompt = new Prompt(
                    List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)), opts);

            LlmRouter.LlmResponse lr = llmRouter.callWithFallbackTracked(
                    model.providerName(), model.getModel(),
                    prompt, runId, "task-compile-supervisor",
                    LlmRouter.FallbackMode.IMMEDIATE, 0, false);

            // Track tokens
            try {
                int inTok = lr.response().getMetadata().getUsage().getPromptTokens().intValue();
                int outTok = lr.response().getMetadata().getUsage().getCompletionTokens().intValue();
                stateStore.addTokensToRun(runId, inTok, outTok);
                stateStore.setAgentTokens(runId, "task-compile-supervisor", inTok, outTok,
                        lr.provider(), lr.model());
            } catch (Exception ex) {
                log.warn("[Supervisor] Could not extract token counts: {}", ex.getMessage());
            }

            return parseLlmDecision(lr.response().getResult().getOutput().getText(), errorOutput, ownedFiles);

        } catch (Exception e) {
            log.warn("[Supervisor] LLM decision failed for task {} — defaulting to RETRY_TASK: {}",
                    task.getTaskId(), e.getMessage());
            return new TaskCompileDecision(
                    SupervisionAction.RETRY_TASK, 0, 0, 0, 0,
                    "LLM decision unavailable — falling back to retry.",
                    "LLM call failed: " + e.getMessage(),
                    null, errorOutput.length() > 2000 ? errorOutput.substring(0, 2000) : errorOutput);
        }
    }

    @Deprecated(since = "v2")
    private TaskCompileDecision parseLlmDecision(String rawResponse, String errorOutput,
                                                   List<String> ownedFiles) {
        try {
            String json = LlmResponseCleaner.extractJsonObject(rawResponse);
            if (json == null) throw new IllegalStateException("No JSON object in response");

            JsonNode node = mapper.readTree(json);
            String actionStr = node.path("action").asText("RETRY_TASK").toUpperCase().trim();
            SupervisionAction action;
            try {
                action = SupervisionAction.valueOf(actionStr);
            } catch (IllegalArgumentException ex) {
                action = SupervisionAction.RETRY_TASK;
            }

            int fixability = node.path("fixability_score").asInt(50);
            String assessment = node.path("assessment").asText("(no assessment)");
            String rationale = node.path("rationale").asText("(no rationale)");
            String autoFix = node.path("auto_fix_instructions").asText(null);
            String retry = node.path("retry_instructions").asText(null);
            if ("null".equals(autoFix)) autoFix = null;
            if ("null".equals(retry)) retry = null;

            // Ensure instructions are populated when required
            if (action == SupervisionAction.AUTO_FIX && (autoFix == null || autoFix.isBlank())) {
                autoFix = buildAutoFixHint(errorOutput, ownedFiles);
            }
            if (action == SupervisionAction.RETRY_TASK && (retry == null || retry.isBlank())) {
                retry = buildRetryFeedback(errorOutput);
            }

            return new TaskCompileDecision(action, 0, 0, 0, fixability,
                    assessment, rationale, autoFix, retry);

        } catch (Exception e) {
            log.warn("[Supervisor] Could not parse LLM decision JSON — defaulting to RETRY_TASK: {}", e.getMessage());
            return new TaskCompileDecision(
                    SupervisionAction.RETRY_TASK, 0, 0, 0, 0,
                    "Could not parse LLM decision.",
                    "JSON parse error: " + e.getMessage(),
                    null, buildRetryFeedback(errorOutput));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Deprecated(since = "v2")
    private List<String> extractOwnedFiles(AgentTask task) {
        if (task.getTaskJson() == null) return List.of();
        try {
            TaskDefinition def = mapper.readValue(task.getTaskJson(), TaskDefinition.class);
            return def.getOwnedFiles() != null ? def.getOwnedFiles() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Deprecated(since = "v2")
    private AgentTask buildSyntheticTaskWithFiles(AgentTask parent, List<String> aggregatedOwnedFiles) {
        // Build a minimal task JSON that carries the aggregated owned files
        try {
            TaskDefinition def = mapper.readValue(parent.getTaskJson(), TaskDefinition.class);
            def.setOwnedFiles(aggregatedOwnedFiles);
            AgentTask synthetic = new AgentTask();
            synthetic.setTaskId(parent.getTaskId());
            synthetic.setRunId(parent.getRunId());
            synthetic.setStatus(parent.getStatus());
            synthetic.setTaskJson(mapper.writeValueAsString(def));
            return synthetic;
        } catch (Exception e) {
            return parent;
        }
    }

    @Deprecated(since = "v2")
    private String simpleFileName(String filePath) {
        if (filePath == null) return "";
        int slash = filePath.lastIndexOf('/');
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    @Deprecated(since = "v2")
    private String toPackagePrefix(String filePath) {
        if (filePath == null) return null;
        Matcher m = Pattern.compile("src/main/java/(.+)/[^/]+\\.java").matcher(filePath);
        if (m.find()) return m.group(1).replace('/', '.');
        return null;
    }

    @Deprecated(since = "v2")
    private boolean isPending(String status) {
        return "PENDING".equals(status) || "LOCKED".equals(status) || "WORKING".equals(status);
    }

    @Deprecated(since = "v2")
    private String buildAutoFixHint(String errorOutput, List<String> ownedFiles) {
        String truncated = errorOutput.length() > 1500
                ? errorOutput.substring(0, 1500) + "..." : errorOutput;
        return "Fix compile errors in owned files " + ownedFiles + ":\n" + truncated;
    }

    @Deprecated(since = "v2")
    private String buildRetryFeedback(String errorOutput) {
        String truncated = errorOutput.length() > 2000
                ? errorOutput.substring(0, 2000) + "..." : errorOutput;
        return "Post-merge compile check failed. Fix the following errors in your owned files:\n" + truncated;
    }

    @Deprecated(since = "v2")
    private String buildLlmUserMessage(AgentTask task, List<String> ownedFiles,
                                        String errorOutput, String pendingFilesCtx,
                                        Set<String> mergedTaskIds) {
        String truncatedErrors = errorOutput.length() > 3000
                ? errorOutput.substring(0, 3000) + "..." : errorOutput;
        return "TASK: " + task.getTitle() + "\n"
                + "TASK ID: " + task.getTaskId() + "\n"
                + "OWNED FILES: " + ownedFiles + "\n\n"
                + "COMPILE ERRORS:\n" + truncatedErrors + "\n\n"
                + "ALREADY MERGED TASK IDs:\n" + String.join(", ", mergedTaskIds) + "\n\n"
                + "PENDING TASKS (not yet merged) with their owned files:\n" + pendingFilesCtx;
    }

    @Deprecated(since = "v2")
    private void emitDecisionEvent(String runId, String taskId, TaskCompileDecision decision) {
        String msg = switch (decision.action()) {
            case PASS -> "📋 Compile supervisor: PASS — " + decision.assessment();
            case CONTINUE_WITH_WARNING ->
                    "⚠️ Compile supervisor: cascading errors (" + decision.cascadingErrorCount()
                    + "/" + decision.totalErrors() + " from pending tasks) — continuing";
            case AUTO_FIX ->
                    "🔧 Compile supervisor: AUTO_FIX (fixability: " + decision.fixabilityScore()
                    + "/100) — " + decision.assessment();
            case RETRY_TASK ->
                    "🔄 Compile supervisor: RETRY_TASK (fixability: " + decision.fixabilityScore()
                    + "/100) — " + decision.assessment();
        };
        broadcaster.emit(runId, RunEvent.log(runId, taskId, msg));
        log.info("[Supervisor] task={} action={} fixability={} owned={} cascading={} | {}",
                taskId, decision.action(), decision.fixabilityScore(),
                decision.ownedErrorCount(), decision.cascadingErrorCount(), decision.rationale());
    }

    @Deprecated(since = "v2")
    private void recordMetric(SupervisionAction action) {
        Counter.builder("executespec.supervisor.decision")
                .tag("action", action.name())
                .description("Task compile supervisor decision distribution")
                .register(meterRegistry)
                .increment();
    }
}
