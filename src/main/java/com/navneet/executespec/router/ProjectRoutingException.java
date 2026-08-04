package com.navneet.executespec.router;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A customer-safe, structured project provider or model-matrix admission failure. */
public final class ProjectRoutingException extends ResponseStatusException {

  private final String errorCode;

  private ProjectRoutingException(
      HttpStatus status, String errorCode, String customerMessage, Throwable cause) {
    super(status, customerMessage, cause);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }

  public static ProjectRoutingException providerNotConfigured() {
    return new ProjectRoutingException(
        HttpStatus.CONFLICT,
        "PROJECT_PROVIDER_NOT_CONFIGURED",
        "Choose an AI provider in project or account settings.",
        null);
  }

  public static ProjectRoutingException providerInvalid() {
    return new ProjectRoutingException(
        HttpStatus.CONFLICT,
        "PROJECT_PROVIDER_INVALID",
        "Choose a supported AI provider in project or account settings.",
        null);
  }

  public static ProjectRoutingException modelMatrixUnavailable(Throwable cause) {
    return new ProjectRoutingException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "MODEL_MATRIX_UNAVAILABLE",
        "Project AI routing is temporarily unavailable.",
        cause);
  }
}
