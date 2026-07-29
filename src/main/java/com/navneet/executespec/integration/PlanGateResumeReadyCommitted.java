package com.navneet.executespec.integration;

/** Transactional event emitted after a first durable plan approval stages READY continuation. */
public record PlanGateResumeReadyCommitted(String runId) {}
