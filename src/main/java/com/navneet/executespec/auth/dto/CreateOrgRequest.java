package com.navneet.executespec.auth.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateOrgRequest(
    @NotBlank String name,
    @NotEmpty List<String> domains, // e.g. ["acme.com", "acmecorp.com"]
    @NotBlank String adminEmail // admin contact email for the org
    ) {}
