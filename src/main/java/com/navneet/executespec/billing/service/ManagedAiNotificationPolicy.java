package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.billing.entity.ManagedAiStateNotification;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;

import org.springframework.stereotype.Component;

@Component
class ManagedAiNotificationPolicy {

  static final String CHANNEL_IN_APP = "IN_APP";
  static final String CHANNEL_EMAIL = "EMAIL";

  boolean shouldNotify(ManagedAiUsagePeriod period, String fromState, String toState) {
    return period != null && toState != null && !toState.equals(fromState);
  }

  boolean shouldEmail(String state) {
    return ManagedAiRoutingPolicy.FRONTIER_LOCKED.equals(state)
        || ManagedAiRoutingPolicy.CHEAP_ONLY.equals(state)
        || ManagedAiRoutingPolicy.EXHAUSTED.equals(state);
  }

  ManagedAiStateNotification record(
      ManagedAiUsagePeriod period, String fromState, String toState, String channel) {
    return ManagedAiStateNotification.builder()
        .id(UUID.randomUUID().toString())
        .periodId(period.getId())
        .userId(period.getUserId())
        .orgId(period.getOrgId())
        .fromState(fromState)
        .toState(toState)
        .channel(channel)
        .sentAt(Instant.now())
        .build();
  }

  String copyFor(String state) {
    return switch (state) {
      case ManagedAiRoutingPolicy.VALUE_MODE ->
          "ExecuteSpec is optimizing model routing to preserve your included AI credits.";
      case ManagedAiRoutingPolicy.FRONTIER_LOCKED ->
          "Premium frontier models are paused for this billing period. Efficient coding models"
              + " remain available.";
      case ManagedAiRoutingPolicy.CHEAP_ONLY ->
          "Efficient model mode is active. Coding work will continue on benchmarked low-cost"
              + " models.";
      case ManagedAiRoutingPolicy.EXHAUSTED ->
          "Managed AI credits are exhausted. Use BYOK, buy top-up credits, or wait for reset.";
      case ManagedAiRoutingPolicy.FULL ->
          "Managed AI routing has reset to full model availability.";
      default -> "Managed AI routing state changed.";
    };
  }
}
