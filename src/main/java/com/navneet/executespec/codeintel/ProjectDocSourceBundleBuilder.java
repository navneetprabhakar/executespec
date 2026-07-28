package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

/**
 * Builds the revision-consistent, deterministically ordered evidence bundle used for generated
 * project documents.
 *
 * <p>The builder rejects missing, extra, or mismatched repository SHA evidence rather than mixing
 * codemaps from different architecture revisions.
 */
final class ProjectDocSourceBundleBuilder {

  private final ProjectArchitectureService architectureService;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final ObjectMapper objectMapper;

  ProjectDocSourceBundleBuilder(
      ProjectArchitectureService architectureService,
      ProjectRepoLinkRepository linkRepo,
      ProjectRepoRepository repoRepo,
      ObjectMapper objectMapper) {
    this.architectureService = architectureService;
    this.linkRepo = linkRepo;
    this.repoRepo = repoRepo;
    this.objectMapper = objectMapper;
  }

  String build(Project project, ProjectArchitectureDoc architecture) {
    Map<String, String> sourceShas = parseSourceShas(architecture);
    List<ProjectRepo> repos = linkedRepos(project.getId());

    StringBuilder metadata = new StringBuilder();
    metadata.append("- Name: ").append(nullToDash(project.getName())).append('\n');
    metadata.append("- Description: ").append(nullToDash(project.getDescription())).append('\n');
    metadata.append("- GitHub owner: ").append(nullToDash(project.getGithubOwner())).append('\n');
    metadata.append("\n## Linked repositories\n");
    for (ProjectRepo repo : repos) {
      metadata
          .append("- ")
          .append(repo.getGithubRepoFullName())
          .append(" (default branch: ")
          .append(repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch())
          .append(")\n");
    }

    List<ProjectDocSourceBudgeter.Section> sections = new ArrayList<>();
    sections.add(new ProjectDocSourceBudgeter.Section("# Project metadata", metadata.toString()));
    sections.add(
        new ProjectDocSourceBudgeter.Section(
            "# Architecture document (v" + architecture.getVersion() + ")",
            architecture.getContent() == null ? "" : architecture.getContent()));
    for (ProjectRepo repo : repos) {
      String fullName = repo.getGithubRepoFullName();
      String expectedSha = sourceShas.remove(fullName);
      if (expectedSha == null || expectedSha.isBlank()) {
        throw new ProjectDocGeneratorService.StaleSourceException();
      }
      ProjectCodeMap exactCodeMap =
          architectureService
              .codeMapForSourceSha(project.getId(), repo.getId(), expectedSha)
              .orElseThrow(ProjectDocGeneratorService.StaleSourceException::new);
      sections.add(
          new ProjectDocSourceBudgeter.Section(
              "# Repository codemap: " + fullName,
              exactCodeMap.getContent() == null ? "" : exactCodeMap.getContent()));
    }
    if (!sourceShas.isEmpty()) {
      throw new ProjectDocGeneratorService.StaleSourceException();
    }
    return ProjectDocSourceBudgeter.budget(
        sections, ProjectDocGeneratorService.SOURCE_BUNDLE_CHAR_LIMIT);
  }

  Map<String, String> parseSourceShas(String sourceFingerprint) {
    if (sourceFingerprint == null || sourceFingerprint.isBlank()) {
      throw new ProjectDocGeneratorService.StaleSourceException();
    }
    try {
      Map<String, String> parsed =
          objectMapper.readValue(
              sourceFingerprint, new TypeReference<LinkedHashMap<String, String>>() {});
      if (parsed == null || parsed.isEmpty()) {
        throw new ProjectDocGeneratorService.StaleSourceException();
      }
      return new LinkedHashMap<>(parsed);
    } catch (ProjectDocGeneratorService.StaleSourceException stale) {
      throw stale;
    } catch (Exception failure) {
      throw new ProjectDocGeneratorService.StaleSourceException();
    }
  }

  private Map<String, String> parseSourceShas(ProjectArchitectureDoc architecture) {
    if (architecture == null) {
      throw new ProjectDocGeneratorService.StaleSourceException();
    }
    return parseSourceShas(architecture.getGeneratedFromShas());
  }

  private List<ProjectRepo> linkedRepos(String projectId) {
    List<ProjectRepo> repos = new ArrayList<>();
    for (ProjectRepoLink link : linkRepo.findByProjectId(projectId)) {
      repoRepo.findById(link.getRepoId()).ifPresent(repos::add);
    }
    repos.sort(
        Comparator.comparing(
                ProjectRepo::getGithubRepoFullName, Comparator.nullsLast(String::compareTo))
            .thenComparing(ProjectRepo::getId, Comparator.nullsLast(java.util.UUID::compareTo)));
    return repos;
  }

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }
}
