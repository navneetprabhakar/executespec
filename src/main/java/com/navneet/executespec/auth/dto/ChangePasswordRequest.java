package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    String currentPassword, // null/empty for forced-change flow
    // RR-603 — align the DTO floor with the single server-owned rule in
    // AuthService.validatePasswordStrength (min 10). The old min=8 advertised a weaker policy than
    // the service actually enforces; the service still runs the full complexity/whitespace corpus.
    @NotBlank @Size(min = 10, max = 128) String newPassword) {}
