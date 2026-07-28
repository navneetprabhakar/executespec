package com.navneet.executespec.report.model;

/** Typed link from the final goal decision to one durable validation-ledger row. */
public record GoalProofEvidenceReferenceDTO(
    String evidenceId,
    String label,
    String proofStatus,
    String observedStatus,
    String source,
    String confidence,
    String artifact,
    Integer observed,
    Integer total) {}
