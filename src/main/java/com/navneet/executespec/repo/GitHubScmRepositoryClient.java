package com.navneet.executespec.repo;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Provider-neutral adapter over the established GitHub transport. */
@Service
@RequiredArgsConstructor
public class GitHubScmRepositoryClient implements ScmRepositoryClient {
  private static final Set<ScmCapability> CAPABILITIES =
      Set.of(
          ScmCapability.CATALOG,
          ScmCapability.ATTACH,
          ScmCapability.SYNC,
          ScmCapability.SOURCE_READINESS,
          ScmCapability.SOURCE_BROWSE,
          ScmCapability.CLOUD_RUN,
          ScmCapability.LOCAL_RUN,
          ScmCapability.ARCHITECTURE,
          ScmCapability.CODE_REVIEW,
          ScmCapability.PR_REVIEW,
          ScmCapability.WEBHOOK,
          ScmCapability.BOOTSTRAP,
          ScmCapability.PR_LINK);

  private final GitHubRepoService github;

  @Override
  public ScmProvider provider() {
    return ScmProvider.GITHUB;
  }

  @Override
  public Set<ScmCapability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public List<ScmRemoteRepository> listRepositories(String ownerFilter, ScmCredential credential) {
    requireCredential(credential);
    return github.listOwnerRepos(ownerFilter, credential.secret()).stream().map(this::map).toList();
  }

  @Override
  public ScmRemoteRepository inspect(String fullName, ScmCredential credential) {
    requireCredential(credential);
    GitHubRepoService.RemoteRepo repo = github.getRemoteRepo(fullName, credential.secret());
    if (repo == null) throw new ScmProviderException("GITHUB_REPOSITORY_UNREADABLE", null, false);
    return map(repo);
  }

  @Override
  public String branchHeadSha(
      ScmRemoteRepository repository, String branch, ScmCredential credential) {
    requireCredential(credential);
    return github.getBranchHeadSha(repository.cloneUrl(), branch, credential.secret());
  }

  @Override
  public ScmSourceInventory inventory(
      ScmRemoteRepository repository, String branch, ScmCredential credential) {
    requireCredential(credential);
    GitHubRepoService.RepoFileListResult result =
        github.listRepoFilesResult(repository.cloneUrl(), branch, credential.secret());
    return new ScmSourceInventory(
        result.files(),
        result.successful(),
        result.truncated(),
        result.errorCode(),
        result.upstreamStatus());
  }

  @Override
  public String readFile(
      ScmRemoteRepository repository, String branch, String path, ScmCredential credential) {
    requireCredential(credential);
    GitHubRepoService.RepoFileResult result =
        github.readRepoFile(repository.cloneUrl(), path, branch, credential.secret());
    // RR-1631 — the provider-neutral contract signals failure by throwing (Bitbucket already does).
    // Returning GitHub's old "// …" sentinel here would hand callers an error message as content.
    if (!result.successful()) {
      throw new ScmProviderException(result.errorCode(), result.upstreamStatus(), false);
    }
    return result.content();
  }

  private ScmRemoteRepository map(GitHubRepoService.RemoteRepo repo) {
    return new ScmRemoteRepository(
        provider().name(),
        repo.immutableId(),
        repo.fullName(),
        repo.fullName(),
        repo.cloneUrl(),
        repo.defaultBranch(),
        repo.isPrivate(),
        repo.sizeKb(),
        CAPABILITIES.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
  }

  private static void requireCredential(ScmCredential credential) {
    if (credential == null || !credential.usable()) {
      throw new ScmProviderException("MISSING_GITHUB_PAT", null, false);
    }
  }
}
