package com.navneet.executespec.router;

/** Indicates that a server-owned model-matrix row is absent or incomplete. */
public final class ModelMatrixConfigurationException extends IllegalStateException {

  public ModelMatrixConfigurationException(String message) {
    super(message);
  }
}
