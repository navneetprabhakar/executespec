package com.navneet.executespec.auth.oauth;

/**
 * Normalized profile extracted from any OAuth2 provider.
 *
 * @param emailVerified RR-131 — whether the provider asserted this email as verified (Google {@code
 *     email_verified} claim / a GitHub {@code /user/emails} entry with {@code verified=true}). A
 *     provider-supplied but unverified email must never be allowed to link to an existing account.
 */
public record OAuthProfile(
    String provider, // "google" or "github"
    String oauthId, // provider-specific unique ID
    String email,
    String name,
    String avatarUrl,
    boolean emailVerified) {}
