package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.router.LlmProvider;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectArchitectureService {

  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final ProjectRepository projectRepository;
  private final ProjectCodeMapRepository codeMapRepo;
  private final ProjectArchitectureDocRepository archDocRepo;
  private final RepoArchitectureDocRepository repoArchRepo;
  private final VersionRetentionService versionRetention;
  private final CodeMapGenerator codeMapGenerator;
  private final RepoArchitectureGenerator repoArchGenerator;
  private final ArchitectureDocGenerator archDocGenerator;
  private final com.navneet.executespec.repo.GitHubRepoService gitHub;
  private final ProjectWorkspaceService workspace;
  private final com.navneet.executespec.project.workspace.ProjectWorkspaceWarmer workspaceWarmer;
  private final ProjectBriefService briefService;

  /** v2.6.4 — compute USD cost for arch + repo-arch generations so the dashboard can roll up. */
  private final com.navneet.executespec.cost.RunCostTracker costTracker;

  private final ObjectMapper objectMapper;

  /**
   * E1.2 — also publish each per-repo codemap to the customer's .executespec/arch/codemap.md (PR by
   * default; DIRECT_PUSH when project_repo.executespec_pr_mode = DIRECT_PUSH). Lazy via
   * ObjectProvider so admin tooling + tests that don't need the publish path can wire
   * ProjectArchitectureService without pulling in the writer chain.
   */
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.folder.ExecutespecFolderWriter>
      folderWriterProvider;

  /**
   * E1.2 — emits CODEMAP_REGENERATED on the activity feed each time a per-repo codemap is published
   * to .executespec/. ObjectProvider keeps this optional so codemap generation can run without the
   * activity pipeline (e.g. unit tests that don't load the activity package).
   */
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.activity.ActivityEventEmitter>
      activityEmitterProvider;

  /**
   * V169 (2026-05-02) — surfaces architecture-write PRs in the project dashboard's PR rollup. Same
   * Optional-pattern as the folder writer so non-publishing call sites (unit tests, admin tooling)
   * don't need to wire the project.pr package.
   */
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.project.pr.PullRequestRecordService>
      prRecordServiceProvider;

  /**
   * Regenerate codemaps for every linked repo, then synthesise a fresh architecture doc. Versioned:
   * existing docs are preserved.
   */
  public ProjectArchitectureDoc generate(String projectId) {
    return generate(projectId, null, null);
  }

  public ProjectArchitectureDoc generate(String projectId, String branchOverride) {
    return generate(projectId, branchOverride, null);
  }

  /**
   * Regenerate codemaps for every linked repo using the supplied branch (falls back to each repo's
   * default when {@code branchOverride} is null or blank) and the supplied PAT (falls back to the
   * system PAT when {@code patOverride} is null).
   */
  /**
   * 3-step architecture generation pipeline:
   *
   * <ol>
   *   <li><b>Step 1</b> — per-repo codemap. Idempotent: reuse the latest codemap when the current
   *       branch HEAD matches {@code generatedFromSha}. Saves a new version otherwise.
   *   <li><b>Step 2</b> — per-repo architecture doc (HLD + LLD, natural language) via {@link
   *       RepoArchitectureGenerator}. Idempotent the same way — reuse if the source codemap id
   *       hasn't changed.
   *   <li><b>Step 3</b> — combined project architecture doc synthesised from the per-repo arch docs
   *       via {@link ArchitectureDocGenerator}. Always produces a new version.
   * </ol>
   *
   * <p>All artefacts land in the project workspace under {@code workspace/projects/<id>/}:
   *
   * <pre>
   *   codemaps/&lt;repo&gt;.md
   *   repos/&lt;repo&gt;/architecture.md   <- NEW, per-repo
   *   architecture.md                   <- combined
   *   summary.md
   * </pre>
   *
   * <p><b>Not</b> {@code @Transactional} — this method runs three LLM calls per repo plus a
   * combined synthesis call, which can easily exceed 60s on a multi-repo project. A single
   * enclosing transaction would pin a Hikari connection for that entire duration and trip the leak
   * detector. Instead each {@code save()} runs in its own (auto-)transaction, keyed by the
   * latest-version-wins lookup in {@link #ensureCodeMap} / {@link #ensureRepoArchDoc}.
   */
  public ProjectArchitectureDoc generate(
      String projectId, String branchOverride, String patOverride) {
    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    if (links.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Project has no linked repos — link repos before generating architecture");
    }

    LlmProvider provider =
        projectRepository
            .findById(projectId)
            .map(Project::getAiProvider)
            .orElse(LlmProvider.OPENAI);

    boolean hasOverride = branchOverride != null && !branchOverride.isBlank();
    List<ProjectCodeMap> codeMaps = new ArrayList<>();
    List<String> repoFullNames = new ArrayList<>();
    List<RepoArchitectureDoc> repoArchDocs = new ArrayList<>();
    java.util.LinkedHashMap<String, String> shasByRepo = new java.util.LinkedHashMap<>();
    // V192 (PR-M, 2026-05-13) — track the branch each codemap was generated from so
    // StalenessService can probe HEAD of the same branch instead of always assuming
    // defaultBranch (fixes permanent-STALE for non-default-branch regenerations).
    java.util.LinkedHashMap<String, String> branchesByRepo = new java.util.LinkedHashMap<>();

    for (ProjectRepoLink link : links) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) continue;
      String effectiveBranch = hasOverride ? branchOverride : repo.getDefaultBranch();

      // ── Step 1: per-repo codemap (idempotent by SHA) ───────────────────
      ProjectCodeMap cm = ensureCodeMap(projectId, repo, effectiveBranch, patOverride);
      codeMaps.add(cm);
      repoFullNames.add(repo.getGithubRepoFullName());
      shasByRepo.put(repo.getGithubRepoFullName(), cm.getGeneratedFromSha());
      branchesByRepo.put(repo.getGithubRepoFullName(), effectiveBranch);

      // ── Step 2: per-repo architecture doc (idempotent by codemap) ─────
      RepoArchitectureDoc rad = ensureRepoArchDoc(projectId, repo, cm, provider);
      repoArchDocs.add(rad);
    }

    // PR-K (2026-05-13) — fingerprint cache. The combined-arch LLM call is the most
    // expensive step in the whole pipeline (60s+ on multi-repo projects, $0.05-0.20+ in tokens).
    // It's also the most-frequently wasted: users hit "Regenerate" expecting the dashboard to
    // refresh, but if no repo SHA has actually moved since the last generation the LLM produces
    // a byte-identical document. Short-circuit: if every linked repo's current SHA matches the
    // SHAs captured on the most recent ProjectArchitectureDoc AND its content is non-empty,
    // return that doc unchanged instead of burning the LLM call.
    //
    // Cache key is the JSON-serialised shasByRepo map. Equality is structural — the LinkedHashMap
    // preserves insertion order and we sort keys before serialise so the comparison isn't
    // sensitive to repo-link iteration order changing across calls.
    String currentFingerprint = serialiseFingerprint(shasByRepo);
    ProjectArchitectureDoc cached =
        archDocRepo
            .findFirstByProjectIdOrderByVersionDesc(projectId)
            .filter(d -> d.getContent() != null && !d.getContent().isBlank())
            .filter(d -> fingerprintMatches(d.getGeneratedFromShas(), currentFingerprint))
            .orElse(null);
    if (cached != null) {
      log.info(
          "[{}] arch regen short-circuit — same SHAs as v{} (fingerprint match); skipped LLM",
          projectId,
          cached.getVersion());
      return cached;
    }

    // ── Step 3: combined architecture doc (always regenerates) ────────
    ArchitectureDocGenerator.Result combined;
    try {
      combined = archDocGenerator.generate(projectId, repoArchDocs, repoFullNames, provider);
    } catch (Exception e) {
      log.warn(
          "[{}] LLM combined-arch failed ({}) — falling back to deterministic combine",
          projectId,
          e.getMessage());
      java.util.LinkedHashMap<String, String> byRepo = new java.util.LinkedHashMap<>();
      for (int i = 0; i < repoArchDocs.size(); i++) {
        byRepo.put(
            i < repoFullNames.size() ? repoFullNames.get(i) : ("repo-" + i),
            repoArchDocs.get(i).getContent());
      }
      combined =
          new ArchitectureDocGenerator.Result(
              ArchitectureDocGenerator.deterministicCombined(projectId, byRepo),
              0,
              0,
              "deterministic");
    }

    int nextVersion =
        archDocRepo
            .findFirstByProjectIdOrderByVersionDesc(projectId)
            .map(d -> d.getVersion() + 1)
            .orElse(1);
    String shasJson = null;
    try {
      shasJson = objectMapper.writeValueAsString(shasByRepo);
    } catch (Exception e) {
      log.warn("Failed to serialise generatedFromShas: {}", e.getMessage());
    }
    // V192 (PR-M) — serialise the branches map alongside.
    String branchesJson = null;
    try {
      branchesJson = objectMapper.writeValueAsString(branchesByRepo);
    } catch (Exception e) {
      log.warn("Failed to serialise generatedFromBranches: {}", e.getMessage());
    }
    // v2.6.4 (V174) — compute USD cost for the combined-arch synthesis. Skipped (= 0) on the
    // deterministic-fallback path since no LLM call happened there.
    double archCost =
        "deterministic".equals(combined.modelUsed())
            ? 0.0
            : costTracker.computeCost(
                providerNameFor(provider),
                combined.modelUsed(),
                combined.inputTokens(),
                combined.outputTokens());
    ProjectArchitectureDoc doc =
        ProjectArchitectureDoc.builder()
            .projectId(projectId)
            .version(nextVersion)
            .content(combined.markdown())
            .generatedFromShas(shasJson)
            .generatedFromBranches(branchesJson)
            .costUsd(archCost)
            .build();
    archDocRepo.save(doc);
    // 2026-05-13 — cap version history at the latest N. See VersionRetentionService for the
    // contract + per-artifact grouping keys; failures here are swallowed (logged) so the regen
    // flow always returns the new version even if the legacy-row delete trips on something.
    versionRetention.pruneProjectArch(projectId);
    log.info(
        "[{}] 3-step arch generation complete: {} repos, combined v{} ({} in / {} out tokens)",
        projectId,
        codeMaps.size(),
        nextVersion,
        combined.inputTokens(),
        combined.outputTokens());

    persistToWorkspace(projectId, codeMaps, repoArchDocs, repoFullNames, combined.markdown());
    workspaceWarmer.warmDiskCache(projectId);
    // E1.2 — also publish each per-repo codemap to .executespec/arch/codemap.md
    // on the customer repo. Best-effort: never fails the regen flow.
    publishCodemapsToCustomerRepos(projectId, codeMaps, repoFullNames, patOverride);
    return doc;
  }

  /**
   * Step 1 helper — reuse the latest codemap when the repo's current default- branch HEAD matches
   * its {@code generatedFromSha}; otherwise regenerate and bump the version.
   */
  private ProjectCodeMap ensureCodeMap(
      String projectId, ProjectRepo repo, String branch, String patOverride) {
    Optional<ProjectCodeMap> latest =
        codeMapRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repo.getId());

    String currentSha =
        gitHub.getBranchHeadSha(
            "https://github.com/" + repo.getGithubRepoFullName(), branch, patOverride);

    if (latest.isPresent()
        && currentSha != null
        && currentSha.equals(latest.get().getGeneratedFromSha())) {
      log.info(
          "[{}] codemap for {} up-to-date @ sha={} — reusing v{}",
          projectId,
          repo.getGithubRepoFullName(),
          shortSha(currentSha),
          latest.get().getVersion());
      return latest.get();
    }

    CodeMapGenerator.Result result =
        codeMapGenerator.generateWithSha(repo.getGithubRepoFullName(), branch, patOverride);
    int nextVersion = latest.map(c -> c.getVersion() + 1).orElse(1);
    ProjectCodeMap cm =
        ProjectCodeMap.builder()
            .projectId(projectId)
            .repoId(repo.getId())
            .version(nextVersion)
            .content(result.markdown())
            .generatedFromSha(result.sha() != null ? result.sha() : repo.getLastSyncedSha())
            .build();
    codeMapRepo.save(cm);
    versionRetention.pruneCodeMap(projectId, repo.getId());
    log.info(
        "[{}] codemap for {} regenerated as v{} @ sha={}",
        projectId,
        repo.getGithubRepoFullName(),
        nextVersion,
        shortSha(cm.getGeneratedFromSha()));
    return cm;
  }

  /**
   * Step 2 helper — reuse the latest repo arch doc when its {@code sourceCodeMapId} matches the
   * codemap we just produced; otherwise regenerate via LLM and bump the version.
   */
  private RepoArchitectureDoc ensureRepoArchDoc(
      String projectId, ProjectRepo repo, ProjectCodeMap codeMap, LlmProvider provider) {
    Optional<RepoArchitectureDoc> latest =
        repoArchRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repo.getId());
    if (latest.isPresent()
        && codeMap.getId() != null
        && codeMap.getId().equals(latest.get().getSourceCodeMapId())) {
      log.info(
          "[{}] repo-arch for {} up-to-date @ codemap v{} — reusing v{}",
          projectId,
          repo.getGithubRepoFullName(),
          codeMap.getVersion(),
          latest.get().getVersion());
      return latest.get();
    }

    RepoArchitectureGenerator.Result rar;
    try {
      rar =
          repoArchGenerator.generate(repo.getGithubRepoFullName(), codeMap.getContent(), provider);
    } catch (Exception e) {
      log.warn(
          "[{}] repo-arch LLM failed for {} ({}) — falling back to codemap as arch content",
          projectId,
          repo.getGithubRepoFullName(),
          e.getMessage());
      rar =
          new RepoArchitectureGenerator.Result(
              "# "
                  + repo.getGithubRepoFullName()
                  + " — Architecture\n\n"
                  + "_LLM generation failed ("
                  + e.getMessage()
                  + "). "
                  + "Showing raw codemap instead._\n\n"
                  + codeMap.getContent(),
              0,
              0,
              "fallback");
    }

    int nextVersion = latest.map(d -> d.getVersion() + 1).orElse(1);
    // v2.6.4 (V174) — compute USD cost from tokens × pricing matrix so the project dashboard
    // can roll arch spend up. providerName() is the actual provider that served the call (after
    // failover); pricing matches it.
    double cost =
        costTracker.computeCost(
            providerNameFor(provider), rar.modelUsed(), rar.inputTokens(), rar.outputTokens());
    RepoArchitectureDoc doc =
        RepoArchitectureDoc.builder()
            .projectId(projectId)
            .repoId(repo.getId())
            .version(nextVersion)
            .content(rar.markdown())
            .sourceCodeMapId(codeMap.getId())
            .generatedFromSha(codeMap.getGeneratedFromSha())
            .modelUsed(rar.modelUsed())
            .inputTokens(rar.inputTokens())
            .outputTokens(rar.outputTokens())
            .costUsd(cost)
            .build();
    repoArchRepo.save(doc);
    versionRetention.pruneRepoArch(projectId, repo.getId());
    log.info(
        "[{}] repo-arch for {} generated as v{} ({} in / {} out tokens, model={})",
        projectId,
        repo.getGithubRepoFullName(),
        nextVersion,
        rar.inputTokens(),
        rar.outputTokens(),
        rar.modelUsed());
    return doc;
  }

  /**
   * Lowercase provider name for the cost matrix lookup. Falls back to "openai" when the project
   * doesn't have a provider set yet.
   */
  private static String providerNameFor(LlmProvider provider) {
    return provider == null ? "openai" : provider.name().toLowerCase();
  }

  private static String shortSha(String sha) {
    return sha == null ? "null" : sha.length() > 8 ? sha.substring(0, 8) : sha;
  }

  /**
   * PR-K (2026-05-13) — canonical serialisation of the per-repo SHA fingerprint. Keys are sorted
   * alphabetically so the resulting string is stable across re-orderings of the linked-repo list
   * (e.g. when a customer adds or removes a project_repo_link, the unaffected repos hash to the
   * same value).
   *
   * <p>Returns null if the input is null or empty so the caller's null-guard does the right thing:
   * a missing fingerprint is "always regenerate", never an accidental cache-hit.
   */
  static String serialiseFingerprint(java.util.Map<String, String> shasByRepo) {
    if (shasByRepo == null || shasByRepo.isEmpty()) return null;
    java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>(shasByRepo);
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (var e : sorted.entrySet()) {
      if (!first) sb.append(",");
      sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  /**
   * PR-K (2026-05-13) — compares the stored fingerprint (from {@code generated_from_shas} JSON
   * column) against the freshly-computed one. Tolerates the legacy column shape — the original
   * {@code generate} flow serialises an unsorted LinkedHashMap, so a v1 row might have a JSON value
   * that's logically equal but textually different. We re-parse + re-canonicalise both sides
   * through {@code serialiseFingerprint} before comparing.
   */
  boolean fingerprintMatches(String storedJson, String currentFingerprint) {
    return fingerprintMatches(objectMapper, storedJson, currentFingerprint);
  }

  /**
   * PR-K (2026-05-13) — static overload that takes the ObjectMapper explicitly. Allows the unit
   * test to exercise the comparison logic without building the full 19-arg
   * ProjectArchitectureService constructor. The instance method delegates to this overload.
   */
  static boolean fingerprintMatches(
      ObjectMapper objectMapper, String storedJson, String currentFingerprint) {
    if (storedJson == null || currentFingerprint == null) return false;
    try {
      @SuppressWarnings("unchecked")
      java.util.Map<String, String> stored =
          objectMapper.readValue(storedJson, java.util.Map.class);
      String canonicalStored = serialiseFingerprint(stored);
      return currentFingerprint.equals(canonicalStored);
    } catch (Exception e) {
      // Malformed legacy row — treat as miss so we regenerate (safe default).
      return false;
    }
  }

  private void persistToWorkspace(
      String projectId,
      List<ProjectCodeMap> codeMaps,
      List<RepoArchitectureDoc> repoArchDocs,
      List<String> repoFullNames,
      String archMarkdown) {
    try {
      workspace.initIfMissing(projectId);

      // codemaps/<shortName>.md — unchanged (Step 1 artefacts)
      for (int i = 0; i < codeMaps.size(); i++) {
        ProjectCodeMap cm = codeMaps.get(i);
        String full = i < repoFullNames.size() ? repoFullNames.get(i) : ("repo-" + cm.getRepoId());
        String shortName = repoShortName(full);
        workspace.writeAndCommit(
            projectId,
            "codemaps/" + shortName + ".md",
            cm.getContent(),
            "codemap: " + full + " v" + cm.getVersion());
      }

      // repos/<shortName>/architecture.md — Step 2 artefacts (per-repo HLD+LLD)
      for (int i = 0; i < repoArchDocs.size(); i++) {
        RepoArchitectureDoc rad = repoArchDocs.get(i);
        String full = i < repoFullNames.size() ? repoFullNames.get(i) : ("repo-" + rad.getRepoId());
        String shortName = repoShortName(full);
        workspace.writeAndCommit(
            projectId,
            "repos/" + shortName + "/architecture.md",
            rad.getContent(),
            "repo-arch: " + full + " v" + rad.getVersion());
      }

      // architecture.md — Step 3 combined doc
      workspace.writeAndCommit(
          projectId, "architecture.md", archMarkdown, "architecture: combined regenerated");

      LlmProvider provider =
          projectRepository
              .findById(projectId)
              .map(Project::getAiProvider)
              .orElse(LlmProvider.OPENAI);
      String summary = briefService.summarise(projectId, archMarkdown, provider);
      if (summary != null && !summary.isBlank()) {
        workspace.writeAndCommit(projectId, "summary.md", summary, "summary: regenerated");
      }
    } catch (Exception e) {
      log.warn("Failed to persist project workspace files for {}: {}", projectId, e.getMessage());
    }
  }

  private static String repoShortName(String full) {
    String s = full.contains("/") ? full.substring(full.indexOf('/') + 1) : full;
    return s.replaceAll("[^A-Za-z0-9._-]", "-");
  }

  /**
   * E1.2 — mirror each per-repo codemap to {@code .executespec/arch/codemap.md} on the
   * corresponding customer repo. Open via PR by default; the per-repo {@code executespec_pr_mode}
   * flag (V132) lets a customer opt into DIRECT_PUSH for trusted high-frequency writes.
   *
   * <p>This is the "real refactor" called for in plan §1.7 — but done as a dual-write rather than a
   * hard cutover. The DB cache + local workspace write continue (so the planner has fresh context
   * immediately); the customer-visible {@code .executespec/} mirror is best-effort and lags by one
   * PR-merge cycle. A future "exclusive customer-repo authority" follow-up can drop the local
   * workspace write once every linked repo has bootstrapped.
   *
   * <p>Failures here never fail the codemap regen flow — codemap is the planner's primary input and
   * must always be persisted to the DB.
   */
  void publishCodemapsToCustomerRepos(
      String projectId,
      List<ProjectCodeMap> codeMaps,
      List<String> repoFullNames,
      String patOverride) {
    com.navneet.executespec.folder.ExecutespecFolderWriter writer =
        folderWriterProvider.getIfAvailable();
    if (writer == null) {
      log.debug(
          "[{}] ExecutespecFolderWriter not on classpath; skipping codemap publish", projectId);
      return;
    }
    String projectName = projectRepository.findById(projectId).map(Project::getName).orElse(null);
    String slug = com.navneet.executespec.folder.BootstrapService.slugify(projectName);
    com.navneet.executespec.activity.ActivityEventEmitter emitter =
        activityEmitterProvider.getIfAvailable();

    for (int i = 0; i < codeMaps.size(); i++) {
      ProjectCodeMap cm = codeMaps.get(i);
      ProjectRepo repo = repoRepo.findById(cm.getRepoId()).orElse(null);
      if (repo == null) continue;
      String fullName =
          i < repoFullNames.size() ? repoFullNames.get(i) : repo.getGithubRepoFullName();
      try {
        java.util.Optional<com.navneet.executespec.folder.ExecutespecFolderWriter.PullRequestInfo>
            result =
                writer.writeFiles(
                    repo,
                    slug,
                    java.util.Map.of(".executespec/arch/codemap.md", cm.getContent()),
                    "ExecuteSpec: codemap regeneration for " + fullName + " v" + cm.getVersion(),
                    "Auto-generated codemap update from ExecuteSpec.\n\n"
                        + "Triggered by: project architecture regen.\n"
                        + "Generated from SHA: "
                        + shortSha(cm.getGeneratedFromSha())
                        + "\n\n"
                        + "## What happens after you merge this?\n\n"
                        + "* **Merging is optional.** This PR refreshes "
                        + "`.executespec/arch/codemap.md` so the cached arch "
                        + "matches the latest source. ExecuteSpec keeps a "
                        + "DB-side copy regardless — the file in your repo "
                        + "is the customer-facing artefact.\n"
                        + "* **No empty-PR cycle.** When you do merge, the next "
                        + "analyse will check whether the codemap content has "
                        + "actually changed before opening another PR. If the "
                        + "default-branch HEAD bumped but the codemap is "
                        + "byte-identical, the writer skips the PR entirely.\n"
                        + "* **Skip the PR step entirely.** If you'd rather "
                        + "these regenerations write straight to the default "
                        + "branch instead of through a PR, ask a project admin "
                        + "to flip this repo's `executespec_pr_mode` to "
                        + "`DIRECT_PUSH`.",
                    patOverride);
        if (result.isEmpty()) {
          log.warn(
              "[{}] codemap publish to {} returned empty (writer logs the cause)",
              projectId,
              fullName);
          continue;
        }
        if (emitter != null) {
          emitter.emit(
              tryParseUuid(projectId),
              com.navneet.executespec.activity.ActivityEventType.CODEMAP_REGENERATED,
              null, // codemap regen is system-initiated
              "REPO",
              repo.getId().toString(),
              "Codemap published to "
                  + fullName
                  + " (v"
                  + cm.getVersion()
                  + ", "
                  + (result.get().openedNewPr() ? "PR" : "direct push")
                  + ")",
              java.util.Map.of(
                  "repoFullName",
                  fullName,
                  "codemapVersion",
                  cm.getVersion(),
                  "openedNewPr",
                  result.get().openedNewPr(),
                  "prUrl",
                  result.get().prUrl() == null ? "" : result.get().prUrl()));
        }

        // V169 (2026-05-02) — persist the architecture PR so the project
        // dashboard's Pull requests tab surfaces it. Skip the DIRECT_PUSH
        // branch (no PR was opened, prUrl is null). Best-effort: any
        // failure logs and continues so codemap publish itself isn't blocked.
        if (result.get().openedNewPr() && result.get().prUrl() != null) {
          com.navneet.executespec.project.pr.PullRequestRecordService prRec =
              prRecordServiceProvider.getIfAvailable();
          if (prRec != null) {
            try {
              prRec.recordCreated(
                  null,
                  projectId,
                  repo.getId(),
                  fullName,
                  result.get().prUrl(),
                  result.get().branchName(),
                  null,
                  com.navneet.executespec.project.pr.PullRequestRecord.Kind.ARCHITECTURE);
            } catch (Exception recEx) {
              log.warn(
                  "[{}] could not record architecture PR for {}: {}",
                  projectId,
                  fullName,
                  recEx.getMessage());
            }
          }
        }
      } catch (Exception ex) {
        log.warn("[{}] codemap publish to {} failed: {}", projectId, fullName, ex.getMessage());
      }
    }
  }

  private static UUID tryParseUuid(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** Latest per-repo architecture doc (for reads + the Sync re-run path). */
  public Optional<RepoArchitectureDoc> latestRepoArch(String projectId, UUID repoId) {
    return repoArchRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repoId);
  }

  /**
   * Per-repo rerun of Steps 1 + 2 (codemap + per-repo arch doc). Used by the Sync button: "fresh
   * pull" of a single repo, regenerating its codemap and arch doc, leaving the combined project
   * arch doc alone. Caller must then mirror the updated doc to disk via {@link
   * ProjectWorkspaceService#writeAndCommit} if UI visibility is required.
   *
   * @return the new or reused per-repo architecture doc
   */
  /** Same connection-leak reasoning as {@link #generate} — no outer tx. */
  public RepoArchitectureDoc regenerateForRepo(String projectId, UUID repoId, String patOverride) {
    ProjectRepo repo =
        repoRepo
            .findById(repoId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found: " + repoId));
    // Ensure the repo is actually linked to this project.
    boolean linked = linkRepo.findByProjectIdAndRepoId(projectId, repoId).isPresent();
    if (!linked) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "repo " + repoId + " is not linked to project " + projectId);
    }

    LlmProvider provider =
        projectRepository
            .findById(projectId)
            .map(Project::getAiProvider)
            .orElse(LlmProvider.OPENAI);

    ProjectCodeMap cm = ensureCodeMap(projectId, repo, repo.getDefaultBranch(), patOverride);
    RepoArchitectureDoc rad = ensureRepoArchDoc(projectId, repo, cm, provider);

    // Disk mirror for just this repo — leaves the combined architecture.md + summary.md untouched.
    try {
      workspace.initIfMissing(projectId);
      String shortName = repoShortName(repo.getGithubRepoFullName());
      workspace.writeAndCommit(
          projectId,
          "codemaps/" + shortName + ".md",
          cm.getContent(),
          "codemap: " + repo.getGithubRepoFullName() + " v" + cm.getVersion() + " (sync)");
      workspace.writeAndCommit(
          projectId,
          "repos/" + shortName + "/architecture.md",
          rad.getContent(),
          "repo-arch: " + repo.getGithubRepoFullName() + " v" + rad.getVersion() + " (sync)");
    } catch (Exception e) {
      log.warn(
          "[{}] repo-sync disk mirror failed for {}: {}",
          projectId,
          repo.getGithubRepoFullName(),
          e.getMessage());
    }
    return rad;
  }

  public Optional<ProjectArchitectureDoc> latest(String projectId) {
    return archDocRepo.findFirstByProjectIdOrderByVersionDesc(projectId);
  }

  public List<ProjectArchitectureDoc> versions(String projectId) {
    return archDocRepo.findByProjectIdOrderByVersionDesc(projectId);
  }

  @Transactional
  public ProjectArchitectureDoc annotate(String projectId, String annotation) {
    ProjectArchitectureDoc doc =
        archDocRepo
            .findFirstByProjectIdOrderByVersionDesc(projectId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No architecture doc — generate one first"));
    doc.setUserAnnotations(annotation);
    return archDocRepo.save(doc);
  }

  public Optional<ProjectCodeMap> latestCodeMap(String projectId, UUID repoId) {
    return codeMapRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repoId);
  }
}
