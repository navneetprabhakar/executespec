package com.navneet.executespec.project.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Renews an already-registered local source's lease. Like registration, this carries no filesystem
 * path — only the opaque installation identity plus an optional bounded content revision.
 */
public record LocalSourceHeartbeatRequest(
    @NotNull UUID installationId,
    @Size(max = 128) String revision,
    @Min(30) @Max(300) Integer leaseSeconds) {}
