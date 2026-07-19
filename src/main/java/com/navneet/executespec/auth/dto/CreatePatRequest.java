package com.navneet.executespec.auth.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a Personal Access Token. {@code expiresInDays} is optional — null means
 * the token never expires.
 */
public record CreatePatRequest(
    @NotBlank @Size(max = 100) String name,
    Integer expiresInDays,
    @Size(max = 8) List<@Size(max = 40) String> scopes) {

  /** Source-compatible constructor for callers compiled against the original two-field DTO. */
  public CreatePatRequest(String name, Integer expiresInDays) {
    this(name, expiresInDays, null);
  }
}
