package com.navneet.executespec.deployment.preview;

public class PreviewException extends RuntimeException {
  public PreviewException(String message) {
    super(message);
  }

  public PreviewException(String message, Throwable cause) {
    super(message, cause);
  }
}
