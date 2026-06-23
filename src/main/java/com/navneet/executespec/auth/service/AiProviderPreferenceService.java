package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.util.Locale;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** User-level AI provider preference shared by web, CLI, and VS Code. */
@Service
@RequiredArgsConstructor
public class AiProviderPreferenceService {

  private final AppUserRepository userRepo;

  public String preferredProvider(AppUserDetails userDetails) {
    AppUser user = requireUser(userDetails);
    return blankToNull(user.getPreferredAiProvider());
  }

  public String resolveProvider(AppUserDetails userDetails, String explicitProvider) {
    String explicit = blankToNull(explicitProvider);
    if (explicit != null) return normalizeProvider(explicit);
    return preferredProvider(userDetails);
  }

  @Transactional
  public String updatePreferredProvider(AppUserDetails userDetails, String provider) {
    AppUser user = requireUser(userDetails);
    String normalized = normalizeProvider(provider);
    user.setPreferredAiProvider(normalized);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);
    return normalized;
  }

  @Transactional
  public void rememberExplicitProvider(AppUserDetails userDetails, String provider) {
    String normalized = blankToNull(provider);
    if (normalized == null) return;
    updatePreferredProvider(userDetails, normalized);
  }

  public String normalizeProvider(String provider) {
    String value = blankToNull(provider);
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider is required");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if ("GOOGLE".equals(normalized)) normalized = "GEMINI";
    try {
      LlmProvider.valueOf(normalized);
      ModelAccessService.assertProviderEnabled(normalized);
      return normalized;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + provider);
    }
  }

  private static AppUser requireUser(AppUserDetails userDetails) {
    if (userDetails == null || userDetails.getUser() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    return userDetails.getUser();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
