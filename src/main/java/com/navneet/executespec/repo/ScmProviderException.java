package com.navneet.executespec.repo;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Typed, secret-free upstream failure used by controllers and readiness persistence. */
public class ScmProviderException extends ResponseStatusException {
  private final String errorCode;
  private final Integer upstreamStatus;
  private final boolean retryable;

  public ScmProviderException(String errorCode, Integer upstreamStatus, boolean retryable) {
    super(httpStatus(upstreamStatus), errorCode);
    this.errorCode = errorCode;
    this.upstreamStatus = upstreamStatus;
    this.retryable = retryable;
  }

  public String errorCode() {
    return errorCode;
  }

  public Integer upstreamStatus() {
    return upstreamStatus;
  }

  public boolean retryable() {
    return retryable;
  }

  private static HttpStatus httpStatus(Integer upstreamStatus) {
    if (upstreamStatus == null) return HttpStatus.BAD_GATEWAY;
    return switch (upstreamStatus) {
      case 400 -> HttpStatus.BAD_REQUEST;
      case 401 -> HttpStatus.UNPROCESSABLE_ENTITY;
      case 403 -> HttpStatus.FORBIDDEN;
      case 404 -> HttpStatus.NOT_FOUND;
      case 429 -> HttpStatus.TOO_MANY_REQUESTS;
      default -> HttpStatus.BAD_GATEWAY;
    };
  }
}
