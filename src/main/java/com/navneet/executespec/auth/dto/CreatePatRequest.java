package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a Personal Access Token. {@code expiresInDays} is optional — null means
 * the token never expires.
 */
public record CreatePatRequest(@NotBlank @Size(max = 100) String name, Integer expiresInDays) {}
