package com.navneet.executespec.v3.referral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * R-162 follow-up — persistent referral-invite row (V153 schema).
 *
 * Replaces the in-memory ConcurrentHashMap that Wave 2 shipped. Each
 * row is created when a Free user invites a friend (recordInvite),
 * acceptedAt is stamped on the referee's signup binding (bindReferral),
 * grantedAt is stamped on the referee's first successful run via
 * V2RunOrchestrator's terminate hook (onRefereeFirstReadyRun).
 *
 * The granted_at column is the idempotency anchor: the credit grant
 * only fires on rows where accepted_at IS NOT NULL AND granted_at
 * IS NULL.
 */
@Entity
@Table(name = "referral_invite")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralInvite {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "referrer_user_id", nullable = false, length = 64)
    private String referrerUserId;

    @Column(name = "referee_email", nullable = false, length = 320)
    private String refereeEmail;

    /** Magic-link token for the future email pipeline. Null until
     *  the email path lands. */
    @Column(length = 64)
    private String token;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by_user_id", length = 64)
    private String acceptedByUserId;

    /** Idempotency anchor — once stamped, this invite never grants
     *  again. */
    @Column(name = "granted_at")
    private Instant grantedAt;
}
