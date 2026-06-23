package com.navneet.executespec.agentsession;

import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.GateDecisionCard;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentGateReadService {

  private final PipelineRunRepository pipelineRunRepo;
  private final RunAccessGuard runAccessGuard;
  private final AgentGateDecisionCardFactory gateCardFactory;

  List<GateDecisionCard> list(AppUserDetails user, int limit) {
    int capped = Math.max(1, Math.min(50, limit));
    return pipelineRunRepo
        .findByCreatedByUserIdAndStatusOrderByStartedAtDesc(
            user.getUser().getId(), "AWAITING_APPROVAL", PageRequest.of(0, capped))
        .stream()
        .map(gateCardFactory::fromRun)
        .toList();
  }

  GateDecisionCard get(String runId, AppUserDetails user) {
    PipelineRun run = runAccessGuard.requireRunAccess(runId, user);
    if (!"AWAITING_APPROVAL".equals(run.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "run is not awaiting approval");
    }
    return gateCardFactory.fromRun(run);
  }
}
