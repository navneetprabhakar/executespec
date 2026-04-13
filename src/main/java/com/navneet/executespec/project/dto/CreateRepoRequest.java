package com.navneet.executespec.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body for {@code POST /api/repos}. {@code scope} must be {@code ORG} or {@code USER};
 * the service enforces that the caller has the right to own a repo at that scope
 * (ORG → must be ORG_ADMIN of their org; USER → always allowed for self).
 */
public record CreateRepoRequest(
        @NotBlank
        @Pattern(regexp = "ORG|USER", message = "scope must be ORG or USER")
        String scope,

        /** e.g. "owner/repo". */
        @NotBlank
        String githubRepoFullName,

        String defaultBranch
) {}
