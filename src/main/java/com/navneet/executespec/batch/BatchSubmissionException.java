package com.navneet.executespec.batch;

/**
 * Thrown when a batch submission is rejected by the provider.
 */
public class BatchSubmissionException extends RuntimeException {

    private final int statusCode;

    public BatchSubmissionException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public BatchSubmissionException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public BatchSubmissionException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
