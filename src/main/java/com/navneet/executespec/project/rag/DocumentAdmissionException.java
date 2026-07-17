package com.navneet.executespec.project.rag;

import org.springframework.http.HttpStatus;

/** Client-safe, typed rejection for an untrusted project-document upload. */
public final class DocumentAdmissionException extends RuntimeException {

  private final String code;
  private final HttpStatus status;

  public DocumentAdmissionException(String code, String message) {
    this(code, message, HttpStatus.BAD_REQUEST);
  }

  public DocumentAdmissionException(String code, String message, HttpStatus status) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }
}
