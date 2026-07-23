package com.navneet.executespec.repo;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** SCM providers supported by the brownfield project-source registry. */
public enum ScmProvider {
  GITHUB,
  BITBUCKET;

  public static ScmProvider require(String raw) {
    if (raw == null || raw.isBlank()) return GITHUB;
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported SCM provider");
    }
  }
}
