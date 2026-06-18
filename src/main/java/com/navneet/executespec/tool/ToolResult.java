package com.navneet.executespec.tool;

/**
 * Typed result of a {@link Tool#execute} call.
 *
 * <p>Sealed so callers can pattern-match on success vs error and react accordingly. Errors are
 * first-class — V2 forces the harness (and ultimately the LLM) to handle them explicitly rather
 * than parsing free-form strings as V1 did.
 */
public sealed interface ToolResult {

  /** Successful execution. {@code content} is what the LLM sees. */
  record Ok(String content) implements ToolResult {
    public Ok {
      if (content == null) content = "";
    }
  }

  /**
   * Failed execution. {@code message} is human/LLM-readable. {@code kind} lets the harness
   * differentiate retryable failures from terminal ones.
   */
  record Err(String message, ErrorKind kind) implements ToolResult {
    public Err {
      if (message == null || message.isBlank()) message = "(no message)";
      if (kind == null) kind = ErrorKind.INTERNAL;
    }
  }

  /** Coarse error taxonomy. Used by the harness to decide retry vs abort. */
  enum ErrorKind {
    /** Tool input failed schema validation or was semantically invalid. Not retryable as-is. */
    INVALID_ARGS,
    /** Target file/directory/resource does not exist. Model may need to listFiles first. */
    NOT_FOUND,
    /** Target exists and the requested operation refused to overwrite/collide. */
    ALREADY_EXISTS,
    /** The file was not read in this session, or changed since the last read. */
    STALE_READ,
    /** Filesystem or network I/O failed. Often retryable. */
    IO_ERROR,
    /** HTTP fetch/search failed with a non-2xx response. */
    HTTP_ERROR,
    /** Operation blocked by sandbox policy or path-escape guard. Not retryable. */
    FORBIDDEN,
    /** Command ran but exited non-zero. */
    COMMAND_FAILED,
    /** Operation exceeded its time budget. May be retryable with a smaller scope. */
    TIMEOUT,
    /** Tool is known to the protocol but not implemented in this backend execution mode. */
    UNAVAILABLE,
    /** Unclassified internal failure. Treated as non-retryable by default. */
    INTERNAL
  }

  /** Convenience factory for success. */
  static ToolResult ok(String content) {
    return new Ok(content);
  }

  /** Convenience factory for an error with a specific kind. */
  static ToolResult err(ErrorKind kind, String message) {
    return new Err(message, kind);
  }
}
