package com.navneet.executespec.api.dto;

import java.time.Instant;

public record MemberUsage(
    String userId,
    String firstName,
    String lastName,
    String email,
    int runCount,
    double successRate,
    Instant lastRunDate
) {}
