package com.navneet.executespec.activity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.integration.PlanGateDecisionCommitted;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

/**
 * Approval auditability — bridges the atomic V348 plan-gate decision to the project activity feed
 * and the append-only {@code activity_audit} chain (both {@link
 * ActivityEventType#RUN_PLAN_APPROVED} and {@link ActivityEventType#RUN_PLAN_REJECTED} are
 * dual-surface).
 *
 * <p>Before this listener, plan approvals were actorless and eventless on every audit surface: the
 * durable {@code DecisionEvidenceV1} receipt bound the real actor, but nothing appended a feed or
 * audit event, and the only feed entries near approval ({@code RUN_PLAN_READY}) carried a null
 * actor which {@link ActivityEventEmitter} renders as {@code actorType=SYSTEM}. This listener
 * passes the REAL deciding human ({@code PlanGateDecisionService} guarantees a human actor for
 * APPROVE/REJECT), so both surfaces record {@code USER} plus the actor's id, and email/display in
 * the structured payload.
 *
 * <p>Transactional discipline (V348 atomicity is sacred): the decision service publishes {@link
 * PlanGateDecisionCommitted} inside its transaction; this listener runs {@code AFTER_COMMIT} and
 * {@code @Async}, so it can never join, delay, or roll back the decision, and a rolled-back
 * decision emits nothing. Exact replays never publish the event (the service returns the stored
 * receipt before the publish site), so a replayed decision cannot append a duplicate entry.
 *
 * <p>Standalone (non-project) runs are skipped — both the feed and the emitter's audit write are
 * project-keyed, which matches the existing run-lifecycle listener behavior.
 */
@Component
@RequiredArgsConstructor
public class PlanGateDecisionActivityListener {

  private final ActivityEventEmitter emitter;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDecisionCommitted(PlanGateDecisionCommitted event) {
    ActivityEventType type =
        switch (event.decision()) {
          case APPROVE -> ActivityEventType.RUN_PLAN_APPROVED;
          case REJECT -> ActivityEventType.RUN_PLAN_REJECTED;
          case CANCEL -> null; // pre-approval cancel is not a customer plan decision event
        };
    if (type == null) return;
    UUID projectId = parseUuid(event.projectId());
    if (projectId == null) return; // standalone run — feed + audit surfaces are project-scoped
    UUID actor = parseUuid(event.actorUserId());
    String decidedBy =
        event.actorEmail() != null && !event.actorEmail().isBlank()
            ? event.actorEmail()
            : event.actorDisplayName() != null && !event.actorDisplayName().isBlank()
                ? event.actorDisplayName()
                : "a project member";
    String summary =
        (type == ActivityEventType.RUN_PLAN_APPROVED ? "Plan approved by " : "Plan rejected by ")
            + decidedBy
            + (event.runTitle() != null && !event.runTitle().isBlank()
                ? ": " + event.runTitle()
                : "");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runId", event.runId());
    payload.put("decision", event.decision().name());
    payload.put("actorUserId", event.actorUserId() == null ? "" : event.actorUserId());
    payload.put("actorEmail", event.actorEmail() == null ? "" : event.actorEmail());
    payload.put(
        "actorDisplayName", event.actorDisplayName() == null ? "" : event.actorDisplayName());
    emitter.emit(projectId, type, actor, "RUN", event.runId(), summary, payload);
  }

  private static UUID parseUuid(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
