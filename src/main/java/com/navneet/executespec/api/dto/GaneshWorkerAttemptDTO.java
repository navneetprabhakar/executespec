package com.navneet.executespec.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public record GaneshWorkerAttemptDTO(
    String taskId,
    int attempt,
    String provider,
    String model,
    String verificationStatus,
    String failureStage,
    JsonNode evidence,
    Instant createdAt) {}
