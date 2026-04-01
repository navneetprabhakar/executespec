package com.navneet.executespec.auth.oauth;

/**
 * Thrown when an OAuth login attempt uses an email that is already registered
 * with a different OAuth provider (or via email/password).
 */
public class OAuthEmailConflictException extends RuntimeException {
    public OAuthEmailConflictException(String message) {
        super(message);
    }
}
