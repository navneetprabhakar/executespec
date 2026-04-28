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

        String defaultBranch,

        /** R-325 — when true, bypass the {@code executespec.repo.max-size-kb}
         *  hard reject and attach the repo even if GitHub reports it
         *  larger than the platform's clone-time budget. The UI surfaces
         *  this as an opt-in button only after the initial attach was
         *  rejected with the size-gate 413; the actual size is persisted
         *  on {@code project_repo.size_kb} so a downstream credit
         *  surcharge can be billed against runs that touch this repo.
         *
         *  <p>Defaults to {@code false} (i.e. honour the gate) when omitted
         *  by the client. */
        Boolean attachAnyway
) {}
