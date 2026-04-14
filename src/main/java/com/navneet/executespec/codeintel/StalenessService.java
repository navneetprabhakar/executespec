package com.navneet.executespec.codeintel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pilot v3.0 (deferral #4) — compares the SHAs captured when each repo's
 * codemap was generated against the current HEAD of each repo's default branch.
 * Result is cached for 5 minutes so dashboard polls don't hammer GitHub.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StalenessService {

    private final ProjectArchitectureService archService;
    private final ProjectRepoLinkRepository linkRepo;
    private final ProjectRepoRepository repoRepo;
    private final GitHubRepoService gitHub;
    private final ObjectMapper objectMapper;

    private final Cache<String, StalenessReport> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1_000)
            .build();

    public record RepoStaleness(
            String repoFullName,
            String generatedFromSha,
            String currentSha,
            boolean stale) {}

    public record StalenessReport(
            boolean stale,
            int staleCount,
            int totalCount,
            List<RepoStaleness> repos) {}

    public StalenessReport report(String projectId) {
        return cache.get(projectId, this::compute);
    }

    /** Force a refresh — call after generate() so the dashboard sees fresh SHAs immediately. */
    public void invalidate(String projectId) {
        cache.invalidate(projectId);
    }

    private StalenessReport compute(String projectId) {
        ProjectArchitectureDoc latest = archService.latest(projectId).orElse(null);
        Map<String, String> generatedShas = parseShas(latest);

        List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
        List<RepoStaleness> repoReports = new java.util.ArrayList<>();
        int staleCount = 0;
        for (ProjectRepoLink link : links) {
            ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
            if (repo == null) continue;
            String generated = generatedShas.get(repo.getGithubRepoFullName());
            String current = gitHub.getBranchHeadSha(
                    "https://github.com/" + repo.getGithubRepoFullName(),
                    repo.getDefaultBranch(),
                    null);
            boolean stale = current != null && (generated == null || !generated.equals(current));
            if (stale) staleCount++;
            repoReports.add(new RepoStaleness(repo.getGithubRepoFullName(), generated, current, stale));
        }
        return new StalenessReport(staleCount > 0, staleCount, repoReports.size(), repoReports);
    }

    private Map<String, String> parseShas(ProjectArchitectureDoc doc) {
        if (doc == null || doc.getGeneratedFromShas() == null || doc.getGeneratedFromShas().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(doc.getGeneratedFromShas(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse generatedFromShas for project {}: {}", doc.getProjectId(), e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
