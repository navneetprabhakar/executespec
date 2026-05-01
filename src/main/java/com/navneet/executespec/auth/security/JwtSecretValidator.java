package com.navneet.executespec.auth.security;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Validates the JWT secret at startup in production environments. Fails fast if the secret is too
 * short or still set to the development placeholder.
 */
@Component
@Profile("prod")
@Slf4j
public class JwtSecretValidator {

  private static final int MIN_SECRET_LENGTH = 32;
  private static final String DEV_PLACEHOLDER_MARKER = "must-be";

  @Value("${app.jwt.secret}")
  private String jwtSecret;

  @PostConstruct
  void validate() {
    if (jwtSecret == null || jwtSecret.isBlank()) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret is not configured. Set a strong secret (>= 32 chars) for"
              + " production.");
    }
    if (jwtSecret.toLowerCase().contains(DEV_PLACEHOLDER_MARKER)) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret still contains the development placeholder. "
              + "Set a strong, unique secret (>= 32 chars) for production.");
    }
    if (jwtSecret.length() < MIN_SECRET_LENGTH) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret is too short ("
              + jwtSecret.length()
              + " chars). "
              + "Must be at least "
              + MIN_SECRET_LENGTH
              + " characters for HS256.");
    }
    log.info("[JwtSecretValidator] JWT secret validation passed (length={})", jwtSecret.length());
  }
}
