package com.navneet.executespec.execution.dto;

import java.util.List;

public record CreateExecutionRequest(
        String requirement,
        List<String> repoIds,
        Boolean jiraEnabled) {
}
