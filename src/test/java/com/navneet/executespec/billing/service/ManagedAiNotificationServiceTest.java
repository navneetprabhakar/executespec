package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.entity.ManagedAiStateNotification;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiStateNotificationRepository;
import com.navneet.executespec.notification.InAppNotificationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManagedAiNotificationServiceTest {

  private ManagedAiStateNotificationRepository repository;
  private InAppNotificationService inAppNotificationService;
  private BillingNotificationService billingNotificationService;
  private AppUserRepository appUserRepository;
  private ManagedAiNotificationService service;

  @BeforeEach
  void setUp() {
    repository = mock(ManagedAiStateNotificationRepository.class);
    inAppNotificationService = mock(InAppNotificationService.class);
    billingNotificationService = mock(BillingNotificationService.class);
    appUserRepository = mock(AppUserRepository.class);
    service =
        new ManagedAiNotificationService(
            repository,
            inAppNotificationService,
            billingNotificationService,
            appUserRepository,
            new ManagedAiNotificationPolicy());
  }

  @Test
  void ignoresNoopTransition() {
    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.CHEAP_ONLY, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(repository, never()).save(any());
    verify(inAppNotificationService, never()).create(any(), any(), any(), any(), any());
    verify(billingNotificationService, never())
        .sendManagedAiRoutingStateChanged(any(), any(), any());
  }

  @Test
  void sendsValueModeInAppOnly() {
    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.FULL, ManagedAiRoutingPolicy.VALUE_MODE);

    verify(inAppNotificationService)
        .create(
            eq("u1"),
            eq("MANAGED_AI_ROUTING_STATE"),
            eq("Managed AI routing update"),
            eq("ExecuteSpec is optimizing model routing to preserve your included AI credits."),
            eq("/billing"));
    verify(billingNotificationService, never())
        .sendManagedAiRoutingStateChanged(any(), any(), any());
    ArgumentCaptor<ManagedAiStateNotification> records =
        ArgumentCaptor.forClass(ManagedAiStateNotification.class);
    verify(repository).save(records.capture());
    assertThat(records.getValue().getChannel())
        .isEqualTo(ManagedAiNotificationPolicy.CHANNEL_IN_APP);
  }

  @Test
  void sendsEmailStateToBothChannelsWhenUserHasEmail() {
    when(appUserRepository.findById("u1"))
        .thenReturn(Optional.of(AppUser.builder().id("u1").email("u1@example.com").build()));

    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(inAppNotificationService).create(eq("u1"), any(), any(), any(), eq("/billing"));
    verify(billingNotificationService)
        .sendManagedAiRoutingStateChanged(
            eq("u1@example.com"), eq(ManagedAiRoutingPolicy.CHEAP_ONLY), any());
    ArgumentCaptor<ManagedAiStateNotification> records =
        ArgumentCaptor.forClass(ManagedAiStateNotification.class);
    verify(repository, org.mockito.Mockito.times(2)).save(records.capture());
    assertThat(records.getAllValues())
        .extracting(ManagedAiStateNotification::getChannel)
        .containsExactly(
            ManagedAiNotificationPolicy.CHANNEL_IN_APP, ManagedAiNotificationPolicy.CHANNEL_EMAIL);
  }

  @Test
  void missingEmailDoesNotWriteEmailDedupeRecord() {
    when(appUserRepository.findById("u1"))
        .thenReturn(Optional.of(AppUser.builder().id("u1").email(" ").build()));

    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(billingNotificationService, never())
        .sendManagedAiRoutingStateChanged(any(), any(), any());
    ArgumentCaptor<ManagedAiStateNotification> records =
        ArgumentCaptor.forClass(ManagedAiStateNotification.class);
    verify(repository).save(records.capture());
    assertThat(records.getAllValues())
        .extracting(ManagedAiStateNotification::getChannel)
        .containsExactly(ManagedAiNotificationPolicy.CHANNEL_IN_APP);
  }

  @Test
  void dedupedChannelsAreNotSentAgain() {
    when(repository.existsByPeriodIdAndToStateAndChannel(
            "period-1",
            ManagedAiRoutingPolicy.CHEAP_ONLY,
            ManagedAiNotificationPolicy.CHANNEL_IN_APP))
        .thenReturn(true);
    when(repository.existsByPeriodIdAndToStateAndChannel(
            "period-1",
            ManagedAiRoutingPolicy.CHEAP_ONLY,
            ManagedAiNotificationPolicy.CHANNEL_EMAIL))
        .thenReturn(true);

    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(inAppNotificationService, never()).create(any(), any(), any(), any(), any());
    verify(appUserRepository, never()).findById(any());
    verify(repository, never()).save(any());
  }

  private static ManagedAiUsagePeriod period() {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    return ManagedAiUsagePeriod.builder()
        .id("period-1")
        .userId("u1")
        .orgId("o1")
        .tier("PRO")
        .periodStart(now)
        .periodEnd(now.plusSeconds(31L * 24 * 3600))
        .routingState(ManagedAiRoutingPolicy.VALUE_MODE)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
