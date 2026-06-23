package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CreateSessionRequest;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentSessionCreationService {

  private final AgentSessionRepository sessionRepo;
  private final ProjectAccessGuard projectAccessGuard;
  private final RunAccessGuard runAccessGuard;
  private final AiProviderPreferenceService aiProviderPreferenceService;
  private final ModelAccessService modelAccessService;

  AgentSession create(CreateSessionRequest request, AppUserDetails user, String orgId) {
    CreateSessionRequest req =
        request == null
            ? new CreateSessionRequest(
                null, null, null, null, null, null, null, null, null, null, null, null)
            : request;
    String requestedId = AgentText.blankToNull(req.id());
    if (requestedId != null) {
      var existing = sessionRepo.findById(requestedId);
      if (existing.isPresent()) {
        AgentSession session = existing.get();
        if (!user.getUser().getId().equals(session.getUserId())) {
          throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return session;
      }
    }

    if (req.projectId() != null && !req.projectId().isBlank()) {
      projectAccessGuard.assertCanRead(req.projectId(), user);
    }
    String runId = AgentText.blankToNull(req.runId());
    PipelineRun scopedRun = null;
    if (runId != null) {
      scopedRun = runAccessGuard.requireRunAccess(runId, user);
    }
    if (scopedRun != null
        && req.projectId() != null
        && !req.projectId().isBlank()
        && scopedRun.getProjectId() != null
        && !req.projectId().equals(scopedRun.getProjectId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId does not match run");
    }
    String sessionProjectId =
        AgentText.blankToNull(
            AgentText.firstNonBlank(
                req.projectId(), scopedRun == null ? null : scopedRun.getProjectId()));
    String requestedProvider = AgentText.blankToNull(req.defaultProvider());
    String effectiveProvider =
        requestedProvider != null
            ? aiProviderPreferenceService.updatePreferredProvider(user, requestedProvider)
            : aiProviderPreferenceService.preferredProvider(user);
    modelAccessService.assertModelAllowed(
        user.getUser().getId(), AgentText.blankToNull(req.defaultModel()));

    Instant now = Instant.now();
    AgentSession session =
        AgentSession.builder()
            .id(AgentText.firstNonBlank(requestedId, UUID.randomUUID().toString()))
            .orgId(orgId)
            .userId(user.getUser().getId())
            .projectId(sessionProjectId)
            .runId(runId)
            .repoRemote(
                AgentText.blankToNull(
                    AgentText.firstNonBlank(
                        req.repoRemote(),
                        req.repoContext() == null ? null : req.repoContext().gitRemote())))
            .repoRootHash(AgentText.blankToNull(req.repoRootHash()))
            .clientSurface(normalize(req.surface(), "CLI"))
            .title(AgentText.firstNonBlank(req.title(), "New ExecuteSpec session"))
            .defaultProvider(effectiveProvider)
            .defaultModel(AgentText.blankToNull(req.defaultModel()))
            .defaultExecutionMode(normalize(req.defaultExecutionMode(), "LOCAL"))
            .approvalPolicy(normalize(req.approvalPolicy(), "PLAN"))
            .createdAt(now)
            .updatedAt(now)
            .build();
    return sessionRepo.save(session);
  }

  private static String normalize(String value, String fallback) {
    String v = AgentText.blankToNull(value);
    return v == null ? fallback : v.trim().toUpperCase(Locale.ROOT);
  }
}
