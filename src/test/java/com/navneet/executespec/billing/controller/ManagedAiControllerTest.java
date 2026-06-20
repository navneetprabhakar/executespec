package com.navneet.executespec.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiBalanceResponse;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiPolicyResponse;
import com.navneet.executespec.billing.service.ManagedAiUsageService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ManagedAiControllerTest {

  @Test
  void exposesBalanceEventsAndPolicyForAuthenticatedUser() {
    ManagedAiUsageService service = mock(ManagedAiUsageService.class);
    ManagedAiController controller = new ManagedAiController(service);
    AppUserDetails user = user();
    ManagedAiBalanceResponse balance =
        new ManagedAiBalanceResponse(
            "u1",
            "o1",
            "PRO",
            ManagedAiUsageService.FULL,
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(3600),
            2000,
            10,
            1990,
            16,
            0.1,
            15.9,
            4,
            0,
            4,
            4,
            12,
            16,
            true,
            1.25);
    when(service.balance(user)).thenReturn(balance);
    when(service.currentEvents(user, 50)).thenReturn(List.of());
    when(service.policy(user))
        .thenReturn(
            new ManagedAiPolicyResponse(
                true,
                "PRO",
                2000,
                16,
                4,
                4,
                12,
                16,
                1.25,
                false,
                false,
                List.of(50),
                "$1 = 100 ES AI credits"));

    assertThat(controller.balance(user).getBody()).isEqualTo(balance);
    assertThat(controller.events("current", 50, user).getBody()).isEmpty();
    assertThat(controller.policy(user).getBody().tier()).isEqualTo("PRO");
    verify(service).balance(user);
    verify(service).currentEvents(user, 50);
    verify(service).policy(user);
  }

  @Test
  void rejectsUnsupportedHistoricalPeriodQueryUntilPaginationExists() {
    ManagedAiController controller = new ManagedAiController(mock(ManagedAiUsageService.class));

    assertThat(controller.events("previous", 50, user()).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private static AppUserDetails user() {
    return new AppUserDetails(
        AppUser.builder()
            .id("u1")
            .email("forge@example.com")
            .firstName("Forge")
            .tier("PRO")
            .status("ACTIVE")
            .orgId("o1")
            .build(),
        null);
  }
}
