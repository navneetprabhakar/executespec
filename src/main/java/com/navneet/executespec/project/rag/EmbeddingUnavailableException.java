package com.navneet.executespec.project.rag;

/** Policy/capability rejection that callers may honestly degrade to lexical retrieval. */
public final class EmbeddingUnavailableException extends RuntimeException {

  private final String reason;

  public EmbeddingUnavailableException(String reason) {
    super(reason);
    this.reason = reason;
  }

  public EmbeddingUnavailableException(String reason, Throwable cause) {
    super(reason, cause);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
