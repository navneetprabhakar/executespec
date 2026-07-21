package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.entity.ManagedAiStateNotification;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.notification.InAppNotificationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedAiNotificationServiceTest {

  private ManagedAiNotificationDedupe dedupe;
  private InAppNotificationService inAppNotificationService;
  private BillingNotificationService billingNotificationService;
  private AppUserRepository appUserRepository;
  private ManagedAiNotificationService service;

  @BeforeEach
  void setUp() {
    dedupe = mock(ManagedAiNotificationDedupe.class);
    inAppNotificationService = mock(InAppNotificationService.class);
    billingNotificationService = mock(BillingNotificationService.class);
    appUserRepository = mock(AppUserRepository.class);
    // Default: every claim wins (single-caller, no contention) unless a test overrides it.
    when(dedupe.claim(any())).thenReturn(true);
    service =
        new ManagedAiNotificationService(
            dedupe,
            inAppNotificationService,
            billingNotificationService,
            appUserRepository,
            new ManagedAiNotificationPolicy());
  }

  @Test
  void ignoresNoopTransition() {
    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.CHEAP_ONLY, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(dedupe, never()).claim(any());
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
    // The in-app channel is claimed exactly once and before delivery.
    verify(dedupe).claim(argChannel(ManagedAiNotificationPolicy.CHANNEL_IN_APP));
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
    verify(dedupe).claim(argChannel(ManagedAiNotificationPolicy.CHANNEL_IN_APP));
    verify(dedupe).claim(argChannel(ManagedAiNotificationPolicy.CHANNEL_EMAIL));
  }

  @Test
  void missingEmailDoesNotClaimOrSendEmailChannel() {
    when(appUserRepository.findById("u1"))
        .thenReturn(Optional.of(AppUser.builder().id("u1").email(" ").build()));

    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(billingNotificationService, never())
        .sendManagedAiRoutingStateChanged(any(), any(), any());
    // Only the in-app channel is claimed; the email channel is never claimed for a user with no
    // address, so no spurious EMAIL dedupe row is written.
    verify(dedupe).claim(argChannel(ManagedAiNotificationPolicy.CHANNEL_IN_APP));
    verify(dedupe, never()).claim(argChannel(ManagedAiNotificationPolicy.CHANNEL_EMAIL));
  }

  @Test
  void losingTheClaimSuppressesDelivery() {
    // The claim boundary reports "already claimed" — the delivery must not happen.
    when(dedupe.claim(any())).thenReturn(false);

    service.notifyTransition(
        period(), ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY);

    verify(inAppNotificationService, never()).create(any(), any(), any(), any(), any());
    verify(billingNotificationService, never())
        .sendManagedAiRoutingStateChanged(any(), any(), any());
    // Email presence is never even consulted once the in-app claim is lost... the email channel is
    // still attempted, but it too loses its claim, so nothing is sent.
    verify(appUserRepository).findById("u1");
  }

  /**
   * RR-520 — concurrent transitions deliver exactly once. The dedupe stub models the database
   * unique index: the first claim of a given (period, to_state, channel) wins, every concurrent
   * claim of the same identity loses. Two threads race the same in-app transition; only one
   * delivery escapes.
   */
  @Test
  void concurrentTransitionsSendOnce() throws InterruptedException {
    Set<String> claimed = ConcurrentHashMap.newKeySet();
    // First claimer of each identity wins; all others lose — exactly what the unique index
    // enforces.
    when(dedupe.claim(any()))
        .thenAnswer(
            inv -> {
              ManagedAiStateNotification r = inv.getArgument(0);
              return claimed.add(r.getPeriodId() + "|" + r.getToState() + "|" + r.getChannel());
            });

    AtomicInteger inAppDeliveries = new AtomicInteger();
    when(inAppNotificationService.create(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              inAppDeliveries.incrementAndGet();
              return null;
            });

    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  service.notifyTransition(
                      period(), ManagedAiRoutingPolicy.FULL, ManagedAiRoutingPolicy.VALUE_MODE);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    start.countDown();
    done.await();

    assertThat(inAppDeliveries.get()).isEqualTo(1);
    verify(inAppNotificationService, times(1)).create(any(), any(), any(), any(), any());
  }

  private static ManagedAiStateNotification argChannel(String channel) {
    return org.mockito.ArgumentMatchers.argThat(r -> r != null && channel.equals(r.getChannel()));
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
