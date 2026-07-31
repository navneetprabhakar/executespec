package com.navneet.executespec.integration.scm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;

/** Verifies every approved continuation against its exact physical worker and reviewer attempts. */
final class ApprovedContinuationRouteAttestation {

  private final AgentTaskRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final RunEffectiveRouteService effectiveRoutes;

  ApprovedContinuationRouteAttestation(
      AgentTaskRepository taskRepository,
      ObjectMapper objectMapper,
      RunEffectiveRouteService effectiveRoutes) {
    this.taskRepository = taskRepository;
    this.objectMapper = objectMapper;
    this.effectiveRoutes = effectiveRoutes;
  }

  void verify(PipelineRun run) {
    List<AgentTask> persistedTasks = taskRepository.findByRunId(run.getRunId());
    if (persistedTasks == null) {
      throw blocked(
          "PUBLICATION_ROUTE_EVIDENCE_INVALID",
          "Approved continuation task evidence is unavailable for " + run.getRunId());
    }
    List<AgentTask> tasks = new ArrayList<>(persistedTasks);
    tasks.sort(
        Comparator.comparing(
            task -> task == null ? null : task.getTaskId(),
            Comparator.nullsFirst(String::compareTo)));
    Set<String> attestedPhysicalAttemptIds = new HashSet<>();
    for (AgentTask task : tasks) {
      if (task == null || task.getTaskId() == null || task.getTaskId().isBlank()) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Approved continuation task evidence is missing its durable task identity");
      }
      List<ApprovedRoute> approvedRoutes = approvedRoutes(task);
      if (approvedRoutes.isEmpty()) {
        if (hasDurableApprovalIdentity(task)) {
          throw blocked(
              "PUBLICATION_ROUTE_EVIDENCE_INVALID",
              "Durable continuation approval exists without its append-only approved route evidence"
                  + " for "
                  + task.getTaskId());
        }
        continue;
      }
      ApprovedRoute latestApproved = approvedRoutes.getLast();
      boolean approvalIdentityMatches =
          latestApproved.approvalId().equals(task.getContinuationApprovalId())
              && latestApproved
                  .proposalDigest()
                  .equals(task.getContinuationApprovalProposalDigest())
              && latestApproved
                  .expectedApprovalStatus()
                  .equalsIgnoreCase(task.getContinuationApprovalStatus())
              && sameProvider(latestApproved.provider(), task.getContinuationApprovalProvider())
              && latestApproved.model().equals(task.getContinuationApprovalModel())
              && latestApproved.decision().equals(task.getContinuationApprovalAction())
              && Objects.equals(
                  latestApproved.postReview(), task.getContinuationApprovalPostReview());
      if (!approvalIdentityMatches) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Latest approved continuation action does not match the durable approval identity for "
                + task.getTaskId());
      }
      for (ApprovedRoute approved : approvedRoutes) {
        reserveAttemptIdentities(task, approved, attestedPhysicalAttemptIds);
        if (!approved.mutationExcluded()) {
          verifyApprovedRoute(run, task, approved);
        }
      }
    }
  }

  private static boolean hasDurableApprovalIdentity(AgentTask task) {
    return hasText(task.getContinuationApprovalId())
        || hasText(task.getContinuationApprovalProposalDigest())
        || hasText(task.getContinuationApprovalStatus())
        || hasText(task.getContinuationApprovalProvider())
        || hasText(task.getContinuationApprovalModel())
        || hasText(task.getContinuationApprovalAction())
        || task.getContinuationApprovalPostReview() != null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private void verifyApprovedRoute(PipelineRun run, AgentTask task, ApprovedRoute approved) {
    var workerRoute =
        effectiveRoutes.forTaskPhysicalAttempts(
            run.getRunId(),
            logicalTaskId(run.getRunId(), task.getTaskId()),
            approved.workerAttempt().physicalAttemptIds());
    if (!approved.workerAttempt().matches(workerRoute, approved.provider(), approved.model())) {
      throw blocked(
          "PUBLICATION_APPROVED_ROUTE_MISMATCH",
          "Approved continuation route "
              + approved.provider()
              + "/"
              + approved.model()
              + " for action "
              + approved.actionIndex()
              + " does not match the fence-specific physical worker attempts for "
              + task.getTaskId());
    }
    var reviewerRoute =
        effectiveRoutes.forReviewerPhysicalAttempts(
            run.getRunId(), approved.reviewerAttempt().physicalAttemptIds());
    if (!approved.reviewerAttempt().matches(reviewerRoute, approved.provider(), approved.model())) {
      throw blocked(
          "PUBLICATION_APPROVED_REVIEWER_ROUTE_MISMATCH",
          "Approved continuation route "
              + approved.provider()
              + "/"
              + approved.model()
              + " for action "
              + approved.actionIndex()
              + " does not match its fence-specific physical reviewer attempts for "
              + task.getTaskId());
    }
  }

  private void reserveAttemptIdentities(
      AgentTask task, ApprovedRoute approved, Set<String> attestedPhysicalAttemptIds) {
    if (approved.workerAttempt() != null
        && !java.util.Collections.disjoint(
            attestedPhysicalAttemptIds, approved.workerAttempt().physicalAttemptIds())) {
      throw blocked(
          "PUBLICATION_ROUTE_EVIDENCE_INVALID",
          "Approved continuation action "
              + approved.actionIndex()
              + " reuses physical worker attempt evidence for "
              + task.getTaskId());
    }
    if (approved.workerAttempt() != null) {
      attestedPhysicalAttemptIds.addAll(approved.workerAttempt().physicalAttemptIds());
    }
    if (approved.reviewerAttempt() != null
        && !java.util.Collections.disjoint(
            attestedPhysicalAttemptIds, approved.reviewerAttempt().physicalAttemptIds())) {
      throw blocked(
          "PUBLICATION_ROUTE_EVIDENCE_INVALID",
          "Approved continuation action "
              + approved.actionIndex()
              + " reuses physical reviewer attempt evidence for "
              + task.getTaskId());
    }
    if (approved.reviewerAttempt() != null) {
      attestedPhysicalAttemptIds.addAll(approved.reviewerAttempt().physicalAttemptIds());
    }
  }

  private List<ApprovedRoute> approvedRoutes(AgentTask task) {
    String json = task.getContinuationActionsJson();
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      JsonNode actions = objectMapper.readTree(json);
      if (!actions.isArray()) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Continuation route evidence is not a JSON array for " + task.getTaskId());
      }
      Map<ApprovalIdentity, CorrectionResolution> resolutions =
          correctionResolutions(task, actions);
      List<ApprovedRoute> approvedRoutes = new ArrayList<>();
      Set<String> approvalIds = new LinkedHashSet<>();
      Set<String> proposalDigests = new LinkedHashSet<>();
      for (int i = 0; i < actions.size(); i++) {
        JsonNode action = actions.get(i);
        if (!"APPROVED".equalsIgnoreCase(action.path("status").asText())) {
          continue;
        }
        String provider = action.path("provider").asText("");
        String model = action.path("model").asText("");
        String decision = action.path("decision").asText("");
        String approvalId = action.path("approvalId").asText("");
        String proposalDigest = action.path("proposalDigest").asText("");
        ApprovalIdentity identity = new ApprovalIdentity(approvalId, proposalDigest);
        CorrectionResolution resolution = resolutions.remove(identity);
        AttemptIdentity workerAttempt = attemptIdentity(action.path("workerAttempt"));
        boolean postReview = action.path("postReview").asBoolean(false);
        AttemptIdentity reviewerAttempt = attemptIdentity(action.path("reviewerAttempt"));
        if (provider.isBlank()
            || model.isBlank()
            || !isApprovedDecision(decision)
            || !isUuid(approvalId)
            || !proposalDigest.matches("[0-9a-f]{64}")
            || !approvalIds.add(approvalId)
            || !proposalDigests.add(proposalDigest)
            || (resolution != null && resolution.actionIndex() <= i)
            || (resolution == null || !resolution.mutationExcluded())
                && (workerAttempt == null || reviewerAttempt == null)) {
          throw blocked(
              "PUBLICATION_ROUTE_EVIDENCE_INVALID",
              "Approved continuation route evidence is incomplete or duplicated for "
                  + task.getTaskId()
                  + " at action "
                  + i);
        }
        approvedRoutes.add(
            new ApprovedRoute(
                i,
                provider,
                model,
                postReview,
                decision,
                approvalId,
                proposalDigest,
                workerAttempt,
                reviewerAttempt,
                resolution != null && resolution.mutationExcluded()));
      }
      if (!resolutions.isEmpty()) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Correction resolution does not match a preceding approved action for "
                + task.getTaskId());
      }
      return List.copyOf(approvedRoutes);
    } catch (PublicationAdmissionService.PublicationBlockedException blocked) {
      throw blocked;
    } catch (Exception malformed) {
      throw blocked(
          "PUBLICATION_ROUTE_EVIDENCE_INVALID",
          "Continuation route evidence is malformed for " + task.getTaskId());
    }
  }

  private Map<ApprovalIdentity, CorrectionResolution> correctionResolutions(
      AgentTask task, JsonNode actions) {
    Map<ApprovalIdentity, CorrectionResolution> resolutions = new LinkedHashMap<>();
    for (int i = 0; i < actions.size(); i++) {
      JsonNode action = actions.get(i);
      if (!"CORRECTION_RESOLUTION".equals(action.path("decision").asText())) {
        continue;
      }
      String approvalId = action.path("approvalId").asText("");
      String proposalDigest = action.path("proposalDigest").asText("");
      if (approvalId.isBlank() && proposalDigest.isBlank()) {
        // Legacy unbound resolutions cannot suppress attestation of any approved mutation.
        continue;
      }
      String status = action.path("status").asText("");
      String beforeGeneration = action.path("beforeWorkspaceGeneration").asText("");
      String afterGeneration = action.path("afterWorkspaceGeneration").asText("");
      boolean mutationExcluded =
          ("REJECTED".equals(status) || "ROLLED_BACK".equals(status))
              && !beforeGeneration.isBlank()
              && beforeGeneration.equals(afterGeneration)
              && action.path("evidenceInvalidated").asBoolean(false);
      if (!isUuid(approvalId)
          || !proposalDigest.matches("[0-9a-f]{64}")
          || !("ACCEPTED".equals(status)
              || "REJECTED".equals(status)
              || "ROLLED_BACK".equals(status))
          || (("REJECTED".equals(status) || "ROLLED_BACK".equals(status)) && !mutationExcluded)) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Correction resolution identity or rollback proof is invalid for "
                + task.getTaskId()
                + " at action "
                + i);
      }
      ApprovalIdentity identity = new ApprovalIdentity(approvalId, proposalDigest);
      if (resolutions.putIfAbsent(identity, new CorrectionResolution(i, mutationExcluded))
          != null) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Correction resolution identity is duplicated for " + task.getTaskId());
      }
    }
    return resolutions;
  }

  private static AttemptIdentity attemptIdentity(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    String provider = node.path("provider").asText("");
    String model = node.path("model").asText("");
    String attribution = node.path("attribution").asText("");
    long floor = node.path("callIndexFloor").asLong(-1L);
    long first = node.path("firstCallIndex").asLong(-1L);
    long last = node.path("lastCallIndex").asLong(-1L);
    JsonNode idsNode = node.path("physicalAttemptIds");
    if (provider.isBlank()
        || model.isBlank()
        || !"LEDGER".equals(attribution)
        || floor < 0
        || first <= floor
        || last < first
        || !idsNode.isArray()
        || idsNode.isEmpty()) {
      return null;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (JsonNode id : idsNode) {
      if (!id.isTextual() || id.asText().isBlank() || !ids.add(id.asText())) {
        return null;
      }
    }
    return new AttemptIdentity(provider, model, List.copyOf(ids), floor, first, last);
  }

  private static boolean isUuid(String value) {
    try {
      return value != null && value.equals(java.util.UUID.fromString(value).toString());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static boolean isApprovedDecision(String value) {
    return "STEP_UP_MODEL".equals(value)
        || "STEP_DOWN_MODEL".equals(value)
        || "CHANGE_PROVIDER".equals(value);
  }

  private static boolean sameProvider(String expected, String actual) {
    return expected != null
        && actual != null
        && expected.trim().toUpperCase(Locale.ROOT).equals(actual.trim().toUpperCase(Locale.ROOT));
  }

  private static String logicalTaskId(String runId, String taskId) {
    if (runId == null || taskId == null || runId.length() < 8) {
      return taskId;
    }
    String prefix = runId.substring(0, 8) + "_";
    return taskId.startsWith(prefix) ? taskId.substring(prefix.length()) : taskId;
  }

  private static PublicationAdmissionService.PublicationBlockedException blocked(
      String code, String message) {
    return new PublicationAdmissionService.PublicationBlockedException(
        new PublicationAdmissionService.Eligibility(
            false, "BLOCKED", code, "PUBLICATION", message, false, null, null, null));
  }

  private record ApprovedRoute(
      int actionIndex,
      String provider,
      String model,
      boolean postReview,
      String decision,
      String approvalId,
      String proposalDigest,
      AttemptIdentity workerAttempt,
      AttemptIdentity reviewerAttempt,
      boolean mutationExcluded) {
    String expectedApprovalStatus() {
      return mutationExcluded ? "REJECTED" : "COMPLETED";
    }
  }

  private record ApprovalIdentity(String approvalId, String proposalDigest) {}

  private record CorrectionResolution(int actionIndex, boolean mutationExcluded) {}

  private record AttemptIdentity(
      String provider,
      String model,
      List<String> physicalAttemptIds,
      long callIndexFloor,
      long firstCallIndex,
      long lastCallIndex) {
    boolean matches(
        RunEffectiveRouteService.AttemptRouteEvidence ledger,
        String expectedProvider,
        String expectedModel) {
      return ledger != null
          && ledger.isExact(expectedProvider, expectedModel)
          && sameProvider(expectedProvider, provider)
          && expectedModel.equals(model)
          && new HashSet<>(physicalAttemptIds).equals(new HashSet<>(ledger.physicalAttemptIds()))
          && firstCallIndex == ledger.firstCallIndex()
          && lastCallIndex == ledger.lastCallIndex();
    }
  }
}
