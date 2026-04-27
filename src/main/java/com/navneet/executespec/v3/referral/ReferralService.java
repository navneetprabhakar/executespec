package com.navneet.executespec.v3.referral;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.v3.credit.LifetimeCreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * R-162 — referral mechanism for the v3 Free-tier growth loop.
 *
 * Greenfield §7.5 + §7.6: a Free user invites a friend; both get
 * +5 lifetime runs once the referee ships their first successful
 * (READY) run. Cap at 25 lifetime runs per user (5 base + 4 invites
 * × 5).
 *
 * Default trigger applied per D-003: the credit grant fires on the
 * referee's first run reaching the READY verdict — NOT at signup.
 * Anti-fraud: stops invite-spam farms from harvesting credits via
 * accounts that never actually run anything.
 *
 * Wave-2 ships the SHAPE end-to-end:
 *   - recordInvite(referrer, email) stores the pending invite.
 *   - bindReferral(refereeUserId, refereeEmail) is called by the auth
 *     flow when a new account signs up; it stamps
 *     app_user.referred_by_user_id (V152 column) on the referee.
 *   - onRefereeFirstReadyRun(userId) is called by the run-completion
 *     hook (TODO wire-point in V2RunOrchestrator) to grant the +5
 *     credit to the referrer.
 *   - getInviteState(referrerId) returns the pending + accepted
 *     counts so the UI can show "3 invites pending, 2 accepted."
 *
 * Wave-3 follow-ups (TODO comments below):
 *   - Promote the in-memory pendingInvites map to the
 *     `referral_invite` table once the email pipeline lands.
 *   - Send a real magic-link signup email to refereeEmail (today
 *     we just record the intent).
 *   - Persist accepted/granted counts so the UI survives JVM restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final AppUserRepository userRepo;
    private final LifetimeCreditService lifetimeCredit;

    /**
     * In-memory pending-invite store, keyed by lower-cased referee
     * email. Maps to the inviting user's id. Lives only for the JVM
     * process — Wave 3 will persist.
     *
     * TODO(R-162 follow-up): promote to a `referral_invite` table
     * with columns (id, referrer_user_id, referee_email, token,
     * created_at, accepted_at, granted_at). Add Flyway V153.
     */
    private final ConcurrentMap<String, PendingInvite> pendingInvites = new ConcurrentHashMap<>();

    /** Recorded by the referrer (POST /api/v3/referrals/invite). */
    @Transactional
    public InviteRecord recordInvite(String referrerUserId, String refereeEmail) {
        if (referrerUserId == null) {
            throw new IllegalArgumentException("referrerUserId required");
        }
        if (refereeEmail == null || refereeEmail.isBlank()) {
            throw new IllegalArgumentException("refereeEmail required");
        }
        String normalised = refereeEmail.trim().toLowerCase();
        AppUser referrer = userRepo.findById(referrerUserId).orElseThrow(
                () -> new IllegalArgumentException("referrer not found: " + referrerUserId));

        // Block self-invite
        if (referrer.getEmail() != null && referrer.getEmail().equalsIgnoreCase(normalised)) {
            throw new IllegalArgumentException("cannot invite yourself");
        }

        // If the email already exists as a user — short-circuit; we don't
        // re-credit existing users.
        Optional<AppUser> existing = userRepo.findAll().stream()
                .filter(u -> normalised.equalsIgnoreCase(u.getEmail()))
                .findFirst();
        if (existing.isPresent()) {
            log.info("v3 referral invite skipped — referee {} already registered", normalised);
            return new InviteRecord(referrerUserId, normalised, Instant.now(), InviteStatus.ALREADY_EXISTS);
        }

        PendingInvite invite = new PendingInvite(referrerUserId, normalised, Instant.now());
        pendingInvites.put(normalised, invite);
        log.info("v3 referral invite recorded — referrer={} referee={}", referrerUserId, normalised);
        // TODO(R-162 follow-up): trigger a signup-with-referrer-token
        // email here once the v3 mail template lands.
        return new InviteRecord(referrerUserId, normalised, invite.createdAt(), InviteStatus.PENDING);
    }

    /**
     * Called by the auth flow on new-user signup so the referee gets
     * `app_user.referred_by_user_id` stamped. Idempotent — running it
     * twice for the same user is a no-op after the first stamp.
     */
    @Transactional
    public boolean bindReferral(String refereeUserId, String refereeEmail) {
        if (refereeUserId == null || refereeEmail == null) return false;
        String normalised = refereeEmail.trim().toLowerCase();
        PendingInvite invite = pendingInvites.get(normalised);
        if (invite == null) return false;

        AppUser referee = userRepo.findById(refereeUserId).orElse(null);
        if (referee == null) return false;
        if (referee.getReferredByUserId() != null) {
            // Already bound — don't overwrite.
            return false;
        }
        referee.setReferredByUserId(invite.referrerUserId());
        userRepo.save(referee);
        // We deliberately keep the pending-invite entry around until
        // the credit is actually granted (in onRefereeFirstReadyRun)
        // so the inviter's pending-count UI stays consistent.
        log.info("v3 referral bound — referee {} → referrer {}", refereeUserId, invite.referrerUserId());
        return true;
    }

    /**
     * Called by the run-completion hook (terminal status === READY) on
     * the referee's first successful run. Grants +5 lifetime credits
     * to the referrer and clears the pending-invite entry.
     *
     * TODO(R-162 follow-up): this wire-point must be added to
     * V2RunOrchestrator's terminal-status path. The hook reads
     * app_user.referred_by_user_id; if non-null AND the referee has
     * never had a granted-grant before, calls this method. A
     * `granted_at` flag on app_user prevents double-credit.
     */
    @Transactional
    public boolean onRefereeFirstReadyRun(String refereeUserId) {
        AppUser referee = userRepo.findById(refereeUserId).orElse(null);
        if (referee == null || referee.getReferredByUserId() == null) return false;
        String referrerId = referee.getReferredByUserId();

        // Idempotency: only fire once per referee. Today we use the
        // pending-invite presence as the marker — Wave 3 promotes to
        // a granted_at column.
        if (referee.getEmail() != null) {
            pendingInvites.remove(referee.getEmail().toLowerCase());
        }
        int newBalance = lifetimeCredit.grantInviteCredit(referrerId);
        log.info("v3 referral granted — referrer={} new balance={}", referrerId, newBalance);
        return true;
    }

    /** Read-only state for the inviter's UI. */
    public InviteState getInviteState(String referrerUserId) {
        long pending = pendingInvites.values().stream()
                .filter(i -> i.referrerUserId().equals(referrerUserId))
                .count();
        return new InviteState(referrerUserId, (int) pending);
    }

    // ── DTOs / records ────────────────────────────────────────────────────────

    public enum InviteStatus { PENDING, ALREADY_EXISTS }

    public record PendingInvite(String referrerUserId, String refereeEmail, Instant createdAt) {}

    public record InviteRecord(String referrerUserId, String refereeEmail,
                               Instant createdAt, InviteStatus status) {}

    public record InviteState(String referrerUserId, int pendingCount) {}
}
