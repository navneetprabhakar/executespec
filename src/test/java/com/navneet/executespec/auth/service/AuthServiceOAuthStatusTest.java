package com.navneet.executespec.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import com.navneet.executespec.auth.entity.AppUser;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceOAuthStatusTest {

  @Test
  void oauthLoginRejectsSuspendedAndDeletedUsersBeforeLinking() {
    assertEquals(
        403,
        assertThrows(
                ResponseStatusException.class,
                () -> AuthService.assertOAuthLoginAllowed(userWithStatus("SUSPENDED")))
            .getStatusCode()
            .value());
    assertEquals(
        403,
        assertThrows(
                ResponseStatusException.class,
                () -> AuthService.assertOAuthLoginAllowed(userWithStatus("DELETED")))
            .getStatusCode()
            .value());
  }

  @Test
  void oauthLoginAllowsActiveAndPendingUsers() {
    assertDoesNotThrow(() -> AuthService.assertOAuthLoginAllowed(userWithStatus("ACTIVE")));
    assertDoesNotThrow(
        () -> AuthService.assertOAuthLoginAllowed(userWithStatus("PENDING_VERIFICATION")));
  }

  private static AppUser userWithStatus(String status) {
    return AppUser.builder()
        .id("u-" + status)
        .email(status.toLowerCase() + "@example.com")
        .firstName("Test")
        .globalRole("USER")
        .tier("FREE")
        .status(status)
        .build();
  }
}
