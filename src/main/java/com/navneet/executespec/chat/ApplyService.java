package com.navneet.executespec.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.integration.scm.ScmPushService;
import com.navneet.executespec.reviewer.PostEditFormatter;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 apply-suggestion path. Runs a single-task {@link WorkerAgent} session against the existing
 * workspace of a TERMINAL run, using the user's suggestion as the task description. Worker has full
 * tool access (readFile, writeFile, editFile, shell, git*, buildProject, completeTask) so it can
 * actually patch code — unlike {@link ChatService} which is read-only Q&A.
 *
 * <p>Differences from a fresh run:
 *
 * <ul>
 *   <li>No planner — the user-provided suggestion IS the plan
 *   <li>No per-task retry / reviewer / validation — single invocation
 *   <li>Appends a git commit with a {@code copilot:} / {@code review:} prefix so the provenance is
 *       obvious in the branch history
 *   <li>Mutates the existing {@code pipeline_run.workspace_path} — no new run id
 * </ul>
 *
 * <p>Limits (2026-06-06): 90 iterations base cap (was 60 — still too tight for non-frontier models
 * applying multi-file suggestions in an existing workspace). Project-scoped runs (workspace
 * contains {@code repos/<name>/} subdirs) get the cap doubled to 180, mirroring {@code
 * SequentialExecutor.tierConfigFor}'s "project runs need 2× the fresh-scratch budget because every
 * readFile/writeFile crosses a directory boundary" rule. Workspace-level concurrency is the
 * caller's responsibility — if two Applies fire on the same run, git may throw dirty-index errors;
 * that's acceptable for now since the UI only surfaces one button at a time.
 *
 * <p>Intentionally NOT annotated {@code @Transactional}. Callers dispatch async (see {@code
 * RunConversationController.applyExecutor}) and the inner JPA calls manage their own short tx scope
 * — wrapping the worker loop in an outer tx would pin a Hikari connection for the entire worker
 * run.
 *
 * <p>Lifecycle state (Fix I): every invocation writes to {@link ApplyResultStore} (Redis, 1h TTL)
 * so the UI can poll {@code GET /api/runs/{id}/apply/status} after the original POST response
 * stream is severed (broken pipe, browser navigation, Monaco crash, etc.). Without this, the
 * a076c876 apply hit ITERATION_LIMIT_EXCEEDED in the background but the user had no way to learn
 * the outcome because the response channel had died at the 3-second mark.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplyService {

  /** Base iter cap — single-repo / scratch-workspace applies. */
  static final int APPLY_BASE_ITER_CAP = 90;

  /** Multiplier for project-scoped applies (multi-repo / large existing codebase). */
  static final int APPLY_PROJECT_MULTIPLIER = 2;

  private final PipelineRunRepository runRepo;
  private final WorkerAgent worker;
  private final ModelResolver modelResolver;
  private final AgentProperties agentProps;
  private final ApplyResultStore resultStore;
  private final ScmPushService scmPushService;
  private final PostEditFormatter postEditFormatter;

  public record ApplyResult(
      int taskCount, List<TaskSummary> tasks, String workerOutcome, String summary) {}

  public record TaskSummary(String taskId, String title) {}

  /**
   * @param runId completed run to patch in-place
   * @param suggestion free-form instruction from the user (from chat copilot, review panel, or
   *     manual input)
   * @param source "COPILOT" or "REVIEW" — used in the commit message prefix and in logs
   */
  public ApplyResult apply(String runId, String suggestion, String source) {
    if (suggestion == null || suggestion.isBlank()) {
      throw new IllegalArgumentException("suggestion is required");
    }
    PipelineRun run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!isTerminal(run.getStatus())) {
      throw new IllegalStateException(
          "Apply only available for terminal runs (current: " + run.getStatus() + ")");
    }

    Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!workspace.toFile().isDirectory()) {
      throw new IllegalStateException("Workspace missing on disk at " + workspace);
    }

    LlmProvider provider;
    try {
      provider =
          run.getAiProvider() == null
              ? LlmProvider.ANTHROPIC
              : LlmProvider.valueOf(run.getAiProvider().toUpperCase());
    } catch (IllegalArgumentException ex) {
      provider = LlmProvider.ANTHROPIC;
    }
    ResolvedModel model;
    try {
      model = modelResolver.resolve("ApplyService", provider, AgentTier.WORKHORSE);
    } catch (Exception e) {
      log.warn(
          "ApplyService model resolve failed for provider={}: {} — falling back to ANTHROPIC",
          provider,
          e.getMessage());
      provider = LlmProvider.ANTHROPIC;
      model = modelResolver.resolve("ApplyService", provider, AgentTier.WORKHORSE);
    }

    String taskId = "APPLY-" + System.currentTimeMillis();
    String commitPrefix = "REVIEW".equalsIgnoreCase(source) ? "review" : "copilot";
    String title = firstLine(suggestion, 60);
    int iterCap =
        run.getProjectId() != null
            ? APPLY_BASE_ITER_CAP * APPLY_PROJECT_MULTIPLIER
            : APPLY_BASE_ITER_CAP;

    ExecutionContext ctx = new ExecutionContext(runId, taskId, workspace);

    // Stamp lifecycle state to Redis so the UI can poll the outcome even if the
    // POST response stream dies (broken pipe, Monaco crash, browser navigation).
    ApplyResultStore.ApplyState pending =
        ApplyResultStore.ApplyState.running(runId, taskId, commitPrefix, model.getModel());
    resultStore.put(pending);

    String systemPrompt =
        """
        You are applying a code change to an already-generated project. The workspace
        is at /app (your tools see it as workspace root). Every source file that the
        previous run produced is already on disk and committed. Your job:

        1. Read only what you must to understand where the change belongs.
        2. Make the MINIMAL set of edits that satisfy the suggestion.
        3. Do NOT restructure code, do NOT rename existing files, do NOT introduce
           new dependencies unless the suggestion explicitly calls for them.
        4. When done, call completeTask with a one-paragraph summary of what you
           changed and which files you touched. Do not run the build yourself — the
           caller handles that.

        DO NOT run linters, formatters, or test suites on the apply path. CI will
        handle those after the push. Concretely: do NOT run `black`, `ruff`,
        `prettier`, `eslint`, `gofmt`, `rustfmt`, `pytest`, `npm test`, `vitest`,
        `mvn test`, `gradle test` — most of them are blocked by the shell sandbox
        anyway, and the ones that aren't only burn iterations. Edit files; call
        completeTask. The downstream push step pushes to the existing PR branch;
        CI on that PR is the authoritative quality gate.

        DO NOT call gitCommit or run git mutations (`git add`, `git commit`,
        `git push`, `git reset`, `git stash`). The push happens automatically
        after completeTask via ScmPushService — your changes go to the existing
        PR branch on GitHub. Trying to commit yourself just confuses git state.

        Safety:
        - Never delete files outside what the suggestion calls for.
        - If the suggestion is ambiguous, make the narrowest reasonable interpretation
          and note it in your completion summary.
        """;

    String userPrompt = "Suggestion source: " + commitPrefix + "\n\n" + suggestion;

    WorkerTaskRequest req =
        WorkerTaskRequest.builder()
            .provider(provider.name().toLowerCase())
            .model(model.getModel())
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt)
            .context(ctx)
            .maxIterations(iterCap)
            .temperature(0.0)
            .build();

    log.info(
        "ApplyService run={} source={} model={} iterCap={} suggestionPreview={}",
        runId,
        commitPrefix,
        model.getModel(),
        iterCap,
        preview(suggestion, 100));

    WorkerResult result;
    try {
      result = worker.run(req);
    } catch (Exception e) {
      log.error("ApplyService run={} worker failed: {}", runId, e.getMessage(), e);
      resultStore.put(pending.toFailed(e.getClass().getSimpleName() + ": " + e.getMessage()));
      throw new RuntimeException("Apply worker failed: " + e.getMessage(), e);
    }

    // 2026-05-13 audit Fix-A — run the same PostEditFormatter pass that the BUILD-mode
    // reviewer runs before push. Without this, chat-driven applies on Python / JS / TS
    // repos pushed unformatted code straight to the existing PR branch, and the
    // customer's CI (`black --check`, `prettier --check`) immediately turned red — making
    // the apply look like it "didn't fix anything". The worker prompt above explicitly
    // forbids the worker from invoking formatters (they burn iters and most are sandbox-
    // blocked anyway), so the formatter pass has to happen out-of-band, post-worker.
    // Idempotent + never throws — safe to call regardless of worker outcome.
    try {
      PostEditFormatter.Result fmt = postEditFormatter.run(workspace, runId);
      if (fmt.filesReformatted() > 0 || !fmt.errors().isEmpty()) {
        log.info(
            "ApplyService run={} post-edit formatter — reformatted={} formatters={} errors={}",
            runId,
            fmt.filesReformatted(),
            fmt.formattersRun(),
            fmt.errors());
      }
    } catch (Exception fmtEx) {
      log.warn(
          "ApplyService run={} post-edit formatter threw (continuing to push): {}",
          runId,
          fmtEx.getMessage());
    }

    // Fix L (2026-05-03) — push apply changes to each linked repo's existing PR
    // branch (or executespec/<runIdShort> when no PR is open). The previous
    // `tryGitCommit` shelled `git add` + `git commit` at the WORKSPACE root, but
    // each `repos/<name>/` is its OWN nested git that the wrapping git treats as
    // a submodule (gitlink). The outer commit recorded only submodule SHAs; the
    // inner repo working trees stayed dirty + were never pushed, so the apply
    // never reached GitHub. With pushApplyChanges → per-repo
    // GitTool.pushToExistingBranch (fast-forward only, no PR create), the
    // existing PR picks up the new commit naturally.
    String commitMsg = commitPrefix + ": " + (title.isBlank() ? "apply user suggestion" : title);
    ScmPushService.ApplyPushResult pushResult;
    try {
      pushResult = scmPushService.pushApplyChanges(run, commitMsg);
    } catch (Exception e) {
      log.warn("ApplyService push failed for run={}: {}", runId, e.getMessage());
      pushResult =
          ScmPushService.ApplyPushResult.skipped(
              "push exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String summary =
        "Worker outcome: "
            + result.outcome()
            + " · iterations: "
            + result.iterations()
            + " · tool calls: "
            + result.toolCallsDispatched()
            + " · "
            + summarizePushResult(pushResult);

    if (result.outcome() != WorkerResult.Outcome.COMPLETED) {
      log.warn(
          "ApplyService run={} non-clean outcome: {} ({} iterations, {} tool calls)",
          runId,
          result.outcome(),
          result.iterations(),
          result.toolCallsDispatched());
    }

    // Persist the terminal lifecycle state so the polling endpoint can return it.
    resultStore.put(
        pending.toCompleted(
            result.outcome().name(), summary, result.iterations(), result.toolCallsDispatched()));

    return new ApplyResult(
        1, List.of(new TaskSummary(taskId, title)), result.outcome().name(), summary);
  }

  private static boolean isTerminal(String s) {
    return "COMPLETE".equals(s)
        || "FAILED".equals(s)
        || "CANCELLED".equals(s)
        || "PARTIAL".equals(s);
  }

  private static String firstLine(String s, int cap) {
    if (s == null) return "";
    int nl = s.indexOf('\n');
    String first = (nl < 0) ? s : s.substring(0, nl);
    first = first.trim();
    if (first.length() > cap) first = first.substring(0, cap - 1) + "…";
    return first;
  }

  private static String preview(String s, int cap) {
    if (s == null) return "";
    String cleaned = s.replace('\n', ' ');
    if (cleaned.length() > cap) cleaned = cleaned.substring(0, cap - 1) + "…";
    return cleaned;
  }

  /**
   * Render an {@link ScmPushService.ApplyPushResult} into a one-line summary suitable for the apply
   * summary surface. Multi-repo runs collapse to a count of OK/CLEAN/FAIL repos so the line stays
   * bounded; per-repo detail goes into structured logs upstream.
   */
  static String summarizePushResult(ScmPushService.ApplyPushResult r) {
    if (r == null) return "push: (unknown)";
    if ("SKIPPED".equals(r.mode())) {
      return "push: skipped (" + (r.skippedReason() == null ? "unknown" : r.skippedReason()) + ")";
    }
    int ok = 0;
    int clean = 0;
    int fail = 0;
    for (ScmPushService.ApplyRepoOutcome o : r.perRepo()) {
      if (o.clean()) clean++;
      else if (o.ok()) ok++;
      else fail++;
    }
    if (r.perRepo().size() == 1) {
      var only = r.perRepo().get(0);
      if (only.clean()) return "push: clean (no diff to push)";
      if (only.ok()) return "push: pushed → " + only.branch();
      return "push: failed (" + only.message() + ")";
    }
    return "push: "
        + ok
        + " pushed, "
        + clean
        + " clean, "
        + fail
        + " failed across "
        + r.perRepo().size()
        + " repos";
  }

  /**
   * Legacy workspace-root commit helper. Retained as an internal escape hatch (currently unused
   * after Fix L) — every apply now goes through {@link ScmPushService#pushApplyChanges} which
   * commits + pushes per-repo. If a future code path needs to capture changes in the wrapping
   * workspace git without pushing, restore the call site here.
   */
  @SuppressWarnings("unused")
  private static String tryGitCommit(Path workspace, String prefix, String title) {
    try {
      runCmd(workspace, "git", "add", "-A");
      String status = runCmd(workspace, "git", "status", "--porcelain");
      if (status == null || status.isBlank()) {
        return "no-op (nothing to commit)";
      }
      String msg = prefix + ": " + (title.isBlank() ? "apply user suggestion" : title);
      runCmd(
          workspace,
          "git",
          "commit",
          "-m",
          msg,
          "--author",
          "Executespec Copilot <copilot@executespec.local>");
      return "committed";
    } catch (Exception e) {
      log.warn("ApplyService git commit failed at {}: {}", workspace, e.getMessage());
      return "commit-failed: " + e.getMessage();
    }
  }

  @SuppressWarnings("unused")
  private static String runCmd(Path cwd, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    StringBuilder sb = new StringBuilder();
    try (var br =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) sb.append(line).append('\n');
    }
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IOException("git command timeout: " + String.join(" ", cmd));
    }
    if (p.exitValue() != 0) {
      throw new IOException(
          "exit="
              + p.exitValue()
              + " cmd="
              + String.join(" ", cmd)
              + " output="
              + sb.toString().trim());
    }
    return sb.toString().trim();
  }
}
