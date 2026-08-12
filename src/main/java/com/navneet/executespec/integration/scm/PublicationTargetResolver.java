package com.navneet.executespec.integration.scm;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

/** Resolves the exact local repository and remote branch identities before Git object creation. */
@Component
@RequiredArgsConstructor
public class PublicationTargetResolver {

  private final ObjectMapper objectMapper;
  private final ProjectRepoRepository projectRepoRepository;
  private final ProjectRepository projectRepository;
  private final AgentProperties agentProperties;

  public List<Target> resolve(PipelineRun run) {
    if (run == null || !StringUtils.hasText(run.getRunId())) {
      throw new IllegalArgumentException("publication run identity is required");
    }
    Project project =
        StringUtils.hasText(run.getProjectId())
            ? projectRepository.findById(run.getProjectId()).orElse(null)
            : null;
    String branch =
        "NEW".equalsIgnoreCase(run.getRepoMode()) && !StringUtils.hasText(run.getRepoUrl())
            ? "main"
            : branchName(project, run);
    List<UUID> selected = selectedRepoIds(run.getSelectedRepoIds());
    if (!selected.isEmpty()) {
      List<Target> targets = new ArrayList<>();
      for (UUID repoId : selected) {
        ProjectRepo repo =
            projectRepoRepository
                .findById(repoId)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "PUBLICATION_REPOSITORY_IDENTITY_INCOMPLETE:" + repoId));
        String fullName = repo.getGithubRepoFullName();
        if (!StringUtils.hasText(fullName)) {
          throw new IllegalStateException("PUBLICATION_REPOSITORY_NAME_MISSING:" + repoId);
        }
        String provider =
            StringUtils.hasText(repo.getScmProvider())
                ? repo.getScmProvider().trim().toUpperCase(Locale.ROOT)
                : "GITHUB";
        targets.add(
            new Target(
                provider + ":" + fullName,
                "repos/" + lastPathSegment(fullName),
                branch,
                false,
                commitMessage(run, fullName)));
      }
      long distinctPaths = targets.stream().map(Target::relativeWorktreePath).distinct().count();
      long distinctIdentities = targets.stream().map(Target::repositoryIdentity).distinct().count();
      if (distinctPaths != targets.size() || distinctIdentities != targets.size()) {
        throw new IllegalStateException("PUBLICATION_REPOSITORY_TARGET_COLLISION");
      }
      return List.copyOf(targets);
    }

    String provider =
        StringUtils.hasText(run.getScmProvider())
            ? run.getScmProvider().trim().toUpperCase(Locale.ROOT)
            : inferProvider(run.getRepoUrl());
    String identity;
    if (StringUtils.hasText(run.getRepoUrl())) {
      identity = canonicalRemoteIdentity(provider + ":expected", run.getRepoUrl());
    } else if ("BITBUCKET".equals(provider)
        && StringUtils.hasText(run.getBitbucketWorkspace())
        && StringUtils.hasText(run.getBitbucketRepoSlug())) {
      identity = provider + ":" + run.getBitbucketWorkspace() + "/" + run.getBitbucketRepoSlug();
    } else if ("GITHUB".equals(provider) && "NEW".equalsIgnoreCase(run.getRepoMode())) {
      String owner =
          StringUtils.hasText(run.getGithubOwner())
              ? run.getGithubOwner()
              : agentProperties.getGithub().getDefaultOwner();
      if (!StringUtils.hasText(owner)) {
        throw new IllegalStateException("PUBLICATION_NEW_REPOSITORY_OWNER_MISSING");
      }
      identity = "GITHUB:" + owner + "/" + newRepositoryName(run);
    } else {
      throw new IllegalStateException("PUBLICATION_NEW_REPOSITORY_PROVIDER_UNSUPPORTED");
    }
    boolean provisionRequired =
        !StringUtils.hasText(run.getRepoUrl())
            && "GITHUB".equals(provider)
            && "NEW".equalsIgnoreCase(run.getRepoMode());
    return List.of(
        new Target(identity, ".", branch, provisionRequired, commitMessage(run, identity)));
  }

  public static String branchName(Project project, PipelineRun run) {
    if (StringUtils.hasText(run.getBranchOverride())) return run.getBranchOverride();
    String pattern = project != null ? project.getBranchNamingPattern() : null;
    String shortId = run.getRunId().substring(0, Math.min(8, run.getRunId().length()));
    if (!StringUtils.hasText(pattern)) return "executespec/" + shortId;
    String description =
        slugify(run.getRunTitle() != null ? run.getRunTitle() : run.getUserRequest());
    return pattern.replace("{runId}", shortId).replace("{description}", description);
  }

  private List<UUID> selectedRepoIds(String json) {
    if (!StringUtils.hasText(json)) return List.of();
    try {
      return objectMapper.readValue(
          json, objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
    } catch (Exception malformed) {
      throw new IllegalStateException("PUBLICATION_REPOSITORY_IDENTITY_INVALID", malformed);
    }
  }

  public static String canonicalRemoteIdentity(String expectedIdentity, String remoteUrl) {
    if (!StringUtils.hasText(expectedIdentity) || !StringUtils.hasText(remoteUrl)) {
      throw new IllegalStateException("PUBLICATION_REMOTE_IDENTITY_MISSING");
    }
    int separator = expectedIdentity.indexOf(':');
    if (separator <= 0) throw new IllegalStateException("PUBLICATION_REPOSITORY_IDENTITY_INVALID");
    String provider = expectedIdentity.substring(0, separator).toUpperCase(Locale.ROOT);
    URI uri;
    try {
      uri = URI.create(remoteUrl);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("PUBLICATION_REMOTE_IDENTITY_INVALID", malformed);
    }
    if (uri.getUserInfo() != null) {
      throw new IllegalStateException("PUBLICATION_REMOTE_IDENTITY_CONTAINS_CREDENTIAL");
    }
    String host = uri.getHost();
    String path = uri.getPath();
    if (!StringUtils.hasText(host) || !StringUtils.hasText(path)) {
      throw new IllegalStateException("PUBLICATION_REMOTE_IDENTITY_INVALID");
    }
    path = path.replaceFirst("^/+", "");
    if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
    String coordinate =
        (("GITHUB".equals(provider) && "github.com".equalsIgnoreCase(host))
                || ("BITBUCKET".equals(provider) && "bitbucket.org".equalsIgnoreCase(host)))
            ? path
            : host.toLowerCase(Locale.ROOT) + "/" + path;
    return provider + ":" + coordinate;
  }

  public static String newRepositoryName(PipelineRun run) {
    if (run == null || !StringUtils.hasText(run.getRunId())) {
      throw new IllegalArgumentException("run identity is required");
    }
    String stable = run.getRunId().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    if (stable.length() > 16) stable = stable.substring(0, 16);
    if (stable.length() < 8) stable = sha256NameSeed(run.getRunId()).substring(0, 16);
    return "executespec-" + stable;
  }

  private static String sha256NameSeed(String value) {
    return PublicationPreparationService.sha256(value);
  }

  private static String inferProvider(String repoUrl) {
    if (repoUrl != null && repoUrl.toLowerCase(Locale.ROOT).contains("bitbucket.org")) {
      return "BITBUCKET";
    }
    return "GITHUB";
  }

  private static String commitMessage(PipelineRun run, String repository) {
    String title =
        StringUtils.hasText(run.getRunTitle()) ? run.getRunTitle() : "ExecuteSpec changes";
    return title
        + "\n\nRepository: "
        + repository
        + "\nRun: "
        + run.getRunId()
        + "\n\nCo-Authored-By: ExecuteSpec Agent <admin@executespec.ai>";
  }

  private static String lastPathSegment(String value) {
    int slash = value.lastIndexOf('/');
    return slash >= 0 ? value.substring(slash + 1) : value;
  }

  private static String slugify(String text) {
    if (text == null) return "change";
    String slug = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (slug.isBlank()) return "change";
    return slug.length() > 40 ? slug.substring(0, 40) : slug;
  }

  public record Target(
      String repositoryIdentity,
      String relativeWorktreePath,
      String publicationBranch,
      boolean provisionRequired,
      String commitMessage) {}
}
