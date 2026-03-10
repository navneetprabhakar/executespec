package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateOrgRequest(
        @NotBlank String name,
        @NotEmpty List<String> domains   // e.g. ["acme.com", "acmecorp.com"]
) {}
