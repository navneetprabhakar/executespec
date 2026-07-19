package com.navneet.executespec.standards;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.folder.ProjectConfigPathResolver;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Loads only the exact selected repositories' canonical checked-in engineering policy. */
@Component
@RequiredArgsConstructor
public class ProjectStandardsYamlLoader {

  private static final long MAX_SOURCE_BYTES = 1024 * 1024L;
  private static final long MAX_TOTAL_BYTES = 4 * MAX_SOURCE_BYTES;

  private final ProjectRepoService projectRepoService;
  private final ProjectConfigPathResolver configPathResolver;

  public List<String> load(String projectId, Path workspaceRoot, List<UUID> selectedRepoIds) {
    if (workspaceRoot == null || selectedRepoIds == null || selectedRepoIds.isEmpty()) {
      return List.of();
    }
    List<ProjectRepo> resolved = projectRepoService.resolveForRun(projectId, selectedRepoIds);
    Map<UUID, ProjectRepo> byId = new HashMap<>();
    for (ProjectRepo repo : resolved) byId.put(repo.getId(), repo);

    try {
      Path realWorkspace = workspaceRoot.toRealPath();
      long total = 0;
      List<String> sources = new ArrayList<>();
      for (UUID selectedId : selectedRepoIds) {
        ProjectRepo repo = byId.get(selectedId);
        if (repo == null) {
          throw new IllegalStateException("Selected repository is no longer linked to the project");
        }
        String configPath =
            configPathResolver.resolveConfigFilePath(projectId, repo.getId(), "standards.yaml");
        if (configPath == null) continue;
        Path repoRoot =
            workspaceRoot.resolve("repos").resolve(lastPathSegment(repo.getGithubRepoFullName()));
        Path candidate = repoRoot.resolve(configPath).normalize();
        if (!candidate.startsWith(repoRoot.normalize()) || !Files.exists(candidate)) continue;
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate)) {
          throw new IllegalStateException("Checked-in standards must be a regular non-link file");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(realWorkspace)) {
          throw new IllegalStateException("Checked-in standards escape the admitted workspace");
        }
        long size = Files.size(real);
        total += size;
        if (size > MAX_SOURCE_BYTES || total > MAX_TOTAL_BYTES) {
          throw new IllegalStateException("Checked-in standards exceed the bounded source budget");
        }
        sources.add(Files.readString(real));
      }
      return List.copyOf(sources);
    } catch (IOException failure) {
      throw new IllegalStateException("Checked-in standards could not be loaded", failure);
    }
  }

  private static String lastPathSegment(String fullName) {
    if (fullName == null || fullName.isBlank()) {
      throw new IllegalStateException("Selected repository has no checkout identity");
    }
    int slash = fullName.lastIndexOf('/');
    return slash >= 0 ? fullName.substring(slash + 1) : fullName;
  }
}
