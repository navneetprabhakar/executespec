package com.navneet.executespec.execution.dto;

import java.util.List;

public record CreateExecutionRequest(
        String requirement,
        List<String> repoIds,
        List<NewRepoSpec> newRepos,
        Boolean jiraEnabled,
        String confluenceUrl,
        String figmaUrl,
        String aiProvider,
        String branch,
        /** v2.5.7 — continue-on-PR: ID of the execution that created the original PR. */
        String sourceExecutionId,
        /** v2.5.7 — continue-on-PR: repo full name (e.g. "owner/repo"). */
        String continuePrRepoFullName,
        /** v2.5.7 — continue-on-PR: PR number to address. */
        Integer continuePrNumber) {
}
