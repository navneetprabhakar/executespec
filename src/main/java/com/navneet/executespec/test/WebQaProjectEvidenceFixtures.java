package com.navneet.executespec.test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Focused collaborator for {@link WebQaFixtureController}: deterministic architecture-version and
 * correction-transaction evidence used by the provider-free browser walkthroughs. Kept separate so
 * the controller stays under the god-class ceiling; all safety-profile admission remains the
 * controller's responsibility.
 */
@Profile("web-qa")
@Component
@RequiredArgsConstructor
class WebQaProjectEvidenceFixtures {

  /**
   * First line of every fixture-authored architecture version. Cleanup deletes exactly the docs
   * carrying this marker, so real generated architecture content is never touched.
   */
  static final String ARCHITECTURE_FIXTURE_MARKER = "<!-- web-qa-architecture-fixture -->";

  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectArchitectureDocRepository architectureDocRepository;
  private final AgentTaskRepository taskRepository;
  private final com.navneet.executespec.agentsession.repository.AgentSessionRepository
      sessionRepository;
  private final com.navneet.executespec.agentsession.repository.AgentTurnRepository turnRepository;
  private final com.navneet.executespec.agentsession.repository.AgentActionEventRepository
      eventRepository;

  record SessionEvidence(
      String sessionId, com.navneet.executespec.agentsession.entity.AgentTurn turn) {}

  /** Seed the deterministic WEB agent session, turn, and action event for the fixture capture. */
  SessionEvidence seedAgentSessionEvidence(String userId, String orgId) {
    String sessionId = WebQaFixtureController.PREFIX + UUID.randomUUID();
    AgentSession session =
        sessionRepository.save(
            com.navneet.executespec.agentsession.entity.AgentSession.builder()
                .id(sessionId)
                .orgId(orgId)
                .userId(userId)
                .clientSurface("WEB")
                .title("Release QA agent evidence")
                .status("ACTIVE")
                .defaultExecutionMode("REMOTE")
                .approvalPolicy("MANUAL")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    AgentTurn turn =
        turnRepository.save(
            AgentTurn.builder()
                .id(WebQaFixtureController.PREFIX + "turn-" + UUID.randomUUID())
                .sessionId(sessionId)
                .orgId(orgId)
                .userId(userId)
                .sequence(1)
                .userMessage("Implement the release QA fixture")
                .assistantSummary("Fixture action is ready for approval.")
                .intentType("CODE_RUN_REQUIRED")
                .intentConfidence(1.0)
                .actionType("create_cloud_run")
                .proposedActionJson(
                    "{\"action\":\"create_cloud_run\",\"requiresConfirmation\":true}")
                .status("OK")
                .build());
    eventRepository.save(
        AgentActionEvent.builder()
            .id(WebQaFixtureController.PREFIX + "event-" + UUID.randomUUID())
            .sessionId(sessionId)
            .turnId(turn.getId())
            .actionId(WebQaFixtureController.PREFIX + "action")
            .type("GOAL_DECISION")
            .payloadJson("{\"status\":\"READY\",\"summary\":\"Fixture evidence is ready\"}")
            .createdAt(Instant.now())
            .build());
    return new SessionEvidence(sessionId, turn);
  }

  /**
   * Seed exactly two deterministic architecture versions for a fixture-created project so the
   * Compare History browser walkthrough can assert a rendered CONTENT diff rather than a mere
   * "changed" marker. Both docs carry {@link #ARCHITECTURE_FIXTURE_MARKER} as their first line and
   * empty {@code generated_from_shas} maps, so the diff endpoint performs no GitHub calls and
   * cleanup can remove exactly these rows. Project write authority is asserted through the
   * canonical guard; this never touches real generated architecture content.
   */
  Map<String, Object> seedArchitectureVersions(AppUserDetails principal, String projectId) {
    projectAccessGuard.assertCanWrite(projectId, principal);
    int nextVersion =
        architectureDocRepository.findByProjectIdOrderByVersionDesc(projectId).stream()
            .findFirst()
            .map(doc -> doc.getVersion() + 1)
            .orElse(1);
    String baseContent =
        ARCHITECTURE_FIXTURE_MARKER
            + "\n# System map\n\n## Services\n\n- checkout-service — owns cart and order flow\n"
            + "- inventory-service — owns stock levels\n";
    ProjectArchitectureDoc from =
        architectureDocRepository.save(
            ProjectArchitectureDoc.builder()
                .projectId(projectId)
                .version(nextVersion)
                .content(baseContent)
                .generatedFromShas("{}")
                .generatedFromBranches("{}")
                .generatedAt(Instant.now().minusSeconds(60))
                .build());
    ProjectArchitectureDoc to =
        architectureDocRepository.save(
            ProjectArchitectureDoc.builder()
                .projectId(projectId)
                .version(nextVersion + 1)
                .content(
                    baseContent
                        + "- notification-service — NEW dispatches order confirmation emails\n")
                .generatedFromShas("{}")
                .generatedFromBranches("{}")
                .generatedAt(Instant.now())
                .build());
    return Map.of(
        "fromVersion", from.getVersion(),
        "toVersion", to.getVersion(),
        "addedLine", "notification-service — NEW dispatches order confirmation emails");
  }

  /**
   * Remove exactly the fixture-authored architecture versions (marker-scoped) so a fixture run can
   * never leave synthetic content behind for a real project view. Returns the deleted count.
   */
  int deleteArchitectureFixtures() {
    List<ProjectArchitectureDoc> fixtureDocs =
        architectureDocRepository.findAll().stream()
            .filter(doc -> doc.getContent() != null)
            .filter(doc -> doc.getContent().startsWith(ARCHITECTURE_FIXTURE_MARKER))
            .toList();
    architectureDocRepository.deleteAll(fixtureDocs);
    return fixtureDocs.size();
  }

  /**
   * Attach one deterministic ACCEPTED correction transaction to the success run so the Evidence
   * tab's correction card renders from durable {@code continuation_actions_json} exactly as it
   * would for a real checkpointed correction. The JSON is the same passthrough shape {@code
   * RunMapper} delivers to {@code TaskSummary.continuationActions}; no correction_attempt row,
   * provider call, or score evidence is created.
   */
  void seedCorrectionEvidenceTask(String runId) {
    String beforeGeneration = "a".repeat(64);
    String afterGeneration = "b".repeat(64);
    String continuationActionsJson =
        """
[{"status":"EXECUTED","decision":"CORRECTION","provider":"fixture","model":"fixture",
  "reason":"Fixture correction accepted after checkpointed retry",
  "source":"POST_VALIDATION_REPORT","code":"CORRECTION_ACCEPTED",
  "approvalId":"web-qa-approval-1","proposalVersion":1,
  "proposalDigest":"%s",
  "beforeWorkspaceGeneration":"%s","afterWorkspaceGeneration":"%s",
  "diffDigest":"%s","changedFiles":["src/app.ts"],
  "unifiedPatch":"--- a/src/app.ts\\n+++ b/src/app.ts\\n@@ -1 +1 @@\\n-export const status = 'failed';\\n+export const status = 'complete';\\n",
  "patchMetadata":{"capturedBytes":118,"fullBytes":118},
  "validationDelta":{
    "before":{"status":"STALE","workspaceGeneration":"%s",
      "build":{"verdict":"FAILED","passed":false,"exitCode":1},
      "tests":{"verdict":"FAILED","passed":0,"total":2,"executionVerified":true}},
    "after":{"status":"COMPLETE","workspaceGeneration":"%s",
      "build":{"verdict":"PASSED","passed":true,"exitCode":0},
      "tests":{"verdict":"PASSED","passed":2,"total":2,"executionVerified":true}}},
  "evidenceInvalidated":true,"validationRegenerated":true}]
"""
            .formatted(
                "c".repeat(64),
                beforeGeneration,
                afterGeneration,
                "d".repeat(64),
                beforeGeneration,
                afterGeneration);
    taskRepository.save(
        AgentTask.builder()
            .taskId(runId + "-correction-task")
            .runId(runId)
            .title("Repair failing build via checkpointed correction")
            .complexity("LOW")
            .status("COMPLETE")
            .taskJson("{\"id\":\"fixture-correction-task\"}")
            .completionNotes("Correction accepted; evidence regenerated")
            .provider("fixture")
            .model("fixture")
            .continuationActionsJson(continuationActionsJson)
            .startedAt(Instant.now().minusSeconds(20))
            .completedAt(Instant.now())
            .build());
  }
}
