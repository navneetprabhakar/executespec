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
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.RunModelOverrides;

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
  private final ProjectProviderResolver projectProviderResolver;
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

  public Optional<ProjectArchitectureDoc> findById(UUID id) {
    return id == null ? Optional.empty() : archDocRepo.findById(id);
  }

  public ProjectArchitectureDoc generate(String projectId, String branchOverride) {
    return generate(projectId, branchOverride, null);
  }

  /**
   * Generate architecture using an explicit run provider when supplied. Run-scoped provider/model
   * pins must also cover this pre-planner refresh; otherwise a pinned run can silently spend on the
   * project's default provider before the worker starts.
   */
  public ProjectArchitectureDoc generate(
      String projectId, String branchOverride, String patOverride, LlmProvider providerOverride) {
    return generateInternal(
        projectId, branchOverride, patOverride, providerOverride, null, null, true);
  }

  /** Confirmed Project Chat path with immutable initiating-actor attribution. */
  public ProjectArchitectureDoc generate(
      String projectId,
      String branchOverride,
      String patOverride,
      LlmProvider providerOverride,
      String actorUserId) {
    return generateInternal(
        projectId, branchOverride, patOverride, providerOverride, actorUserId, null, true);
  }

  /** Confirmed action path: all downstream LLM calls share one durable hard budget. */
  public ProjectArchitectureDoc generate(
      String projectId,
      String branchOverride,
      String patOverride,
      LlmProvider providerOverride,
      String actorUserId,
      LlmExecutionReference confirmedActionReference) {
    return generateInternal(
        projectId,
        branchOverride,
        patOverride,
        providerOverride,
        actorUserId,
        confirmedActionReference,
        true);
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
    return generateInternal(projectId, branchOverride, patOverride, null, null, null, true);
  }

  /**
   * Durable V353 job path. Customer-repository publication is suppressed because that external PR
   * side effect does not yet carry the job's database fencing token; the generated codemap,
   * architecture rows, workspace documents, and UI result remain complete.
   */
  public ProjectArchitectureDoc generateForDurableJob(
      String projectId,
      String patOverride,
      LlmProvider provider,
      String actorUserId,
      LlmExecutionReference executionReference) {
    return generateInternal(
        projectId, null, patOverride, provider, actorUserId, executionReference, false);
  }

  public record StagedCodeMap(
      UUID repoId, String repoFullName, String branch, String revision, String content) {}

  public record StagedRepoArchitecture(
      UUID repoId,
      String repoFullName,
      String branch,
      String revision,
      String content,
      String modelUsed,
      int inputTokens,
      int outputTokens,
      double costUsd,
      boolean fallback) {}

  public record StagedProjectArchitecture(
      String content,
      String generatedFromShas,
      String generatedFromBranches,
      double costUsd,
      String modelUsed,
      int inputTokens,
      int outputTokens,
      boolean fallback) {}

  public record StagedArtifacts(
      int schemaVersion,
      String projectId,
      List<StagedCodeMap> codeMaps,
      List<StagedRepoArchitecture> repoArchitectures,
      StagedProjectArchitecture projectArchitecture,
      String summary,
      String qualityStatus,
      List<String> qualityEvidence) {}

  /**
   * Generate entirely in memory from an immutable V353 source snapshot. This method performs no
   * repository save, workspace write, retention prune, activity event, or customer-repository PR.
   * Only {@link ProjectArchitectureJobPublisher} can make this stage visible.
   */
  public StagedArtifacts stageForDurableJob(
      ProjectArchitectureJobStore.Job job,
      ProjectArchitectureJobService.SourceSnapshot snapshot,
      java.util.Map<UUID, String> credentials) {
    return stageForDurableJob(job, snapshot, credentials, () -> {});
  }

  StagedArtifacts stageForDurableJob(
      ProjectArchitectureJobStore.Job job,
      ProjectArchitectureJobService.SourceSnapshot snapshot,
      java.util.Map<UUID, String> credentials,
      Runnable cooperativeFence) {
    LlmProvider provider = LlmProvider.valueOf(job.requestedProvider());
    if (!java.util.Objects.equals(job.repoArchitectureModel(), job.projectArchitectureModel())) {
      throw new IllegalStateException(
          "Repository and project architecture routes must share the configured PLANNING model");
    }
    java.util.Map<String, String> exactRoutes =
        java.util.Map.of(
            RunModelOverrides.key(provider, AgentTier.PLANNING), job.projectArchitectureModel());
    java.util.Map<String, String> previous = RunModelOverrides.capture();
    RunModelOverrides.bind(exactRoutes);
    try {
      List<StagedCodeMap> codeMaps = new ArrayList<>();
      List<StagedRepoArchitecture> repoArchitectures = new ArrayList<>();
      List<RepoArchitectureDoc> transientRepoDocs = new ArrayList<>();
      List<String> repoNames = new ArrayList<>();
      List<String> warnings = new ArrayList<>();
      java.util.LinkedHashMap<String, String> shas = new java.util.LinkedHashMap<>();
      java.util.LinkedHashMap<String, String> branches = new java.util.LinkedHashMap<>();
      LlmExecutionReference repoReference =
          new LlmExecutionReference(
              job.repoExecutionContextId(),
              com.navneet.executespec.executioncontext.LlmExecutionScope.customer(job.orgId()));
      LlmExecutionReference projectReference =
          new LlmExecutionReference(
              job.projectExecutionContextId(),
              com.navneet.executespec.executioncontext.LlmExecutionScope.customer(job.orgId()));

      for (ProjectArchitectureJobService.SourceRepo source : snapshot.repositories()) {
        cooperativeFence.run();
        ProjectRepo repo =
            repoRepo
                .findById(source.repoId())
                .orElseThrow(() -> new IllegalStateException("Snapshotted repository was removed"));
        String credential = credentials.get(source.repoId());
        if (credential == null || credential.isBlank()) {
          throw new IllegalStateException("No current credential for snapshotted repository");
        }
        ProjectCodeMap latest =
            codeMapRepo
                .findFirstByProjectIdAndRepoIdOrderByVersionDesc(job.projectId(), source.repoId())
                .filter(cm -> source.revision().equals(cm.getGeneratedFromSha()))
                .orElse(null);
        String codeMapContent;
        if (latest != null && latest.getContent() != null && !latest.getContent().isBlank()) {
          codeMapContent = latest.getContent();
        } else {
          CodeMapGenerator.Result generated =
              codeMapGenerator.generateWithSha(source.repoFullName(), source.branch(), credential);
          cooperativeFence.run();
          if (!source.revision().equals(generated.sha())) {
            throw new IllegalStateException("Repository changed while its codemap was generated");
          }
          codeMapContent = generated.markdown();
        }
        if (codeMapContent == null || codeMapContent.isBlank()) {
          throw new IllegalStateException("Generated codemap is empty");
        }
        codeMaps.add(
            new StagedCodeMap(
                source.repoId(),
                source.repoFullName(),
                source.branch(),
                source.revision(),
                codeMapContent));
        shas.put(source.repoFullName(), source.revision());
        branches.put(source.repoFullName(), source.branch());

        RepoArchitectureGenerator.Result generatedRepo;
        boolean repoFallback = false;
        try {
          cooperativeFence.run();
          generatedRepo =
              repoArchGenerator.generate(
                  job.projectId(),
                  source.repoFullName(),
                  codeMapContent,
                  provider,
                  job.requestedByUserId(),
                  repoReference);
          cooperativeFence.run();
        } catch (RepoArchitectureGenerator.ProviderOutputIncompleteException incomplete) {
          throw incomplete;
        } catch (Exception failure) {
          repoFallback = true;
          warnings.add("REPOSITORY_ARCHITECTURE_FALLBACK:" + source.repoId());
          generatedRepo =
              new RepoArchitectureGenerator.Result(
                  fallbackRepoArchitectureMarkdown(source.repoFullName(), codeMapContent),
                  0,
                  0,
                  "fallback");
        }
        double repoCost =
            repoFallback
                ? 0.0
                : costTracker.computeCost(
                    providerNameFor(provider),
                    generatedRepo.modelUsed(),
                    generatedRepo.inputTokens(),
                    generatedRepo.outputTokens());
        repoArchitectures.add(
            new StagedRepoArchitecture(
                source.repoId(),
                source.repoFullName(),
                source.branch(),
                source.revision(),
                generatedRepo.markdown(),
                generatedRepo.modelUsed(),
                generatedRepo.inputTokens(),
                generatedRepo.outputTokens(),
                repoCost,
                repoFallback));
        transientRepoDocs.add(
            RepoArchitectureDoc.builder()
                .projectId(job.projectId())
                .repoId(source.repoId())
                .version(1)
                .content(generatedRepo.markdown())
                .generatedFromSha(source.revision())
                .modelUsed(generatedRepo.modelUsed())
                .inputTokens(generatedRepo.inputTokens())
                .outputTokens(generatedRepo.outputTokens())
                .costUsd(repoCost)
                .build());
        repoNames.add(source.repoFullName());
      }

      ArchitectureDocGenerator.Result combined;
      boolean combinedFallback = false;
      try {
        cooperativeFence.run();
        combined =
            archDocGenerator.generate(
                job.projectId(),
                transientRepoDocs,
                repoNames,
                provider,
                job.requestedByUserId(),
                projectReference);
        cooperativeFence.run();
      } catch (RepoArchitectureGenerator.ProviderOutputIncompleteException incomplete) {
        throw incomplete;
      } catch (Exception failure) {
        combinedFallback = true;
        warnings.add("PROJECT_ARCHITECTURE_DETERMINISTIC_FALLBACK");
        java.util.LinkedHashMap<String, String> byRepo = new java.util.LinkedHashMap<>();
        for (int i = 0; i < transientRepoDocs.size(); i++) {
          byRepo.put(repoNames.get(i), transientRepoDocs.get(i).getContent());
        }
        combined =
            new ArchitectureDocGenerator.Result(
                ArchitectureDocGenerator.deterministicCombined(job.projectId(), byRepo),
                0,
                0,
                "deterministic");
      }
      if (combined.markdown() == null || combined.markdown().isBlank()) {
        throw new IllegalStateException("Generated project architecture is empty");
      }
      double combinedCost =
          combinedFallback
              ? 0.0
              : costTracker.computeCost(
                  providerNameFor(provider),
                  combined.modelUsed(),
                  combined.inputTokens(),
                  combined.outputTokens());
      StagedProjectArchitecture projectArchitecture =
          new StagedProjectArchitecture(
              combined.markdown(),
              writeJson(shas),
              writeJson(branches),
              combinedCost,
              combined.modelUsed(),
              combined.inputTokens(),
              combined.outputTokens(),
              combinedFallback);

      // Brief generation is deliberately excluded from the durable provider context. Sharing the
      // PLANNING context with this LIGHTWEIGHT operation would violate the exact-route contract.
      String summary = deterministicSummary(combined.markdown());
      return new StagedArtifacts(
          1,
          job.projectId(),
          List.copyOf(codeMaps),
          List.copyOf(repoArchitectures),
          projectArchitecture,
          summary,
          warnings.isEmpty() ? "QUALIFIED" : "WARNING",
          List.copyOf(warnings));
    } finally {
      RunModelOverrides.bind(previous);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not stage architecture source evidence", failure);
    }
  }

  private static String deterministicSummary(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    String first =
        markdown
            .lines()
            .map(String::trim)
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .findFirst()
            .orElse("Project architecture is available.");
    return first.length() <= 500 ? first : first.substring(0, 500);
  }

  private ProjectArchitectureDoc generateInternal(
      String projectId,
      String branchOverride,
      String patOverride,
      LlmProvider providerOverride,
      String actorUserId,
      LlmExecutionReference confirmedActionReference,
      boolean publishToCustomerRepos) {
    rejectDirectPublication();
    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    if (links.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Project has no linked repos — link repos before generating architecture");
    }

    // RR-457 — decide up front which linked sources this pipeline can actually read. Every step
    // below constructs "https://github.com/" + githubRepoFullName and calls GitHubRepoService, so a
    // Bitbucket or LOCAL source used to be fed through as the literal repository "null": codemap,
    // per-repo architecture, the combined document and downstream planner context were all
    // generated against a repository that does not exist, or the whole regen threw with a message
    // that never named the real cause. Non-GitHub sources are now excluded explicitly and the
    // exclusion is carried into the document instead of being silently absent.
    SourceAdmission admission = admitSources(links);
    if (admission.generatable().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_IMPLEMENTED,
          "Architecture generation is not available for this project's linked sources: "
              + admission.excludedSummary()
              + ". Code intelligence currently reads GitHub sources only.");
    }

    LlmProvider provider = projectProviderResolver.resolve(projectId, providerOverride);

    boolean hasOverride = branchOverride != null && !branchOverride.isBlank();
    List<ProjectCodeMap> codeMaps = new ArrayList<>();
    List<String> repoFullNames = new ArrayList<>();
    List<RepoArchitectureDoc> repoArchDocs = new ArrayList<>();
    java.util.LinkedHashMap<String, String> shasByRepo = new java.util.LinkedHashMap<>();
    // V192 (PR-M, 2026-05-13) — track the branch each codemap was generated from so
    // StalenessService can probe HEAD of the same branch instead of always assuming
    // defaultBranch (fixes permanent-STALE for non-default-branch regenerations).
    java.util.LinkedHashMap<String, String> branchesByRepo = new java.util.LinkedHashMap<>();

    for (ProjectRepo repo : admission.generatable()) {
      String effectiveBranch = hasOverride ? branchOverride : repo.getDefaultBranch();

      // Step 1: per-repo codemap (idempotent by SHA)
      ProjectCodeMap cm = ensureCodeMap(projectId, repo, effectiveBranch, patOverride);
      codeMaps.add(cm);
      repoFullNames.add(repo.getGithubRepoFullName());
      shasByRepo.put(repo.getGithubRepoFullName(), cm.getGeneratedFromSha());
      branchesByRepo.put(repo.getGithubRepoFullName(), effectiveBranch);

      // Step 2: per-repo architecture doc (idempotent by codemap)
      RepoArchitectureDoc rad =
          ensureRepoArchDoc(projectId, repo, cm, provider, actorUserId, confirmedActionReference);
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
    boolean allRepoArchitecturesReusable =
        repoArchDocs.stream().allMatch(ProjectArchitectureService::isReusableRepoArchitecture);
    ProjectArchitectureDoc cached =
        archDocRepo
            .findFirstByProjectIdOrderByVersionDesc(projectId)
            .filter(d -> d.getContent() != null && !d.getContent().isBlank())
            .filter(ProjectArchitectureService::isReusableProjectArchitecture)
            .filter(d -> allRepoArchitecturesReusable)
            .filter(d -> fingerprintMatches(d.getGeneratedFromShas(), currentFingerprint))
            .orElse(null);
    if (cached != null) {
      log.info(
          "[{}] arch regen short-circuit — same SHAs as v{} (fingerprint match); skipped LLM",
          projectId,
          cached.getVersion());
      return cached;
    }

    // Step 3: combined architecture doc (always regenerates)
    ArchitectureDocGenerator.Result combined;
    try {
      combined =
          archDocGenerator.generate(
              projectId,
              repoArchDocs,
              repoFullNames,
              provider,
              actorUserId,
              confirmedActionReference);
    } catch (Exception e) {
      log.warn(
          "[{}] LLM combined-arch failed (type={}) — falling back to deterministic combine",
          projectId,
          e.getClass().getSimpleName());
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

    // RR-457 — a document that silently omits half the project reads as complete coverage. When
    // some linked sources could not be read, say so inside the document itself.
    String combinedMarkdown = admission.appendExclusionNotice(combined.markdown());

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
            .content(combinedMarkdown)
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
        "[{}] 3-step arch generation complete: {} repos, combined v{} ({} chars, {} in / {} out"
            + " tokens)",
        projectId,
        codeMaps.size(),
        nextVersion,
        combinedMarkdown.length(),
        combined.inputTokens(),
        combined.outputTokens());

    persistToWorkspace(
        projectId,
        codeMaps,
        repoArchDocs,
        repoFullNames,
        combinedMarkdown,
        provider,
        actorUserId,
        confirmedActionReference);
    workspaceWarmer.warmDiskCache(projectId);
    // E1.2 — also publish each per-repo codemap to .executespec/arch/codemap.md
    // on the customer repo. Best-effort: never fails the regen flow.
    if (publishToCustomerRepos) {
      publishCodemapsToCustomerRepos(projectId, codeMaps, repoFullNames, patOverride);
    }
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

    if (latest.isPresent() && currentSha != null) {
      String generatedSha = latest.get().getGeneratedFromSha();
      if (currentSha.equals(generatedSha)) {
        log.info(
            "[{}] codemap for {} up-to-date @ sha={} — reusing v{}",
            projectId,
            repo.getGithubRepoFullName(),
            shortSha(currentSha),
            latest.get().getVersion());
        return latest.get();
      }
      // RL-2026-05-025 — break the regenerate loop. ExecuteSpec publishes codemaps to the customer
      // repo under .executespec/, which moves HEAD; without this, the next sync sees HEAD ≠
      // generatedFromSha and regenerates (→ publish → HEAD moves → regen → …). If the ONLY change
      // since this codemap was generated is under .executespec/, the source is unchanged — reuse +
      // advance the stored SHA so subsequent syncs short-circuit on the equality check above.
      if (gitHub.diffConfinedTo(
          repo.getGithubRepoFullName(), generatedSha, currentSha, ".executespec/", patOverride)) {
        log.info(
            "[{}] codemap for {} unchanged except .executespec/ ({} → {}) — reusing v{}, advancing"
                + " SHA",
            projectId,
            repo.getGithubRepoFullName(),
            shortSha(generatedSha),
            shortSha(currentSha),
            latest.get().getVersion());
        ProjectCodeMap cm = latest.get();
        cm.setGeneratedFromSha(currentSha);
        return codeMapRepo.save(cm);
      }
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
        "[{}] codemap for {} regenerated as v{} ({} chars) @ sha={}",
        projectId,
        repo.getGithubRepoFullName(),
        nextVersion,
        result.markdown().length(),
        shortSha(cm.getGeneratedFromSha()));
    return cm;
  }

  /**
   * Step 2 helper — reuse the latest repo arch doc when its {@code sourceCodeMapId} matches the
   * codemap we just produced; otherwise regenerate via LLM and bump the version.
   */
  private RepoArchitectureDoc ensureRepoArchDoc(
      String projectId, ProjectRepo repo, ProjectCodeMap codeMap, LlmProvider provider) {
    return ensureRepoArchDoc(projectId, repo, codeMap, provider, null, null);
  }

  private RepoArchitectureDoc ensureRepoArchDoc(
      String projectId,
      ProjectRepo repo,
      ProjectCodeMap codeMap,
      LlmProvider provider,
      String actorUserId,
      LlmExecutionReference confirmedActionReference) {
    Optional<RepoArchitectureDoc> latest =
        repoArchRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repo.getId());
    if (latest.isPresent()
        && codeMap.getId() != null
        && codeMap.getId().equals(latest.get().getSourceCodeMapId())
        && isReusableRepoArchitecture(latest.get())) {
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
          repoArchGenerator.generate(
              projectId,
              repo.getGithubRepoFullName(),
              codeMap.getContent(),
              provider,
              actorUserId,
              confirmedActionReference);
    } catch (Exception e) {
      log.warn(
          "[{}] repo-arch LLM failed for {} (type={}) — falling back to codemap as arch content",
          projectId,
          repo.getGithubRepoFullName(),
          e.getClass().getSimpleName());
      rar =
          new RepoArchitectureGenerator.Result(
              fallbackRepoArchitectureMarkdown(repo.getGithubRepoFullName(), codeMap.getContent()),
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

  static boolean isReusableRepoArchitecture(RepoArchitectureDoc doc) {
    if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) return false;
    String model = doc.getModelUsed();
    return model != null
        && !model.isBlank()
        && !"fallback".equalsIgnoreCase(model)
        && !doc.getContent().contains("LLM generation failed");
  }

  static String fallbackRepoArchitectureMarkdown(String repoFullName, String codeMap) {
    return "# "
        + repoFullName
        + " — Architecture\n\n"
        + "_LLM generation failed. Showing raw codemap instead._\n\n"
        + codeMap;
  }

  static boolean isReusableProjectArchitecture(ProjectArchitectureDoc doc) {
    if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) return false;
    String content = doc.getContent();
    return !content.startsWith("# Project architecture (deterministic)")
        && !content.contains("LLM generation failed");
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
      String archMarkdown,
      LlmProvider provider,
      String actorUserId,
      LlmExecutionReference confirmedActionReference) {
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

      String summary =
          summariseBrief(
              briefService,
              projectId,
              archMarkdown,
              provider,
              actorUserId,
              confirmedActionReference);
      if (summary != null && !summary.isBlank()) {
        workspace.writeAndCommit(projectId, "summary.md", summary, "summary: regenerated");
      }
    } catch (Exception e) {
      log.warn(
          "Failed to persist project workspace files for {} errorClass={}",
          projectId,
          e.getClass().getSimpleName());
    }
  }

  static String summariseBrief(
      ProjectBriefService briefService,
      String projectId,
      String archMarkdown,
      LlmProvider provider,
      String actorUserId,
      LlmExecutionReference confirmedActionReference) {
    return briefService.summarise(
        projectId, archMarkdown, provider, actorUserId, confirmedActionReference);
  }

  /**
   * RR-457 — split linked sources into the ones this GitHub-shaped pipeline can genuinely read and
   * the ones it cannot, with a stated reason per exclusion.
   *
   * <p>This is deliberately an explicit typed unavailable result rather than Bitbucket support. The
   * per-repo path is GitHub API calls all the way down — {@code CodeMapGenerator} builds {@code
   * https://github.com/<name>} and reads blobs through {@code GitHubRepoService}. Making it
   * provider-neutral means routing through {@code ScmRepositoryClient} and giving the codemap
   * reader a provider-neutral file/blob contract; that is a real port, not a rename, and it belongs
   * with the repo-client lane. Until then the honest behaviour is to name the gap rather than
   * fabricate a {@code github.com/null} repository and present the output as project-wide truth.
   */
  private SourceAdmission admitSources(List<ProjectRepoLink> links) {
    List<ProjectRepo> generatable = new ArrayList<>();
    List<String> excluded = new ArrayList<>();
    for (ProjectRepoLink link : links) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) {
        excluded.add("linked source " + link.getRepoId() + " (registry row not found)");
        continue;
      }
      String reason = unsupportedSourceReason(repo);
      if (reason == null) {
        generatable.add(repo);
      } else {
        excluded.add(sourceLabel(repo) + " (" + reason + ")");
      }
    }
    return new SourceAdmission(List.copyOf(generatable), List.copyOf(excluded));
  }

  /** Null when the source can be read by the GitHub-shaped code-intelligence pipeline. */
  static String unsupportedSourceReason(ProjectRepo repo) {
    if (ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())) {
      return "LOCAL source — there is no remote to read a codemap from";
    }
    String provider = repo.getScmProvider();
    if (provider != null && !"GITHUB".equalsIgnoreCase(provider)) {
      return provider + " source — code intelligence has no provider-neutral reader yet";
    }
    if (repo.getGithubRepoFullName() == null || repo.getGithubRepoFullName().isBlank()) {
      return "no GitHub repository name recorded";
    }
    return null;
  }

  private static String sourceLabel(ProjectRepo repo) {
    if (repo.getSourceKey() != null && !repo.getSourceKey().isBlank()) return repo.getSourceKey();
    if (repo.getGithubRepoFullName() != null && !repo.getGithubRepoFullName().isBlank()) {
      return repo.getGithubRepoFullName();
    }
    return String.valueOf(repo.getId());
  }

  /**
   * The readable and unreadable halves of a project's linked sources.
   *
   * @param generatable sources the pipeline may read, in link order
   * @param excluded human-readable "name (reason)" strings for sources it may not
   */
  record SourceAdmission(List<ProjectRepo> generatable, List<String> excluded) {

    String excludedSummary() {
      return String.join("; ", excluded);
    }

    /** Append a coverage caveat so the document never implies it covers every linked source. */
    String appendExclusionNotice(String markdown) {
      String base = markdown == null ? "" : markdown;
      if (excluded.isEmpty()) return base;
      StringBuilder sb = new StringBuilder(base);
      sb.append("\n\n## Sources excluded from this architecture\n\n");
      sb.append("The following linked sources were not read, so nothing below describes them:\n\n");
      for (String entry : excluded) sb.append("* ").append(entry).append('\n');
      return sb.toString();
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
    rejectDirectPublication();
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
    // RR-457 — same GitHub-only boundary as the project-wide pipeline. Without this the Bitbucket
    // and LOCAL cases reach ensureCodeMap and read "https://github.com/null".
    String unsupported = unsupportedSourceReason(repo);
    if (unsupported != null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_IMPLEMENTED,
          "Architecture generation is not available for " + sourceLabel(repo) + ": " + unsupported);
    }

    LlmProvider provider = projectProviderResolver.resolve(projectId);

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

  private static void rejectDirectPublication() {
    throw new IllegalStateException(
        "Direct architecture publication is retired; admit a durable architecture job");
  }

  public Optional<ProjectArchitectureDoc> latest(String projectId) {
    return archDocRepo.findFirstByProjectIdOrderByVersionDesc(projectId);
  }

  /**
   * Reads the codemap bound to one exact architecture-source revision. Callers generating derived
   * artifacts must not mix a newer latest codemap into an older architecture snapshot.
   */
  public Optional<ProjectCodeMap> codeMapForSourceSha(
      String projectId, UUID repoId, String sourceSha) {
    if (projectId == null || repoId == null || sourceSha == null || sourceSha.isBlank()) {
      return Optional.empty();
    }
    return codeMapRepo.findFirstByProjectIdAndRepoIdAndGeneratedFromShaOrderByVersionDesc(
        projectId, repoId, sourceSha);
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

  /**
   * Latest combined (cross-repo) architecture doc for a project — read-only accessor used by the
   * run-context composer to inject the high-level design into the planner (flag {@code
   * codeintel.inject-arch.enabled}). Mirrors {@link #latestCodeMap}.
   */
  public Optional<ProjectArchitectureDoc> latestArchitecture(String projectId) {
    return archDocRepo.findFirstByProjectIdOrderByVersionDesc(projectId);
  }
}
