package com.navneet.executespec.specchat.policy;

import java.time.Instant;
import java.util.Set;

import com.navneet.executespec.specchat.ChatToolPolicy;

/**
 * Narrow read-only seam between chat authorization and the durable confirmation protocol.
 * Implementations own persistence, expiry and single-use consumption; chat policy resolution only
 * snapshots the already-authorized capabilities for this exact turn.
 */
public interface ProjectChatConfirmationAuthority {

  ConfirmationGrant resolve(ConfirmationQuery query);

  record ConfirmationQuery(
      String actorId,
      String orgId,
      String projectId,
      String sessionId,
      String turnId,
      String correlationId) {}

  record ConfirmationGrant(
      String reference, Instant expiresAt, Set<ChatToolPolicy.Capability> confirmedCapabilities) {

    public ConfirmationGrant {
      confirmedCapabilities =
          confirmedCapabilities == null ? Set.of() : Set.copyOf(confirmedCapabilities);
    }
  }
}
