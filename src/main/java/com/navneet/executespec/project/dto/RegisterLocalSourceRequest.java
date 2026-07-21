package com.navneet.executespec.project.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registers or renews a local-folder source without transmitting its filesystem path. Both IDs are
 * client-minted random UUIDv4 values; neither may be derived from a path or machine username.
 */
public record RegisterLocalSourceRequest(
    @NotNull UUID localFolderId,
    @NotNull UUID installationId,
    @NotBlank @Size(max = 160) String displayName,
    @Size(max = 128) String revision,
    @Size(max = 16) List<@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> capabilities,
    @Size(max = 12) Map<@Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,63}") String, @Size(max = 256) String>
            metadata,
    @Min(30) @Max(300) Integer leaseSeconds) {}
