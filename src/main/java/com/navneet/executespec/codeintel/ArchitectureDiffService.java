package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.GitHubRepoService.ChangedFile;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * v2.5.5 (UX-4.2) — architecture version diff with source-file correlation.
 *
 * <p>For each repo that appears in both versions' {@code generated_from_shas} JSON maps, calls
 * GitHub's compare API and aggregates per-repo changed files (added / modified / removed /
 * renamed). The text diff of {@code architecture.md} stays client-side (LCS renderer already in
 * {@code ArchitectureViewer}); this endpoint only returns the file-correlation payload so the UI
 * can render a "Source changes" section below the existing text diff.
 *
 * <p>No new persistence: relies on SHAs already captured at generate-time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArchitectureDiffService {

  private final ProjectArchitectureDocRepository docRepo;
  private final GitHubRepoService gitHub;
  private final AppUserRepository userRepo;
  private final ObjectMapper objectMapper;

  public record RepoChanges(String repoFullName, List<ChangedFile> files) {}

  public record DiffReport(
      int fromVersion, int toVersion, List<RepoChanges> perRepo, int totalFiles) {}

  public DiffReport diff(String projectId, int fromVersion, int toVersion, String callerUserId) {
    ProjectArchitectureDoc from =
        docRepo
            .findByProjectIdAndVersion(projectId, fromVersion)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "architecture v" + fromVersion + " not found"));
    ProjectArchitectureDoc to =
        docRepo
            .findByProjectIdAndVersion(projectId, toVersion)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "architecture v" + toVersion + " not found"));

    Map<String, String> fromShas = parseShas(from);
    Map<String, String> toShas = parseShas(to);

    String pat =
        callerUserId == null
            ? null
            : userRepo.findById(callerUserId).map(AppUser::getGithubPat).orElse(null);

    List<RepoChanges> perRepo = new ArrayList<>();
    int totalFiles = 0;
    for (Map.Entry<String, String> entry : toShas.entrySet()) {
      String repo = entry.getKey();
      String head = entry.getValue();
      String base = fromShas.get(repo);
      if (base == null || head == null || base.equals(head)) continue;
      List<ChangedFile> files = gitHub.compareCommits(repo, base, head, pat);
      if (files.isEmpty()) continue;
      perRepo.add(new RepoChanges(repo, files));
      totalFiles += files.size();
    }
    return new DiffReport(fromVersion, toVersion, perRepo, totalFiles);
  }

  private Map<String, String> parseShas(ProjectArchitectureDoc doc) {
    if (doc.getGeneratedFromShas() == null || doc.getGeneratedFromShas().isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(
          doc.getGeneratedFromShas(), new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception e) {
      log.warn("Failed to parse generated_from_shas for doc {}: {}", doc.getId(), e.getMessage());
      return Map.of();
    }
  }
}
