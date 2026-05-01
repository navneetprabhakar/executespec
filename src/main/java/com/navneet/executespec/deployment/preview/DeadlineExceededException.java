package com.navneet.executespec.deployment.preview;

import lombok.Getter;

/**
 * Thrown when the total preview startup deadline (5 minutes) is exceeded. Carries the phase name
 * where the timeout occurred (e.g., "database readiness", "container build").
 */
@Getter
public class DeadlineExceededException extends RuntimeException {

  private final String phase;

  public DeadlineExceededException(String phase) {
    super("Preview startup deadline exceeded during: " + phase);
    this.phase = phase;
  }
}
