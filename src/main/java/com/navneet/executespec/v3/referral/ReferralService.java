package com.navneet.executespec.v3.referral;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.v3.credit.LifetimeCreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
 * Persistence (V153): all invites live in the {@code referral_invite}
 * table. Wave-2 used an in-memory ConcurrentHashMap that lost state
 * on JVM restart; that path was retired in this refactor.
 *
 * Idempotency anchor: {@code referral_invite.granted_at}. The credit
 * grant only fires on rows where {@code accepted_at IS NOT NULL AND
 * granted_at IS NULL}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    /** Per-invite grant — see greenfield doc §7.5. */
    public static final int INVITE_GRANT = 5;

    private final AppUserRepository userRepo;
    private final ReferralInviteRepository inviteRepo;
    private final LifetimeCreditService lifetimeCredit;
    /** R-162 follow-up — send the magic-link signup email so an
     *  invite is more than a recorded intent. Best-effort; @Async +
     *  try/catch ensures email failures never block the invite write. */
    private final MailService mailService;

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

        // Block self-invite.
        if (referrer.getEmail() != null && referrer.getEmail().equalsIgnoreCase(normalised)) {
            throw new IllegalArgumentException("cannot invite yourself");
        }

        // Short-circuit when the referee already has an account — no
        // re-credit, no row created.
        Optional<AppUser> existing = userRepo.findAll().stream()
                .filter(u -> normalised.equalsIgnoreCase(u.getEmail()))
                .findFirst();
        if (existing.isPresent()) {
            log.info("v3 referral invite skipped — referee {} already registered", normalised);
            return new InviteRecord(referrerUserId, normalised, Instant.now(), InviteStatus.ALREADY_EXISTS);
        }

        // Idempotency: if this referrer already invited this email,
        // return the existing row rather than creating a duplicate.
        Optional<ReferralInvite> dupe = inviteRepo.findByReferrerAndEmail(referrerUserId, normalised);
        if (dupe.isPresent()) {
            ReferralInvite r = dupe.get();
            return new InviteRecord(r.getReferrerUserId(), r.getRefereeEmail(), r.getCreatedAt(), InviteStatus.PENDING);
        }

        ReferralInvite invite = ReferralInvite.builder()
                .id(UUID.randomUUID().toString())
                .referrerUserId(referrerUserId)
                .refereeEmail(normalised)
                .token(generateToken())
                .createdAt(Instant.now())
                .build();
        inviteRepo.save(invite);
        log.info("v3 referral invite recorded — referrer={} referee={} id={}",
                referrerUserId, normalised, invite.getId());

        // R-162 follow-up — fire the magic-link signup email. Best-effort:
        // a missing SMTP config or transient mail error must never abort
        // the invite write (which the user has already paid the round-trip
        // for). MailService methods are @Async so this returns immediately.
        try {
            String displayName = referrer.getFirstName();
            if (displayName == null || displayName.isBlank()) {
                displayName = referrer.getEmail();
            }
            mailService.sendV3ReferralInvite(normalised, displayName, invite.getToken());
        } catch (Exception mailErr) {
            log.warn("v3 referral email failed for invite={} — recorded but not delivered: {}",
                    invite.getId(), mailErr.getMessage());
        }

        return new InviteRecord(referrerUserId, normalised, invite.getCreatedAt(), InviteStatus.PENDING);
    }

    /**
     * Called by the auth flow on new-user signup so the referee gets
     * {@code app_user.referred_by_user_id} stamped + the matching
     * {@code referral_invite} row's {@code accepted_at} set. Idempotent
     * on the user row (won't overwrite an existing binding) and on the
     * invite row (won't re-stamp accepted_at).
     */
    @Transactional
    public boolean bindReferral(String refereeUserId, String refereeEmail) {
        if (refereeUserId == null || refereeEmail == null) return false;
        String normalised = refereeEmail.trim().toLowerCase();

        Optional<ReferralInvite> match = inviteRepo.findByRefereeEmailIgnoreCase(normalised);
        if (match.isEmpty()) return false;
        ReferralInvite invite = match.get();

        AppUser referee = userRepo.findById(refereeUserId).orElse(null);
        if (referee == null) return false;
        if (referee.getReferredByUserId() != null) {
            // User row already bound — don't overwrite.
            return false;
        }

        referee.setReferredByUserId(invite.getReferrerUserId());
        userRepo.save(referee);

        if (invite.getAcceptedAt() == null) {
            invite.setAcceptedAt(Instant.now());
            invite.setAcceptedByUserId(refereeUserId);
            inviteRepo.save(invite);
        }

        log.info("v3 referral bound — referee {} → referrer {}", refereeUserId, invite.getReferrerUserId());
        return true;
    }

    /**
     * Called by the run-completion hook (terminal status === READY) on
     * the referee's first successful run. Grants +5 lifetime credits
     * to the referrer and stamps {@code granted_at} so the grant only
     * fires once.
     *
     * Wired into V2RunOrchestrator.terminate().
     */
    @Transactional
    public boolean onRefereeFirstReadyRun(String refereeUserId) {
        AppUser referee = userRepo.findById(refereeUserId).orElse(null);
        if (referee == null || referee.getReferredByUserId() == null) return false;
        String referrerId = referee.getReferredByUserId();

        // Prefer the invite row keyed by accepted user-id (most precise).
        // Fall back to email-keyed lookup for legacy rows that never
        // got the user-id stamp.
        Optional<ReferralInvite> match = inviteRepo.findUngrantedByAcceptedUser(refereeUserId);
        if (match.isEmpty() && referee.getEmail() != null) {
            match = inviteRepo.findByRefereeEmailIgnoreCase(referee.getEmail());
        }
        if (match.isEmpty()) return false;
        ReferralInvite invite = match.get();
        if (invite.getGrantedAt() != null) {
            // Already granted — idempotent no-op.
            return false;
        }

        invite.setGrantedAt(Instant.now());
        inviteRepo.save(invite);

        int newBalance = lifetimeCredit.grantInviteCredit(referrerId);
        log.info("v3 referral granted — referrer={} new balance={} invite={}",
                referrerId, newBalance, invite.getId());
        return true;
    }

    /** Read-only state for the inviter's UI. */
    @Transactional(readOnly = true)
    public InviteState getInviteState(String referrerUserId) {
        long pending = inviteRepo.findPendingByReferrer(referrerUserId).size();
        return new InviteState(referrerUserId, (int) pending);
    }

    /**
     * R-162 follow-up — magic-link landing lookup. Resolves an invite token
     * to the inviter's display name + grant size so the V3Auth page can
     * render "Priya invited you — +5 free runs waiting" instead of a cold
     * sign-in form. Public-by-token (no auth) — the token IS the credential.
     *
     * <p>Returns {@link ReferralLookup#unknown()} for unknown / expired
     * tokens so the frontend can fall back to a generic "you were invited"
     * message rather than showing nothing or surfacing a 404. The token
     * existing at all means an inviter recently made it; treating it as a
     * generic warm landing is a safer UX than treating it as broken.
     */
    @Transactional(readOnly = true)
    public ReferralLookup lookupByToken(String token) {
        if (token == null || token.isBlank()) {
            return ReferralLookup.unknown();
        }
        Optional<ReferralInvite> match = inviteRepo.findByToken(token);
        if (match.isEmpty()) {
            return ReferralLookup.unknown();
        }
        ReferralInvite invite = match.get();
        AppUser referrer = userRepo.findById(invite.getReferrerUserId()).orElse(null);
        String name = (referrer == null || referrer.getFirstName() == null || referrer.getFirstName().isBlank())
                ? null
                : referrer.getFirstName();
        return new ReferralLookup(true, name, INVITE_GRANT, invite.getRefereeEmail());
    }

    private static String generateToken() {
        // 24-byte URL-safe random — same shape as anonymous-trial session token.
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    // ── DTOs / records (unchanged from Wave 2 wire) ─────────────────────────

    public enum InviteStatus { PENDING, ALREADY_EXISTS }

    public record InviteRecord(String referrerUserId, String refereeEmail,
                               Instant createdAt, InviteStatus status) {}

    public record InviteState(String referrerUserId, int pendingCount) {}

    /**
     * Magic-link landing payload. {@code valid=false} means we couldn't
     * resolve the token but the frontend should still render a warm
     * "you were invited" UX rather than treating the visitor like a
     * stranger — see {@link #lookupByToken} for rationale.
     */
    public record ReferralLookup(boolean valid, String inviterFirstName,
                                 int creditGrant, String refereeEmail) {
        public static ReferralLookup unknown() {
            return new ReferralLookup(false, null, INVITE_GRANT, null);
        }
    }
}
