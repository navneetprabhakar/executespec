package com.navneet.agentteam.api.dto;

public record DeployRequest(
    String runId,
    String repoMode,    // "NEW", "EXISTING", or "NONE"
    String repoUrl,     // required for EXISTING; null otherwise
    String repoName     // optional override for NEW; null otherwise
) {}
