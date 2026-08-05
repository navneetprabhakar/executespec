package com.navneet.executespec.codeintel;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelMatrixConfigurationException;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.ProjectRoutingException;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** DB/config-local admission and exact-route compatibility for architecture jobs. */
@Component
@RequiredArgsConstructor
class ProjectArchitectureJobAdmission {

  private static final String REPO_AGENT = "RepoArchitectureGenerator";
  private static final String PROJECT_AGENT = "ArchitectureDocGenerator";

  private final ProjectArchitectureJobStore store;
  private final ProjectRepository projectRepository;
  private final ProjectProviderResolver providerResolver;
  private final ModelResolver modelResolver;
  private final ProjectArchitectureJobProperties properties;

  ProjectArchitectureJobStore.Job request(
      String projectId, String actorUserId, String branchOverride) {
    Project project = requireProject(projectId);
    return createResolved(
        projectId, project, actorUserId, branchOverride, providerResolver.resolve(project));
  }

  ProjectArchitectureJobStore.Job requestExact(
      String projectId,
      String actorUserId,
      String branchOverride,
      LlmProvider provider,
      String exactModel) {
    if (provider == null || exactModel == null || exactModel.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Exact architecture provider/model route is required");
    }
    Project project = requireProject(projectId);
    String model = exactModel.trim();
    return create(projectId, project, actorUserId, branchOverride, provider, model, model);
  }

  ProjectArchitectureJobStore.Job requestWithProvider(
      String projectId, String actorUserId, String branchOverride, LlmProvider provider) {
    return createResolved(
        projectId, requireProject(projectId), actorUserId, branchOverride, provider);
  }

  private ProjectArchitectureJobStore.Job createResolved(
      String projectId,
      Project project,
      String actorUserId,
      String branchOverride,
      LlmProvider requestedProvider) {
    ResolvedModel repoRoute = resolveModel(REPO_AGENT, requestedProvider);
    ResolvedModel projectRoute = resolveModel(PROJECT_AGENT, requestedProvider);
    if (repoRoute.getActualProvider() != projectRoute.getActualProvider()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Architecture routes do not resolve to one durable provider");
    }
    return create(
        projectId,
        project,
        actorUserId,
        branchOverride,
        projectRoute.getActualProvider(),
        repoRoute.getModel(),
        projectRoute.getModel());
  }

  private ProjectArchitectureJobStore.Job create(
      String projectId,
      Project project,
      String actorUserId,
      String branchOverride,
      LlmProvider provider,
      String repoModel,
      String projectModel) {
    String branch = normalizeBranch(branchOverride);
    ProjectArchitectureJobStore.Job job =
        store.createOrGet(
            UUID.randomUUID(),
            projectId,
            project.getOrgId(),
            actorUserId,
            branch,
            provider.name(),
            repoModel,
            projectModel,
            Duration.ofSeconds(properties.getDeadlineSeconds()));
    if (!Objects.equals(job.requestedByUserId(), actorUserId)
        || !Objects.equals(job.requestedBranch(), branch)
        || !job.requestedProvider().equals(provider.name())
        || !job.repoArchitectureModel().equals(repoModel)
        || !job.projectArchitectureModel().equals(projectModel)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "A map refresh is already active with a different actor, branch, or exact route");
    }
    return job;
  }

  private Project requireProject(String projectId) {
    return projectRepository
        .findById(projectId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
  }

  private ResolvedModel resolveModel(String agentName, LlmProvider provider) {
    try {
      return modelResolver.resolve(agentName, provider);
    } catch (ModelMatrixConfigurationException invalidConfiguration) {
      throw ProjectRoutingException.modelMatrixUnavailable(invalidConfiguration);
    }
  }

  private static String normalizeBranch(String branch) {
    return branch == null || branch.isBlank() ? null : branch.trim();
  }
}
