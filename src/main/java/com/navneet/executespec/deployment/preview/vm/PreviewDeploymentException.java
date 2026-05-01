package com.navneet.executespec.deployment.preview.vm;

public class PreviewDeploymentException extends RuntimeException {

  public PreviewDeploymentException(String message) {
    super(message);
  }

  public PreviewDeploymentException(String message, Throwable cause) {
    super(message, cause);
  }
}
