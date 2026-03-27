package com.navneet.executespec.api.controller;

import com.navneet.executespec.agent.ApplySuggestionTooLargeException;
import com.navneet.executespec.agent.ConversationAgent;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.api.dto.RunSummary;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.harness.PipelineHarness;
import com.navneet.executespec.state.ApplySource;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for post-run AI conversation and iteration.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>Conversation is only available on COMPLETE runs.</li>
 *   <li>Capped at {@link ConversationAgent#MAX_MESSAGES} user messages
 *       (each user message + assistant reply pair consumes 2 of the chat_message_count).</li>
 *   <li>Creating an iteration run copies the source workspace and starts a full new pipeline.</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/runs/{runId}/chat}      — return the full conversation history</li>
 *   <li>{@code POST /api/runs/{runId}/chat}      — send a user message, receive AI reply</li>
 *   <li>{@code POST /api/runs/{runId}/iterate}   — create a new run from this run's codebase</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class RunConversationController {

    private final StateStore stateStore;
    private final ConversationAgent conversationAgent;
    private final PipelineHarness harness;
    private final RunMapper runMapper;
    private final RunAccessGuard guard;
    private final TierPermissionService tierPermissionService;
    private final TierEnforcementService tierEnforcementService;

    // ── GET /api/runs/{runId}/chat ────────────────────────────────────────────

    /**
     * Returns the full conversation history for the given run.
     * Each entry has {@code role} ("user" | "assistant") and {@code content}.
     * Returns an empty array if no conversation has started yet.
     */
    @GetMapping("/{runId}/chat")
    public ResponseEntity<List<Map<String, String>>> getChatHistory(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        PipelineRun run = guard.requireRunAccess(runId, userDetails);

        List<Map<String, String>> history = stateStore.getChatHistory(runId);
        return ResponseEntity.ok(history);
    }

    // ── POST /api/runs/{runId}/chat ───────────────────────────────────────────

    /**
     * Sends a user message and returns the assistant reply.
     *
     * <p>Request body: {@code {"message": "..."}}
     *
     * <p>Response: {@code {"reply": "...", "messageCount": <n>, "maxMessages": <m>}}
     *
     * <p>Returns {@code 429 Too Many Requests} when the conversation limit is reached.
     */
    @PostMapping("/{runId}/chat")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @PathVariable String runId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        PipelineRun run = guard.requireRunAccess(runId, userDetails);

        // Tier gate: only PRO+ org members and admins can use post-run chat
        var perms = tierPermissionService.getPermissions(userDetails.getUser().getId());
        if (!perms.canUseChat()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Post-run chat is not available on your current plan. Upgrade to PRO or ENTERPRISE.");
        }

        // Allow chat on completed runs and at review/merge gates
        var chatStatuses = java.util.Set.of("COMPLETE", "PARTIAL", "REVIEWING");
        if (!chatStatuses.contains(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Conversation is only available for completed or reviewing runs");
        }

        // Count existing user messages (chat_message_count includes both user + assistant)
        int userMessages = run.getChatMessageCount() / 2;
        if (userMessages >= ConversationAgent.MAX_MESSAGES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Conversation limit reached (" + ConversationAgent.MAX_MESSAGES + " messages). " +
                "Click 'Implement as New Run' to continue with a new run.");
        }

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be blank");
        }

        // Retrieve existing history
        List<Map<String, String>> rawHistory = stateStore.getChatHistory(runId);
        List<ConversationAgent.ChatMessageRecord> history = rawHistory.stream()
            .map(m -> new ConversationAgent.ChatMessageRecord(
                m.getOrDefault("role", "user"),
                m.getOrDefault("content", "")))
            .toList();

        log.info("[Conversation] run={} userMsg={} historySize={}", runId, message.length(), history.size());

        // Call the LLM
        String reply = conversationAgent.chat(run, history, message);

        // Persist both the user message and the assistant reply
        stateStore.saveChatMessage(runId, "user", message);
        stateStore.saveChatMessage(runId, "assistant", reply);

        // Reload to get fresh message count
        PipelineRun updated = stateStore.findRun(runId).orElse(run);
        int newUserMessages = updated.getChatMessageCount() / 2;

        return ResponseEntity.ok(Map.of(
            "reply", reply,
            "messageCount", newUserMessages,
            "maxMessages", ConversationAgent.MAX_MESSAGES
        ));
    }

    // ── POST /api/runs/{runId}/apply ──────────────────────────────────────────

    /**
     * Applies a suggestion (from Chat Copilot or Review) to the same run by
     * generating a mini task plan, appending it to the existing plan, and
     * executing only the new tasks.
     *
     * <p>Request body:
     * <ul>
     *   <li>{@code suggestion} — the improvement text (required for COPILOT, ignored for REVIEW)</li>
     *   <li>{@code source} — "COPILOT" or "REVIEW" (default: COPILOT)</li>
     * </ul>
     *
     * <p>Response 200: {@code {"taskCount": 2, "tasks": [{"taskId":"...","title":"..."}]}}
     * <p>Response 422: {@code {"error": "too_many_tasks", "compiledGoal": "..."}}
     * <p>Response 429: Apply limit exceeded
     */
    @PostMapping("/{runId}/apply")
    public ResponseEntity<Map<String, Object>> applySuggestion(
            @PathVariable String runId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        String userId = userDetails.getUser().getId();

        // Tier gate: only PRO+ org members and admins can apply suggestions
        var perms = tierPermissionService.getPermissions(userId);
        if (!perms.canUseChat()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apply suggestion is not available on your current plan. Upgrade to PRO or ENTERPRISE.");
        }

        // Allow on completed runs and at review/merge gates
        var applyStatuses = java.util.Set.of("COMPLETE", "PARTIAL", "REVIEWING");
        if (!applyStatuses.contains(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Apply is only available for completed or reviewing runs");
        }

        // Check no tasks are currently executing
        List<AgentTask> activeTasks = stateStore.getTasksForRun(runId).stream()
                .filter(t -> "LOCKED".equals(t.getStatus()) || "WORKING".equals(t.getStatus()))
                .toList();
        if (!activeTasks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot apply while tasks are still executing");
        }

        // Tier limit check (shared counter for both COPILOT and REVIEW)
        tierEnforcementService.assertCanApplySuggestion(userId, run);

        // Determine source
        String sourceStr = body.getOrDefault("source", "COPILOT").toUpperCase();
        ApplySource source;
        try {
            source = ApplySource.valueOf(sourceStr);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source: " + sourceStr);
        }

        // Resolve suggestion text
        String suggestion;
        if (source == ApplySource.REVIEW) {
            suggestion = conversationAgent.extractReviewSuggestion(run);
            if (suggestion == null || suggestion.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No actionable review issues found to apply.");
            }
        } else {
            suggestion = body.get("suggestion");
            if (suggestion == null || suggestion.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suggestion must not be blank");
            }
        }

        // Generate mini-plan
        TaskPlan miniPlan;
        try {
            miniPlan = conversationAgent.generateSuggestionPlan(run, suggestion);
        } catch (ApplySuggestionTooLargeException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "too_many_tasks",
                    "compiledGoal", e.getCompiledGoal()
            ));
        }

        // Increment plan version first, then save — avoids duplicate key on (run_id, version)
        stateStore.incrementPlanVersion(runId);
        stateStore.savePlanVersion(runId, source == ApplySource.COPILOT ? "COPILOT" : "REVIEW", userId);

        // Update run goal text with apply identifier so the update is visible
        int applyIndex = run.getApplySuggestionCount() + 1;
        String applyLabel = "\n\n--- Applied Improvement #" + applyIndex + " (" + source + ") ---\n" + suggestion;
        run.setUserRequest(run.getUserRequest() + applyLabel);
        stateStore.save(run);

        // Append tasks to existing plan
        List<AgentTask> newTasks = stateStore.appendSuggestionTasks(runId, miniPlan, applyIndex, source);
        stateStore.incrementApplySuggestionCount(runId);

        // Collect task IDs for execution
        List<String> newTaskIds = newTasks.stream().map(AgentTask::getTaskId).toList();

        // Kick off async execution
        harness.executeApplySuggestion(runId, newTaskIds);

        // Build response
        List<Map<String, String>> taskSummaries = newTasks.stream()
                .map(t -> Map.of("taskId", t.getTaskId(), "title", t.getTitle()))
                .toList();

        log.info("[Apply] run={} source={} tasks={} applyIndex={}", runId, source, newTasks.size(), applyIndex);

        return ResponseEntity.ok(Map.of(
                "taskCount", newTasks.size(),
                "tasks", taskSummaries
        ));
    }

    // ── POST /api/runs/{runId}/iterate ────────────────────────────────────────

    /**
     * Creates a new pipeline run that starts from the source run's codebase.
     *
     * <p>The new run's workspace is populated by copying the tracked files from the source
     * run's workspace (via {@link com.navneet.executespec.harness.WorkspaceInitService#copyFromRun}).
     * The normal pipeline then starts: plan → approve → workers → review → merge.
     *
     * <p>Request body: {@code {"userRequest": "Add dark mode support"}}
     *
     * <p>Response: {@code {"runId": "..."}} — the new run's ID.
     */
    @PostMapping("/{runId}/iterate")
    public ResponseEntity<Map<String, String>> iterateRun(
            @PathVariable String runId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        PipelineRun sourceRun = guard.requireRunAccess(runId, userDetails);

        if (!"COMPLETE".equals(sourceRun.getStatus()) && !"PARTIAL".equals(sourceRun.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Iteration is only available for COMPLETE or PARTIAL runs");
        }

        String userRequest = body.get("userRequest");
        if (userRequest == null || userRequest.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userRequest must not be blank");
        }

        // Create the iteration run in the DB (sets sourceRunId = sourceRun.getRunId())
        PipelineRun newRun = stateStore.createIterationRun(sourceRun, userRequest);

        // Set ownership from caller identity
        newRun.setCreatedByUserId(userDetails.getUser().getId());
        newRun.setOrgId(userDetails.getUser().getOrgId());
        stateStore.save(newRun);

        // Kick off the async pipeline (workspace copy happens inside executeRunAsync)
        harness.startIterationRun(newRun.getRunId(), userRequest);

        log.info("[Conversation] Iteration run {} created from source run {}", newRun.getRunId(), runId);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("runId", newRun.getRunId()));
    }
}
