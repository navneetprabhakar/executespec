package com.navneet.executespec.auth.oauth;

/**
 * Normalized profile extracted from any OAuth2 provider.
 */
public record OAuthProfile(
        String provider,    // "google" or "github"
        String oauthId,     // provider-specific unique ID
        String email,
        String name,
        String avatarUrl
) {}
