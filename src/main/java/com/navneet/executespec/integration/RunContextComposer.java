package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.languageConventionHint;
import static com.navneet.executespec.integration.RunPipelineSupport.lastPathSegment;
import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.harness.UiStyleResolver;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Run-context / prompt-block builders extracted from {@link RunOrchestrator} (god-class split,
 * increment 3 — audit #43). Stateless string assembly with no run-lifecycle state: the run-meta
 * README, the {@code .agent-context} project descriptor, the design-system block, the code-intel
 * codemap block, and provider/model resolution. Every method is a relocated, byte-identical
 * builder.
 *
 * <p>Includes the multi-repo workspace summary ({@code buildMultiRepoSummary} + {@code
 * buildLanguageParityBlock}) since inc-3b; the {@code languageConventionHint} mapping it uses was
 * relocated to {@link RunPipelineSupport} (still pinned by {@code LanguageParityBlockTest}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RunContextComposer {

  // RL-2026-05-035 — code-intel injection caps (moved with buildCodeIntelContext). Per-repo +
  // total bounds so a large codemap can't blow the planner/worker cold-start budget.
  private static final int CODEINTEL_TOTAL_CAP = 12_000;
  private static final int CODEINTEL_PER_REPO_CAP = 6_000;
  private static final String DEFAULT_PROVIDER = "openai";

  private final ObjectMapper objectMapper;
  private final UiStyleResolver uiStyleResolver;
  private final ProjectArchitectureService projectArchitectureService;
  private final ProjectRepository projectRepository;
  private final ModelRegistry modelRegistry;
  private final ProjectRepoService projectRepoService;

  void ensureReadme(
      String runId,
      Path workspace,
      String userRequest,
      Plan plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Instant startedAt) {
    Path readme = workspace.resolve("README.md");
    if (Files.exists(readme)) {
      log.debug(
          "RunOrchestrator run={} README.md already present (planner-authored), skipping", runId);
      return;
    }
    try {
      Files.writeString(
          readme, renderRunReadme(runId, userRequest, plan, execResult, reviewResult, startedAt));
      log.info(
          "RunOrchestrator run={} wrote run-meta README.md ({} bytes)", runId, Files.size(readme));
    } catch (IOException e) {
      log.warn("RunOrchestrator run={} failed to write README.md: {}", runId, e.getMessage());
    }
  }

  String renderRunReadme(
      String runId,
      String userRequest,
      Plan plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Instant startedAt) {
    StringBuilder sb = new StringBuilder();
    sb.append("# ")
        .append(plan.planTitle() != null ? plan.planTitle() : "Run Output")
        .append("\n\n");
    if (plan.planSummary() != null && !plan.planSummary().isBlank()) {
      sb.append(plan.planSummary()).append("\n\n");
    } else {
      sb.append("This project was generated from the following request:\n\n> ")
          .append(userRequest)
          .append("\n\n");
    }

    // How to run
    String buildCmd = plan.buildCommand();
    if (buildCmd != null && !buildCmd.isBlank()) {
      sb.append("## How to build / run\n\n```bash\n").append(buildCmd).append("\n```\n\n");
    } else {
      sb.append("## How to run\n\nSee source files for entry points.\n\n");
    }

    // Task summary
    sb.append("## Tasks\n\n| ID | Title | Status |\n|---|---|---|\n");
    for (TaskOutcome t : execResult.perTask()) {
      sb.append("| `")
          .append(t.taskId())
          .append("` | ")
          .append(t.taskTitle() == null ? "" : t.taskTitle().replace("|", "\\|"))
          .append(" | `")
          .append(t.status().name())
          .append("` |\n");
    }
    sb.append("\n");

    // Review verdict
    if (reviewResult != null) {
      sb.append("## Code review\n\n")
          .append("**Verdict:** `")
          .append(reviewResult.overall().name())
          .append("`");
      if (reviewResult.finalBuildPassed() != null) {
        sb.append("  \n**Build passed:** `").append(reviewResult.finalBuildPassed()).append("`");
      }
      sb.append("\n\n");
      if (reviewResult.generalPassSummary() != null
          && !reviewResult.generalPassSummary().isBlank()) {
        sb.append("**General review:** ").append(reviewResult.generalPassSummary()).append("\n\n");
      }
      if (reviewResult.wiringPassSummary() != null && !reviewResult.wiringPassSummary().isBlank()) {
        sb.append("**Wiring review:** ").append(reviewResult.wiringPassSummary()).append("\n\n");
      }
    }

    // Run metadata
    sb.append("## Run details\n\n")
        .append("- **Run ID:** `")
        .append(runId)
        .append("`\n")
        .append("- **Started:** `")
        .append(startedAt)
        .append("`\n")
        .append("- **Completed:** `")
        .append(Instant.now())
        .append("`\n")
        .append("- **Harness:** ExecuteSpec V2\n")
        .append("- **Overall:** `")
        .append(execResult.overall().name())
        .append("`\n");
    if (plan.projectType() != null && !plan.projectType().isBlank()) {
      sb.append("- **Project type:** `").append(plan.projectType()).append("`\n");
    }
    sb.append("\n---\n\n*Generated by ExecuteSpec V2.*\n");
    return sb.toString();
  }

  void writeAgentContext(Path workspace, Plan plan) {
    if (workspace == null || plan.projectType() == null) return;
    try {
      Path dir = workspace.resolve(".agent-context");
      Files.createDirectories(dir);
      // Normalize to the canonical PreviewProjectType/DeployStrategy enum form
      // (UPPER_SNAKE_CASE). V2 planner historically emitted lowercase-hyphenated
      // values (e.g. "node-backend") which silently fell through to heuristic
      // detection and picked the wrong Dockerfile template.
      String normalizedType = plan.projectType().trim().toUpperCase().replace('-', '_');
      String json =
          objectMapper.writeValueAsString(java.util.Map.of("project_type", normalizedType));
      Files.writeString(dir.resolve("project.json"), json);
    } catch (Exception e) {
      log.debug("writeAgentContext failed: {}", e.getMessage());
    }
  }

  /**
   * Resolves and loads the design system block for this run using V1's {@link UiStyleResolver}.
   * Picks from three signals in priority order: explicit hint parsed from the user request,
   * planner-declared ui_style, and project-type heuristic. Returns an empty string for backend-only
   * projects so the worker prompt stays compact.
   */
  String buildDesignBlock(String userRequest, Plan plan) {
    try {
      String hint = uiStyleResolver.extractStyleHint(userRequest);
      String plannerStyle = null; // Plan has no explicit ui_style field today
      String projectType = plan.projectType() == null ? "unknown" : plan.projectType();
      String designName = uiStyleResolver.resolveDesignName(hint, plannerStyle, projectType);
      if (designName == null) return "";
      String content = uiStyleResolver.loadDesignContent(designName);
      String block = uiStyleResolver.buildFrontendConstraintBlock(null, content);
      if (block == null || block.isBlank()) return "";
      log.info(
          "RunOrchestrator run=? design resolved='{}' (hint='{}', project={})",
          designName,
          hint,
          projectType);
      return block;
    } catch (Exception e) {
      log.warn("buildDesignBlock failed: {}", e.getMessage());
      return "";
    }
  }

  String buildCodeIntelContext(
      String projectId, java.util.List<com.navneet.executespec.project.entity.ProjectRepo> repos) {
    if (projectId == null || repos == null || repos.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    int budget = CODEINTEL_TOTAL_CAP;
    try {
      for (com.navneet.executespec.project.entity.ProjectRepo r : repos) {
        if (budget <= 0) break;
        var cm = projectArchitectureService.latestCodeMap(projectId, r.getId());
        if (cm.isEmpty() || cm.get().getContent() == null || cm.get().getContent().isBlank()) {
          continue;
        }
        String body = truncate(cm.get().getContent(), Math.min(budget, CODEINTEL_PER_REPO_CAP));
        sb.append("### Codemap — ")
            .append(lastPathSegment(r.getGithubRepoFullName()))
            .append("\n")
            .append(body)
            .append("\n\n");
        budget -= body.length();
      }
    } catch (Exception e) {
      log.warn("buildCodeIntelContext failed (continuing without code-intel): {}", e.getMessage());
      return "";
    }
    String out = sb.toString().trim();
    if (out.isBlank()) return "";
    return "EXISTING CODEBASE MAP — use this to LOCATE the right files/symbols before editing;"
        + " do NOT re-discover the structure from scratch:\n\n"
        + out;
  }

  /**
   * Resolve the effective provider string for a run.
   *
   * <p>Order of precedence: 1. Request-supplied {@code aiProvider} (lowercased, "google"→"gemini").
   * 2. Project's default {@code ai_provider} (when {@code projectId} is set). 3. Platform default
   * ({@code DEFAULT_PROVIDER} = "openai").
   *
   * <p>Levels 2/3 log a WARN so missing provider values surface in ops rather than silently
   * defaulting. Legacy {@link com.navneet.executespec.execution.entity.ExecutionRun} rows
   * pre-dating V92 hit level 2; the V99 backfill closes that gap for new rows.
   */
  String normalizeProvider(String aiProvider, String projectId, String runId) {
    // v2.5.8 — strict-provider mode: when the system property
    // executespec.strict-provider=true, we refuse to fall back silently.
    // Used by the cross-provider evaluation harness so Gemini runs never
    // accidentally land on OpenAI due to a null flag.
    boolean strict =
        "true".equalsIgnoreCase(System.getProperty("executespec.strict-provider", "false"));
    if (aiProvider != null && !aiProvider.isBlank()) {
      String lower = aiProvider.toLowerCase();
      String resolved = "google".equals(lower) ? "gemini" : lower;
      log.info(
          "normalizeProvider[strict={}] runId={} -> provider={} (explicit)",
          strict,
          runId,
          resolved);
      return resolved;
    }
    if (strict) {
      throw new IllegalStateException(
          "strict-provider mode: runId=" + runId + " has null aiProvider — refusing to fall back");
    }
    if (projectId != null) {
      String projectProvider =
          projectRepository
              .findById(projectId)
              .map(p -> p.getAiProvider() == null ? null : p.getAiProvider().name())
              .orElse(null);
      if (projectProvider != null && !projectProvider.isBlank()) {
        log.warn(
            "normalizeProvider: null aiProvider on runId={} — falling back to project default {}",
            runId,
            projectProvider);
        String lower = projectProvider.toLowerCase();
        return "google".equals(lower) ? "gemini" : lower;
      }
    }
    log.warn(
        "normalizeProvider: no provider resolvable for runId={} projectId={} — defaulting to {}",
        runId,
        projectId,
        DEFAULT_PROVIDER);
    return DEFAULT_PROVIDER;
  }

  /**
   * Run-level worker model default — used when {@code RunRequest.modelName} is blank.
   *
   * <p>2026-05-06 single-source consolidation: routes through {@link
   * ModelRegistry#workhouseModelFor(String)} so the YAML matrix is the only source of truth.
   * Previously a hardcoded switch returned Haiku/flash/mini even after YAML moved Anthropic
   * WORKHORSE to Sonnet (2026-04-19) and Gemini WORKHORSE to Pro (2026-05-04).
   *
   * <p>Final-fallback safety net: if YAML lookup returns null (e.g. provider mis-configured), fall
   * back to the historical Anthropic-Haiku default rather than NPE'ing the run.
   */
  String defaultModelFor(String provider) {
    String model = modelRegistry.workhouseModelFor(provider);
    if (model != null && !model.isBlank()) return model;
    log.warn(
        "RunOrchestrator: no workhorse model in YAML matrix for provider '{}'; falling back to"
            + " claude-haiku-4-5-20251001",
        provider);
    return "claude-haiku-4-5-20251001";
  }

  String buildMultiRepoSummary(String projectId, List<UUID> selectedRepoIds) {
    if (projectId == null || selectedRepoIds == null || selectedRepoIds.isEmpty()) return null;
    List<ProjectRepo> repos;
    try {
      repos = projectRepoService.resolveForRun(projectId, selectedRepoIds);
    } catch (Exception e) {
      return null;
    }
    if (repos.isEmpty()) return null;
    StringBuilder sb =
        new StringBuilder(
            "MULTI-REPO PROJECT WORKSPACE.\n\n"
                + "Each selected repo is cloned into its own subdirectory under `repos/`.\n"
                + "All file paths you create or edit MUST live inside the matching"
                + " `repos/<name>/`\n"
                + "subdirectory — never at the workspace root. Only one PR per repo is opened,\n"
                + "based on the diff inside that subdirectory.\n\n"
                + "Repos available for this run:\n");
    for (ProjectRepo r : repos) {
      String name = lastPathSegment(r.getGithubRepoFullName());
      sb.append("- `repos/")
          .append(name)
          .append("/` → github.com/")
          .append(r.getGithubRepoFullName())
          .append(" (default branch: ")
          .append(r.getDefaultBranch() == null ? "main" : r.getDefaultBranch())
          .append(")\n");
    }

    // Fix D (2026-05-03, run a076c876 T2 diagnosis) — append a language parity
    // block per repo from ProjectRepo.detectedStack so the planner doesn't
    // template TypeScript filenames into a Python repo and vice versa. T2's
    // task description told the worker to edit `repos/pplai-api/src/config/index.ts`
    // when pplai-api is FastAPI/Python; the worker spent iters 1-10 reconciling
    // the spec's TypeScript wording against the actual Python code on disk.
    String languageBlock = buildLanguageParityBlock(repos);
    if (!languageBlock.isEmpty()) {
      sb.append('\n').append(languageBlock);
    }
    return sb.toString();
  }

  /**
   * Render a per-repo language parity block from the {@link ProjectRepo#getDetectedStack()} JSON
   * shape produced by {@code StackDetector} ({@code {language, framework, version}}). Returns the
   * empty string when no repo has a non-blank detected language — there's nothing useful to say.
   *
   * <p>The block instructs the planner to TRANSLATE filename / extension conventions in the spec to
   * match the repo's actual language. Without this block, planners templated TypeScript filenames
   * into Python repos because the user's spec wording happened to use {@code .ts} extensions (run
   * a076c876 / T2). The planner now sees an explicit rule and either translates up-front or rejects
   * the mismatch.
   */
  private String buildLanguageParityBlock(List<ProjectRepo> repos) {
    if (repos == null || repos.isEmpty()) return "";
    StringBuilder lines = new StringBuilder();
    for (ProjectRepo r : repos) {
      String stackJson = r.getDetectedStack();
      if (stackJson == null || stackJson.isBlank()) continue;
      String language = null;
      String framework = null;
      try {
        var node = objectMapper.readTree(stackJson);
        language = node.path("language").asText("");
        framework = node.path("framework").asText("");
        if (language == null || language.isBlank()) continue;
      } catch (Exception e) {
        // detectedStack is malformed JSON — skip this repo's hint, no big deal.
        continue;
      }
      String name = lastPathSegment(r.getGithubRepoFullName());
      String hint = languageConventionHint(language, framework);
      lines.append("- `repos/").append(name).append("/` is **").append(language);
      if (framework != null && !framework.isBlank()) {
        lines.append(" / ").append(framework);
      }
      lines.append("** — ").append(hint).append('\n');
    }
    if (lines.length() == 0) return "";
    return "LANGUAGE PARITY (do not violate):\n"
        + lines
        + "If the user's spec uses different language conventions for a repo (e.g. asks for"
        + " `scan.service.ts` in a Python repo), TRANSLATE the filenames + idioms to match the"
        + " repo's actual language before emitting the task description. The repo's detected"
        + " language is authoritative.\n";
  }
}
