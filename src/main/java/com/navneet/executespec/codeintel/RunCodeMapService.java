package com.navneet.executespec.codeintel;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RunCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.RunCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot v3.0 follow-up — generates + persists a per-run codemap for paid-tier existing-repo runs.
 * Output is the LLM-summarised text injected into both the planner's {@code workspaceSummary} and
 * the assessment prompt.
 *
 * <p>Tier gate is enforced at the call site (not here) so the service is reusable if other code
 * paths want to opt in unconditionally.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunCodeMapService {

  private static final Set<String> ELIGIBLE_TIERS = Set.of("PRO", "TEAM", "ENTERPRISE");
  private static final int MAX_RAW_CHARS = 16_000;
  private static final Pattern ROUTE_LINE = Pattern.compile("(?m)^- `[^`]+`\\s*$");

  private final CodeMapGenerator codeMapGenerator;
  private final RunCodeMapRepository repo;
  // RL-2026-05-041 (audit #14 — dual codemap consolidation): a project-scoped run reuses the
  // project's canonical codemap instead of regenerating + re-summarising from scratch.
  private final ProjectCodeMapRepository projectCodeMapRepo;
  private final ProjectRepoRepository projectRepoRepo;
  // RR-1586 — the revision this run actually checked out, so a reused project codemap can be proven
  // to describe the code the planner will plan against.
  private final RunRepoSnapshotRepository runRepoSnapshotRepo;
  private final GitHubRepoService gitHub;
  private final LlmRouter llmRouter;
  private final PipelineRunRepository runRepository;
  private final ModelResolver modelResolver;

  public boolean isEligible(String userTier) {
    return userTier != null && ELIGIBLE_TIERS.contains(userTier.toUpperCase());
  }

  /**
   * @return the LLM summary (or raw markdown when summarisation isn't available) that callers
   *     should inject into prompts; {@code null} if generation failed.
   */
  /** Backward-compatible overload for standalone (non-project) runs. */
  @Transactional
  public String generateForRun(String runId, String repoUrl, String defaultBranch, String pat) {
    return generateForRun(runId, repoUrl, defaultBranch, pat, null);
  }

  /**
   * RL-2026-05-041 (audit #14 — dual codemap consolidation). When {@code projectId} is non-null and
   * the project already has a {@link ProjectCodeMap} for this repo, reuse that content (0 LLM cost,
   * no redundant scan) rather than regenerating an independent per-run codemap. The project codemap
   * is produced by the same {@link CodeMapGenerator} core and is the artifact the execution planner
   * already consumes, so reuse keeps the planner + assessment context consistent. Standalone runs
   * (no project) fall through to the legacy generate-and-summarise path unchanged.
   */
  @Transactional
  public String generateForRun(
      String runId, String repoUrl, String defaultBranch, String pat, String projectId) {
    Optional<RunCodeMap> existing = repo.findByRunId(runId);
    if (existing.isPresent()) {
      log.debug("RunCodeMap already exists for run {}", runId);
      return existing.get().getSummary() != null
          ? existing.get().getSummary()
          : existing.get().getRawContent();
    }

    String repoFullName = extractFullName(repoUrl);
    if (repoFullName == null) {
      log.warn("Cannot extract owner/repo from {} — skipping codemap", repoUrl);
      return null;
    }

    // Consolidation: reuse the project's canonical codemap when available.
    String reused = tryReuseProjectCodeMap(runId, repoUrl, repoFullName, projectId, pat);
    if (reused != null) return reused;

    String branch = (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch;

    CodeMapGenerator.Result raw;
    try {
      raw = codeMapGenerator.generateWithSha(repoFullName, branch, pat);
    } catch (Exception e) {
      log.warn(
          "CodeMapGenerator failed for run {} repo {}: {}", runId, repoFullName, e.getMessage());
      return null;
    }
    if (raw == null || raw.markdown() == null || raw.markdown().isBlank()) return null;

    int fileCount = countFiles(raw.markdown());
    int routeCount = countRoutes(raw.markdown());

    String summary = null;
    int inputTokens = 0, outputTokens = 0;
    try {
      String[] llm = summarise(runId, repoFullName, raw.markdown());
      summary = llm[0];
      inputTokens = Integer.parseInt(llm[1]);
      outputTokens = Integer.parseInt(llm[2]);
    } catch (Exception e) {
      log.warn(
          "Codemap summarisation failed for run {}: {} — falling back to raw",
          runId,
          e.getMessage());
    }

    RunCodeMap entity =
        RunCodeMap.builder()
            .runId(runId)
            .repoUrl(repoUrl)
            .rawContent(truncate(raw.markdown(), MAX_RAW_CHARS))
            .summary(summary)
            .fileCount(fileCount)
            .routeCount(routeCount)
            .generatedFromSha(raw.sha())
            .inputTokens(inputTokens)
            .outputTokens(outputTokens)
            .build();
    repo.save(entity);
    log.info(
        "RunCodeMap persisted for run {}: {} files / {} routes / {} input / {} output tokens",
        runId,
        fileCount,
        routeCount,
        inputTokens,
        outputTokens);
    return summary != null ? summary : entity.getRawContent();
  }

  public Optional<RunCodeMap> findByRunId(String runId) {
    return repo.findByRunId(runId);
  }

  /**
   * RL-2026-05-041 (audit #14). If this run belongs to a project that already has a codemap for the
   * same repo, persist a {@link RunCodeMap} sourced from the project codemap (0 LLM tokens, no
   * regeneration) and return its content. Returns {@code null} when there's no project match, so
   * the caller falls through to the legacy generate-and-summarise path.
   *
   * <p>RR-1586 — reuse is now provenance-checked. Previously the match was on {@code repoFullName}
   * alone: a project codemap generated weeks and many merges ago could be injected into the planner
   * as this run's repository picture, persisted with the PROJECT codemap's {@code generatedFromSha}
   * so it looked verified. Reuse now requires the run's own checked-out revision ({@code
   * run_repo_snapshot}) to either equal the project codemap's revision, or differ from it only
   * inside {@code .executespec/} (ExecuteSpec's own published artefacts move HEAD without changing
   * source). Anything else — including a missing snapshot, or a project codemap with no recorded
   * SHA — regenerates. The persisted row records the RUN's SHA, which is the revision the content
   * is now asserted to describe.
   */
  private String tryReuseProjectCodeMap(
      String runId, String repoUrl, String repoFullName, String projectId, String pat) {
    if (projectId == null || projectId.isBlank()) return null;
    try {
      for (ProjectCodeMap pcm : projectCodeMapRepo.findByProjectId(projectId)) {
        if (pcm.getRepoId() == null) continue;
        ProjectRepo pr = projectRepoRepo.findById(pcm.getRepoId()).orElse(null);
        if (pr == null || !repoFullName.equalsIgnoreCase(pr.getGithubRepoFullName())) continue;
        String content = pcm.getContent();
        if (content == null || content.isBlank()) continue;
        // Resolve the LATEST version for this repo (findByProjectId returns all versions).
        ProjectCodeMap latest =
            projectCodeMapRepo
                .findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, pcm.getRepoId())
                .orElse(pcm);
        String latestContent = latest.getContent() != null ? latest.getContent() : content;

        String runSha = runSnapshotSha(runId, repoFullName);
        if (runSha == null) {
          log.info(
              "RunCodeMap run={} NOT reusing project codemap (project={}, repo={}): no run repo"
                  + " snapshot to verify the revision against — regenerating",
              runId,
              projectId,
              repoFullName);
          return null;
        }
        String projectSha = latest.getGeneratedFromSha();
        if (projectSha == null || projectSha.isBlank()) {
          log.info(
              "RunCodeMap run={} NOT reusing project codemap (project={}, repo={}, v{}): the"
                  + " project codemap records no source sha — regenerating",
              runId,
              projectId,
              repoFullName,
              latest.getVersion());
          return null;
        }
        boolean sameRevision = runSha.equalsIgnoreCase(projectSha);
        boolean executespecOnlyDrift =
            !sameRevision
                && gitHub.diffConfinedTo(repoFullName, projectSha, runSha, ".executespec/", pat);
        if (!sameRevision && !executespecOnlyDrift) {
          log.info(
              "RunCodeMap run={} NOT reusing project codemap (project={}, repo={}, v{}): codemap"
                  + " sha {} != run sha {} with source-level drift — regenerating",
              runId,
              projectId,
              repoFullName,
              latest.getVersion(),
              projectSha,
              runSha);
          return null;
        }

        RunCodeMap reused =
            RunCodeMap.builder()
                .runId(runId)
                .repoUrl(repoUrl)
                .rawContent(truncate(latestContent, MAX_RAW_CHARS))
                .summary(null) // reusing full project codemap; no separate per-run LLM summary
                .fileCount(countFiles(latestContent))
                .routeCount(countRoutes(latestContent))
                // RR-1586 — record the revision THIS run checked out, not the project codemap's, so
                // the provenance field cannot overstate what was verified.
                .generatedFromSha(runSha)
                .inputTokens(0)
                .outputTokens(0)
                .build();
        repo.save(reused);
        log.info(
            "RunCodeMap run={} REUSED project codemap (project={}, repo={}, v{}, sha={},"
                + " executespecOnlyDrift={}) — 0 LLM cost",
            runId,
            projectId,
            repoFullName,
            latest.getVersion(),
            runSha,
            executespecOnlyDrift);
        return reused.getRawContent();
      }
    } catch (Exception e) {
      log.warn(
          "Project-codemap reuse probe failed for run {} (project {}): {} — regenerating",
          runId,
          projectId,
          e.getMessage());
    }
    return null;
  }

  /**
   * RR-1586 — the commit SHA this run cloned for {@code repoFullName}, or {@code null} when the run
   * has no snapshot for that repo (standalone run, pre-clone phase, or a repo the run never checked
   * out). A null answer means "cannot verify" and must never be treated as "matches".
   */
  private String runSnapshotSha(String runId, String repoFullName) {
    for (RunRepoSnapshot snap : runRepoSnapshotRepo.findByRunId(runId)) {
      if (snap.getRepoFullName() == null || snap.getCommitSha() == null) continue;
      if (repoFullName.equalsIgnoreCase(snap.getRepoFullName())) return snap.getCommitSha();
    }
    return null;
  }

  /**
   * Returns [summary, inputTokens, outputTokens].
   *
   * <p>The summary is routed through {@link LlmRouter}; a disabled or unavailable transport falls
   * back to the deterministic raw codemap at the caller.
   */
  private String[] summarise(String runId, String repoFullName, String rawMarkdown) {
    var run =
        runRepository
            .findById(runId)
            .orElseThrow(() -> new IllegalStateException("Run not found for codemap routing"));
    String providerValue = run.isRoutePinned() ? run.getRequestedProvider() : run.getAiProvider();
    if (providerValue == null || providerValue.isBlank()) {
      throw new IllegalStateException("Run provider missing for codemap routing");
    }
    LlmProvider provider = LlmProvider.valueOf(providerValue.toUpperCase());
    ResolvedModel route =
        run.isRoutePinned()
            ? ResolvedModel.builder()
                .requestedProvider(provider)
                .actualProvider(provider)
                .model(run.getRequestedModel())
                .agentTier(AgentTier.LIGHTWEIGHT)
                .fallback(false)
                .build()
            : modelResolver.resolve("RunCodeMapService", provider, AgentTier.LIGHTWEIGHT);
    String system =
        """
        You are a senior engineer summarising a code map for use as planner context.
        Return a concise markdown brief (≤ 60 lines, ≤ 3000 tokens) covering:
        - Top-level module structure (1 line per major area)
        - Public HTTP / RPC endpoints and their handler types
        - Persistence model: entities, key fields, foreign keys
        - Cross-cutting concerns: auth, config, background jobs
        - Anything a feature planner would need to avoid duplicating existing code.
        No prose preamble, no closing notes. Just the brief.
        """;
    String user =
        "Repository: "
            + repoFullName
            + "\n\n## Raw scan\n\n"
            + truncate(rawMarkdown, MAX_RAW_CHARS);
    ChatResponse resp =
        llmRouter.callText(
            route.providerName(),
            route.getModel(),
            java.util.List.of(
                new SystemMessage(system),
                new UserMessage(PromptTrustBoundary.wrap("RAW_CODEMAP_SCAN", user))),
            runId,
            "RunCodeMapService",
            "codemap.summary",
            1200);
    String text = resp.getResult().getOutput().getText();
    int in = 0, out = 0;
    if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
      in = (int) resp.getMetadata().getUsage().getPromptTokens();
      out = (int) resp.getMetadata().getUsage().getCompletionTokens();
    }
    return new String[] {text, String.valueOf(in), String.valueOf(out)};
  }

  private static int countFiles(String md) {
    int idx = md.indexOf("**Files**: ");
    if (idx < 0) return 0;
    int start = idx + 11;
    int end = md.indexOf('\n', start);
    if (end < 0) end = md.length();
    try {
      return Integer.parseInt(md.substring(start, end).trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int countRoutes(String md) {
    java.util.regex.Matcher m = ROUTE_LINE.matcher(md);
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  private static String extractFullName(String repoUrl) {
    if (repoUrl == null) return null;
    String stripped = repoUrl.replaceFirst("https?://[^/]+/", "");
    if (stripped.endsWith(".git")) stripped = stripped.substring(0, stripped.length() - 4);
    int slash = stripped.indexOf('/');
    if (slash < 0) return null;
    int second = stripped.indexOf('/', slash + 1);
    return second < 0 ? stripped : stripped.substring(0, second);
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)";
  }
}
