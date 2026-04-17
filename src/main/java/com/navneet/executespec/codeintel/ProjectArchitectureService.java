package com.navneet.executespec.codeintel;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private final CodeMapGenerator codeMapGenerator;
    private final RepoArchitectureGenerator repoArchGenerator;
    private final ArchitectureDocGenerator archDocGenerator;
    private final com.navneet.executespec.repo.GitHubRepoService gitHub;
    private final ProjectWorkspaceService workspace;
    private final com.navneet.executespec.project.workspace.ProjectWorkspaceWarmer workspaceWarmer;
    private final ProjectBriefService briefService;
    private final ObjectMapper objectMapper;

    /**
     * Regenerate codemaps for every linked repo, then synthesise a fresh
     * architecture doc. Versioned: existing docs are preserved.
     */
    @Transactional
    public ProjectArchitectureDoc generate(String projectId) {
        return generate(projectId, null, null);
    }

    @Transactional
    public ProjectArchitectureDoc generate(String projectId, String branchOverride) {
        return generate(projectId, branchOverride, null);
    }

    /**
     * Regenerate codemaps for every linked repo using the supplied branch
     * (falls back to each repo's default when {@code branchOverride} is null
     * or blank) and the supplied PAT (falls back to the system PAT when
     * {@code patOverride} is null).
     */
    /**
     * 3-step architecture generation pipeline:
     *
     * <ol>
     *   <li><b>Step 1</b> — per-repo codemap. Idempotent: reuse the latest
     *       codemap when the current branch HEAD matches
     *       {@code generatedFromSha}. Saves a new version otherwise.</li>
     *   <li><b>Step 2</b> — per-repo architecture doc (HLD + LLD, natural
     *       language) via {@link RepoArchitectureGenerator}. Idempotent the
     *       same way — reuse if the source codemap id hasn't changed.</li>
     *   <li><b>Step 3</b> — combined project architecture doc synthesised
     *       from the per-repo arch docs via {@link ArchitectureDocGenerator}.
     *       Always produces a new version.</li>
     * </ol>
     *
     * <p>All artefacts land in the project workspace under
     * {@code workspace/projects/<id>/}:
     * <pre>
     *   codemaps/&lt;repo&gt;.md
     *   repos/&lt;repo&gt;/architecture.md   <- NEW, per-repo
     *   architecture.md                   <- combined
     *   summary.md
     * </pre>
     */
    @Transactional
    public ProjectArchitectureDoc generate(String projectId, String branchOverride, String patOverride) {
        List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
        if (links.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Project has no linked repos — link repos before generating architecture");
        }

        LlmProvider provider = projectRepository.findById(projectId)
                .map(Project::getAiProvider)
                .orElse(LlmProvider.OPENAI);

        boolean hasOverride = branchOverride != null && !branchOverride.isBlank();
        List<ProjectCodeMap> codeMaps = new ArrayList<>();
        List<String> repoFullNames = new ArrayList<>();
        List<RepoArchitectureDoc> repoArchDocs = new ArrayList<>();
        java.util.LinkedHashMap<String, String> shasByRepo = new java.util.LinkedHashMap<>();

        for (ProjectRepoLink link : links) {
            ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
            if (repo == null) continue;
            String effectiveBranch = hasOverride ? branchOverride : repo.getDefaultBranch();

            // ── Step 1: per-repo codemap (idempotent by SHA) ───────────────────
            ProjectCodeMap cm = ensureCodeMap(projectId, repo, effectiveBranch, patOverride);
            codeMaps.add(cm);
            repoFullNames.add(repo.getGithubRepoFullName());
            shasByRepo.put(repo.getGithubRepoFullName(), cm.getGeneratedFromSha());

            // ── Step 2: per-repo architecture doc (idempotent by codemap) ─────
            RepoArchitectureDoc rad = ensureRepoArchDoc(projectId, repo, cm, provider);
            repoArchDocs.add(rad);
        }

        // ── Step 3: combined architecture doc (always regenerates) ────────
        ArchitectureDocGenerator.Result combined;
        try {
            combined = archDocGenerator.generate(projectId, repoArchDocs, repoFullNames, provider);
        } catch (Exception e) {
            log.warn("[{}] LLM combined-arch failed ({}) — falling back to deterministic combine",
                    projectId, e.getMessage());
            java.util.LinkedHashMap<String, String> byRepo = new java.util.LinkedHashMap<>();
            for (int i = 0; i < repoArchDocs.size(); i++) {
                byRepo.put(i < repoFullNames.size() ? repoFullNames.get(i) : ("repo-" + i),
                        repoArchDocs.get(i).getContent());
            }
            combined = new ArchitectureDocGenerator.Result(
                    ArchitectureDocGenerator.deterministicCombined(projectId, byRepo), 0, 0, "deterministic");
        }

        int nextVersion = archDocRepo.findFirstByProjectIdOrderByVersionDesc(projectId)
                .map(d -> d.getVersion() + 1)
                .orElse(1);
        String shasJson = null;
        try {
            shasJson = objectMapper.writeValueAsString(shasByRepo);
        } catch (Exception e) {
            log.warn("Failed to serialise generatedFromShas: {}", e.getMessage());
        }
        ProjectArchitectureDoc doc = ProjectArchitectureDoc.builder()
                .projectId(projectId)
                .version(nextVersion)
                .content(combined.markdown())
                .generatedFromShas(shasJson)
                .build();
        archDocRepo.save(doc);
        log.info("[{}] 3-step arch generation complete: {} repos, combined v{} ({} in / {} out tokens)",
                projectId, codeMaps.size(), nextVersion, combined.inputTokens(), combined.outputTokens());

        persistToWorkspace(projectId, codeMaps, repoArchDocs, repoFullNames, combined.markdown());
        workspaceWarmer.warmDiskCache(projectId);
        return doc;
    }

    /**
     * Step 1 helper — reuse the latest codemap when the repo's current default-
     * branch HEAD matches its {@code generatedFromSha}; otherwise regenerate
     * and bump the version.
     */
    private ProjectCodeMap ensureCodeMap(String projectId, ProjectRepo repo,
                                          String branch, String patOverride) {
        Optional<ProjectCodeMap> latest = codeMapRepo
                .findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repo.getId());

        String currentSha = gitHub.getBranchHeadSha(
                "https://github.com/" + repo.getGithubRepoFullName(), branch, patOverride);

        if (latest.isPresent() && currentSha != null
                && currentSha.equals(latest.get().getGeneratedFromSha())) {
            log.info("[{}] codemap for {} up-to-date @ sha={} — reusing v{}",
                    projectId, repo.getGithubRepoFullName(),
                    shortSha(currentSha), latest.get().getVersion());
            return latest.get();
        }

        CodeMapGenerator.Result result = codeMapGenerator.generateWithSha(
                repo.getGithubRepoFullName(), branch, patOverride);
        int nextVersion = latest.map(c -> c.getVersion() + 1).orElse(1);
        ProjectCodeMap cm = ProjectCodeMap.builder()
                .projectId(projectId)
                .repoId(repo.getId())
                .version(nextVersion)
                .content(result.markdown())
                .generatedFromSha(result.sha() != null ? result.sha() : repo.getLastSyncedSha())
                .build();
        codeMapRepo.save(cm);
        log.info("[{}] codemap for {} regenerated as v{} @ sha={}",
                projectId, repo.getGithubRepoFullName(),
                nextVersion, shortSha(cm.getGeneratedFromSha()));
        return cm;
    }

    /**
     * Step 2 helper — reuse the latest repo arch doc when its
     * {@code sourceCodeMapId} matches the codemap we just produced; otherwise
     * regenerate via LLM and bump the version.
     */
    private RepoArchitectureDoc ensureRepoArchDoc(String projectId, ProjectRepo repo,
                                                    ProjectCodeMap codeMap, LlmProvider provider) {
        Optional<RepoArchitectureDoc> latest = repoArchRepo
                .findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repo.getId());
        if (latest.isPresent()
                && codeMap.getId() != null
                && codeMap.getId().equals(latest.get().getSourceCodeMapId())) {
            log.info("[{}] repo-arch for {} up-to-date @ codemap v{} — reusing v{}",
                    projectId, repo.getGithubRepoFullName(),
                    codeMap.getVersion(), latest.get().getVersion());
            return latest.get();
        }

        RepoArchitectureGenerator.Result rar;
        try {
            rar = repoArchGenerator.generate(
                    repo.getGithubRepoFullName(), codeMap.getContent(), provider);
        } catch (Exception e) {
            log.warn("[{}] repo-arch LLM failed for {} ({}) — falling back to codemap as arch content",
                    projectId, repo.getGithubRepoFullName(), e.getMessage());
            rar = new RepoArchitectureGenerator.Result(
                    "# " + repo.getGithubRepoFullName() + " — Architecture\n\n"
                            + "_LLM generation failed (" + e.getMessage() + "). "
                            + "Showing raw codemap instead._\n\n"
                            + codeMap.getContent(),
                    0, 0, "fallback");
        }

        int nextVersion = latest.map(d -> d.getVersion() + 1).orElse(1);
        RepoArchitectureDoc doc = RepoArchitectureDoc.builder()
                .projectId(projectId)
                .repoId(repo.getId())
                .version(nextVersion)
                .content(rar.markdown())
                .sourceCodeMapId(codeMap.getId())
                .generatedFromSha(codeMap.getGeneratedFromSha())
                .modelUsed(rar.modelUsed())
                .inputTokens(rar.inputTokens())
                .outputTokens(rar.outputTokens())
                .build();
        repoArchRepo.save(doc);
        log.info("[{}] repo-arch for {} generated as v{} ({} in / {} out tokens, model={})",
                projectId, repo.getGithubRepoFullName(), nextVersion,
                rar.inputTokens(), rar.outputTokens(), rar.modelUsed());
        return doc;
    }

    private static String shortSha(String sha) {
        return sha == null ? "null" : sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    private void persistToWorkspace(String projectId,
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
                workspace.writeAndCommit(projectId,
                        "codemaps/" + shortName + ".md",
                        cm.getContent(),
                        "codemap: " + full + " v" + cm.getVersion());
            }

            // repos/<shortName>/architecture.md — Step 2 artefacts (per-repo HLD+LLD)
            for (int i = 0; i < repoArchDocs.size(); i++) {
                RepoArchitectureDoc rad = repoArchDocs.get(i);
                String full = i < repoFullNames.size() ? repoFullNames.get(i) : ("repo-" + rad.getRepoId());
                String shortName = repoShortName(full);
                workspace.writeAndCommit(projectId,
                        "repos/" + shortName + "/architecture.md",
                        rad.getContent(),
                        "repo-arch: " + full + " v" + rad.getVersion());
            }

            // architecture.md — Step 3 combined doc
            workspace.writeAndCommit(projectId, "architecture.md", archMarkdown,
                    "architecture: combined regenerated");

            LlmProvider provider = projectRepository.findById(projectId)
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

    /** Latest per-repo architecture doc (for reads + the Sync re-run path). */
    public Optional<RepoArchitectureDoc> latestRepoArch(String projectId, UUID repoId) {
        return repoArchRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repoId);
    }

    /**
     * Per-repo rerun of Steps 1 + 2 (codemap + per-repo arch doc). Used by the
     * Sync button: "fresh pull" of a single repo, regenerating its codemap and
     * arch doc, leaving the combined project arch doc alone. Caller must then
     * mirror the updated doc to disk via
     * {@link ProjectWorkspaceService#writeAndCommit} if UI visibility is
     * required.
     *
     * @return the new or reused per-repo architecture doc
     */
    @Transactional
    public RepoArchitectureDoc regenerateForRepo(
            String projectId, UUID repoId, String patOverride) {
        ProjectRepo repo = repoRepo.findById(repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "repo not found: " + repoId));
        // Ensure the repo is actually linked to this project.
        boolean linked = linkRepo.findByProjectIdAndRepoId(projectId, repoId).isPresent();
        if (!linked) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repo " + repoId + " is not linked to project " + projectId);
        }

        LlmProvider provider = projectRepository.findById(projectId)
                .map(Project::getAiProvider)
                .orElse(LlmProvider.OPENAI);

        ProjectCodeMap cm = ensureCodeMap(projectId, repo, repo.getDefaultBranch(), patOverride);
        RepoArchitectureDoc rad = ensureRepoArchDoc(projectId, repo, cm, provider);

        // Disk mirror for just this repo — leaves the combined architecture.md + summary.md untouched.
        try {
            workspace.initIfMissing(projectId);
            String shortName = repoShortName(repo.getGithubRepoFullName());
            workspace.writeAndCommit(projectId, "codemaps/" + shortName + ".md",
                    cm.getContent(),
                    "codemap: " + repo.getGithubRepoFullName() + " v" + cm.getVersion() + " (sync)");
            workspace.writeAndCommit(projectId,
                    "repos/" + shortName + "/architecture.md",
                    rad.getContent(),
                    "repo-arch: " + repo.getGithubRepoFullName() + " v" + rad.getVersion() + " (sync)");
        } catch (Exception e) {
            log.warn("[{}] repo-sync disk mirror failed for {}: {}",
                    projectId, repo.getGithubRepoFullName(), e.getMessage());
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
        ProjectArchitectureDoc doc = archDocRepo.findFirstByProjectIdOrderByVersionDesc(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No architecture doc — generate one first"));
        doc.setUserAnnotations(annotation);
        return archDocRepo.save(doc);
    }

    public Optional<ProjectCodeMap> latestCodeMap(String projectId, UUID repoId) {
        return codeMapRepo.findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repoId);
    }
}
