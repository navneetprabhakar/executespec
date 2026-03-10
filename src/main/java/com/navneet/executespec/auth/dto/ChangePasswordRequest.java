package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        String currentPassword,            // null/empty for forced-change flow
        @NotBlank @Size(min = 8) String newPassword
) {}
