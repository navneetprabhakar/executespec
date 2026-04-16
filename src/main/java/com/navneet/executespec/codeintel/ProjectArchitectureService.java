package com.navneet.executespec.codeintel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
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
    private final CodeMapGenerator codeMapGenerator;
    private final ArchitectureDocGenerator archDocGenerator;
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
    @Transactional
    public ProjectArchitectureDoc generate(String projectId, String branchOverride, String patOverride) {
        List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
        if (links.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Project has no linked repos — link repos before generating architecture");
        }

        List<ProjectCodeMap> codeMaps = new ArrayList<>();
        List<String> repoFullNames = new ArrayList<>();
        java.util.LinkedHashMap<String, String> shasByRepo = new java.util.LinkedHashMap<>();
        boolean hasOverride = branchOverride != null && !branchOverride.isBlank();
        for (ProjectRepoLink link : links) {
            ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
            if (repo == null) continue;
            String effectiveBranch = hasOverride ? branchOverride : repo.getDefaultBranch();
            CodeMapGenerator.Result result = codeMapGenerator.generateWithSha(
                    repo.getGithubRepoFullName(), effectiveBranch, patOverride);
            int nextVersion = codeMapRepo
                    .findFirstByProjectIdAndRepoIdOrderByVersionDesc(projectId, repo.getId())
                    .map(c -> c.getVersion() + 1)
                    .orElse(1);
            ProjectCodeMap cm = ProjectCodeMap.builder()
                    .projectId(projectId)
                    .repoId(repo.getId())
                    .version(nextVersion)
                    .content(result.markdown())
                    .generatedFromSha(result.sha() != null ? result.sha() : repo.getLastSyncedSha())
                    .build();
            codeMapRepo.save(cm);
            codeMaps.add(cm);
            repoFullNames.add(repo.getGithubRepoFullName());
            shasByRepo.put(repo.getGithubRepoFullName(), cm.getGeneratedFromSha());
        }

        String archMarkdown = archDocGenerator.generate(projectId, codeMaps, repoFullNames);
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
                .content(archMarkdown)
                .generatedFromShas(shasJson)
                .build();
        archDocRepo.save(doc);
        log.info("Generated architecture doc v{} for project {} ({} repos)",
                nextVersion, projectId, codeMaps.size());

        // Also persist to the project workspace (codemaps/*.md, architecture.md, summary.md).
        persistToWorkspace(projectId, codeMaps, repoFullNames, archMarkdown);

        // Async warm the disk cache on this node too — generate() runs inside a
        // transaction, so we schedule the warm via the self-proxy (@Async bean)
        // which picks up the committed DB state.
        workspaceWarmer.warmDiskCache(projectId);
        return doc;
    }

    private void persistToWorkspace(String projectId,
                                    List<ProjectCodeMap> codeMaps,
                                    List<String> repoFullNames,
                                    String archMarkdown) {
        try {
            workspace.initIfMissing(projectId);
            for (int i = 0; i < codeMaps.size(); i++) {
                ProjectCodeMap cm = codeMaps.get(i);
                String full = i < repoFullNames.size() ? repoFullNames.get(i) : ("repo-" + cm.getRepoId());
                String shortName = full.contains("/") ? full.substring(full.indexOf('/') + 1) : full;
                shortName = shortName.replaceAll("[^A-Za-z0-9._-]", "-");
                workspace.writeAndCommit(projectId,
                        "codemaps/" + shortName + ".md",
                        cm.getContent(),
                        "codemap: " + full + " v" + cm.getVersion());
            }
            workspace.writeAndCommit(projectId, "architecture.md", archMarkdown,
                    "architecture: regenerated");
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
