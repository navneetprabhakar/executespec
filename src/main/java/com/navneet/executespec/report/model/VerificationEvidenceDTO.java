package com.navneet.executespec.report.model;

/**
 * One auditable signal behind a validation report's score.
 *
 * <p>The headline score is useful for routing, but users need to see which claims are backed by
 * deterministic evidence, which are LLM-derived, and which were not measured. Keep this DTO
 * string-valued rather than enum-heavy so older clients can ignore new categories/statuses.
 */
public record VerificationEvidenceDTO(
    String id,
    String label,
    String category,
    String status,
    String source,
    String confidence,
    String claim,
    String summary,
    String artifact,
    Integer observed,
    Integer total) {}
