package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
        @NotBlank String name,
        String description,
        String aiProvider
) {}
