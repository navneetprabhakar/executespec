package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/projects}.
 *
 * <p>{@code @Pattern} guards close adversarial-test-report Finding 6 — name and description used to
 * accept raw HTML/script content which would flow back out via notifications, share-link PDFs, and
 * email templates. React renders these as text by default so the client wasn't exploitable, but the
 * defense is cheap and removes the footgun for any future rendering surface.
 */
public record CreateProjectRequest(
    @NotBlank @Size(max = 120, message = "name must be 1-120 characters") @Pattern(regexp = "^[^<>]+$", message = "name must not contain < or > characters") String name,
    @Size(max = 2000, message = "description must not exceed 2000 characters") @Pattern(regexp = "^[^<>]*$", message = "description must not contain < or > characters") String description,
    @Pattern(
            regexp = "ANTHROPIC|GEMINI|GOOGLE|OPENAI|DEEPSEEK|OPENROUTER|FIREWORKS",
            message =
                "aiProvider must be one of ANTHROPIC, GEMINI, OPENAI, DEEPSEEK, OPENROUTER,"
                    + " FIREWORKS")
        String aiProvider,
    @Size(max = 120, message = "githubOwner must not exceed 120 characters") @Pattern(
            regexp = "^[A-Za-z0-9][A-Za-z0-9-]*$|^$",
            message = "githubOwner must be a valid GitHub owner/login")
        String githubOwner) {}
