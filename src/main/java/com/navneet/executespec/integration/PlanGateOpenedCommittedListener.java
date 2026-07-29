package com.navneet.executespec.integration;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Emits user-visible gate-open signals only after the durable tuple is committed. */
@Component
@RequiredArgsConstructor
@Slf4j
class PlanGateOpenedCommittedListener {
  private final SseBroadcaster broadcaster;
  private final ApplicationEventPublisher eventPublisher;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onCommitted(PlanGateOpenedCommitted event) {
    try {
      broadcaster.emit(event.runId(), RunEvent.runStatus(event.runId(), "AWAITING_APPROVAL"));
    } catch (RuntimeException statusSignalFailure) {
      log.warn(
          "run={} committed plan-gate status signal failed errorClass={}",
          event.runId(),
          statusSignalFailure.getClass().getSimpleName());
    }
    try {
      broadcaster.emit(
          event.runId(),
          RunEvent.gateOpenedWithCredits(
              event.runId(), "PLAN_APPROVAL", event.credits(), event.estimatedCost()));
    } catch (RuntimeException gateSignalFailure) {
      log.warn(
          "run={} committed plan-gate signal failed errorClass={}",
          event.runId(),
          gateSignalFailure.getClass().getSimpleName());
    }
    try {
      eventPublisher.publishEvent(
          new PipelineEvent.GateOpened(event.runId(), "PLAN_APPROVAL", event.credits()));
    } catch (RuntimeException domainEventFailure) {
      log.warn(
          "run={} committed plan-gate domain event failed errorClass={}",
          event.runId(),
          domainEventFailure.getClass().getSimpleName());
    }
  }
}
