package com.navneet.executespec.project.workspace;

import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Mirrors the DB-authoritative project artefacts (architecture.md, codemaps/*.md)
 * to the local disk workspace. Runs {@link Async asynchronously} so the generate
 * path doesn't block on disk IO.
 *
 * <p><b>Why this exists:</b> V2 workers served by one MIG node may need to read
 * files written by {@code ProjectArchitectureService.generate()} on a different
 * node. The DB is the source of truth, but {@code ReadFileTool project:} paths
 * prefer the disk cache for hot reads. Warming the cache after a generate (and
 * optionally on first project access per JVM) keeps cross-node reads fast
 * without making them correctness-critical — {@code ReadFileTool} already has a
 * DB fallback, so a missed warm is a latency blip, not a failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectWorkspaceWarmer {

    private final ProjectWorkspaceService workspace;
    private final ProjectCodeMapRepository codeMapRepo;
    private final ProjectArchitectureDocRepository archRepo;
    private final ProjectRepoLinkRepository linkRepo;
    private final ProjectRepoRepository repoRepo;

    /**
     * Pull every current codemap + the latest architecture doc from the DB and
     * mirror them to disk. No-op when already up-to-date; best-effort on failure.
     */
    @Async
    public void warmDiskCache(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        try {
            workspace.initIfMissing(projectId);

            // Resolve repo short names once.
            List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
            Map<java.util.UUID, String> shortNameByRepoId = new java.util.HashMap<>();
            for (ProjectRepoLink link : links) {
                ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
                if (repo == null) continue;
                String full = repo.getGithubRepoFullName();
                String shortName = full != null && full.contains("/")
                        ? full.substring(full.indexOf('/') + 1)
                        : (full == null ? repo.getId().toString() : full);
                shortName = shortName.replaceAll("[^A-Za-z0-9._-]", "-");
                shortNameByRepoId.put(repo.getId(), shortName);
            }

            List<ProjectCodeMap> maps = codeMapRepo.findByProjectId(projectId);
            for (ProjectCodeMap cm : maps) {
                String shortName = shortNameByRepoId.getOrDefault(cm.getRepoId(), cm.getRepoId().toString());
                workspace.writeRawFile(projectId, "codemaps/" + shortName + ".md", cm.getContent());
            }

            archRepo.findFirstByProjectIdOrderByVersionDesc(projectId).ifPresent(
                    (ProjectArchitectureDoc doc) -> workspace.writeRawFile(projectId, "architecture.md", doc.getContent()));

            log.debug("Warmed disk cache for project {} ({} codemaps)", projectId, maps.size());
        } catch (Exception e) {
            log.warn("warmDiskCache failed for project {}: {}", projectId, e.getMessage());
        }
    }
}
