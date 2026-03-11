package com.navneet.executespec.auth.dto;

import java.util.List;

/**
 * Stable pagination DTO for audit log queries.
 * Avoids Spring Data PageImpl serialization warnings.
 */
public record AuditLogPage(
    List<AuditLogEntry> content,
    int totalPages,
    long totalElements,
    int number,
    int size
) {
    public static AuditLogPage from(org.springframework.data.domain.Page<AuditLogEntry> page) {
        return new AuditLogPage(
            page.getContent(),
            page.getTotalPages(),
            page.getTotalElements(),
            page.getNumber(),
            page.getSize()
        );
    }
}
