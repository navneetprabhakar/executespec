package com.navneet.executespec.codeintel;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.RunModelOverrides;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** DB-local admission and scoped reads for V358 generated-document jobs. */
@Service
@RequiredArgsConstructor
public class ProjectGeneratedDocJobService {

  private static final Duration DEADLINE = Duration.ofMinutes(30);

  private final ProjectGeneratedDocJobStore store;
  private final ProjectRepository projects;
  private final ProjectProviderResolver providerResolver;
  private final ModelResolver modelResolver;
  private final OrgModelOverrideService orgModelOverrides;

  public ProjectGeneratedDocJobStore.Job request(
      String projectId, DocType docType, String actorUserId) {
    Project project =
        projects
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    LlmProvider provider = providerResolver.resolve(project);
    var overrides = orgModelOverrides.overridesForRun(project.getOrgId());
    RunModelOverrides.bind(overrides);
    com.navneet.executespec.router.ResolvedModel route;
    try {
      route = modelResolver.resolve("ProjectDocGeneratorService", provider);
    } finally {
      RunModelOverrides.clear();
    }
    ProjectGeneratedDocJobStore.Job job =
        store.createOrGet(
            UUID.randomUUID(),
            projectId,
            project.getOrgId(),
            actorUserId,
            docType.name(),
            route.getActualProvider().name(),
            route.getModel(),
            DEADLINE);
    if (!job.requestedByUserId().equals(actorUserId)
        || !job.requestedProvider().equals(route.getActualProvider().name())
        || !job.requestedModel().equals(route.getModel())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Document generation is already active with a different actor or exact route");
    }
    return job;
  }

  public ProjectGeneratedDocJobStore.Job get(String projectId, UUID jobId) {
    return store
        .getScoped(jobId, projectId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Document generation job not found"));
  }

  public Optional<ProjectGeneratedDocJobStore.Job> latest(String projectId, DocType docType) {
    return store.latest(projectId, docType.name());
  }
}
