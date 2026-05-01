package com.navneet.executespec.deployment.strategy;

import java.nio.file.Path;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.state.entity.Deployment;

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
