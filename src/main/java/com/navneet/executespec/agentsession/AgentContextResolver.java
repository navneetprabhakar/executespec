package com.navneet.executespec.agentsession;

import java.util.Locale;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ProjectMatch;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentContextResolver {

  private final ProjectRepoRepository projectRepoRepo;
  private final ProjectRepoLinkRepository projectRepoLinkRepo;
  private final ProjectAccessGuard projectAccessGuard;

  ContextResolveResponse resolve(ContextResolveRequest request, AppUserDetails user) {
    String repoFullName =
        normalizeRepoFullName(
            AgentText.firstNonBlank(
                request == null ? null : request.gitRemote(),
                request == null || request.repoContext() == null
                    ? null
                    : request.repoContext().gitRemote()),
            request == null ? null : request.repoName());
    if (repoFullName == null) {
      return new ContextResolveResponse(null, "no git remote or repository name was provided");
    }

    for (var repo : projectRepoRepo.findByGithubRepoFullNameIgnoreCase(repoFullName)) {
      for (var link : projectRepoLinkRepo.findByRepoId(repo.getId())) {
        try {
          Project project = projectAccessGuard.assertCanRead(link.getProjectId(), user);
          return new ContextResolveResponse(
              new ProjectMatch(
                  project.getId(), project.getName(), String.valueOf(repo.getId()), repoFullName),
              "repo remote matches linked project repo " + repoFullName);
        } catch (ResponseStatusException ignored) {
          // Another org/user's link; keep looking without leaking it.
        }
      }
    }
    return new ContextResolveResponse(
        null, "no accessible project is linked to repo " + repoFullName);
  }

  private static String normalizeRepoFullName(String remote, String repoName) {
    String candidate = AgentText.firstNonBlank(remote, repoName);
    if (candidate == null) return null;
    String s = candidate.trim();
    if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
    int github = s.indexOf("github.com");
    if (github >= 0) {
      s = s.substring(github + "github.com".length());
    }
    s = s.replace(':', '/');
    while (s.startsWith("/")) s = s.substring(1);
    String[] parts = s.split("/");
    if (parts.length < 2) return null;
    return (parts[parts.length - 2] + "/" + parts[parts.length - 1]).toLowerCase(Locale.ROOT);
  }
}
