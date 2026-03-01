package com.navneet.agentteam.deployment.strategy;

import com.navneet.agentteam.deployment.DeploymentProperties;
import com.navneet.agentteam.state.entity.Deployment;

import java.nio.file.Path;

public interface DeploymentStrategy {

    /** Return the strategy type identifier. */
    Deployment.DeployStrategy type();

    /** Return true if this strategy can handle the given workspace. */
    boolean applies(Path workspacePath);

    /** Generate Dockerfile content for this project type. */
    String generateDockerfile(Path workspacePath, DeploymentProperties props);

    /** Return a human-readable description of what was detected. */
    String detectionSummary(Path workspacePath);
}
