package com.navneet.executespec.v3.api;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.v3.referral.ReferralService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * R-162 — referral endpoint for v3 Free-tier growth loop.
 *
 * Wave 2 ships the recordInvite + getInviteState surface. The
 * "credit grant on referee's first ready run" hook is owned by
 * V2RunOrchestrator's terminal-status path; the wire-point is left
 * as a TODO in ReferralService.onRefereeFirstReadyRun.
 */
@RestController
@RequestMapping("/api/v3/referrals")
@RequiredArgsConstructor
public class V3ReferralController {

    private final ReferralService service;

    @PostMapping("/invite")
    public ResponseEntity<ReferralService.InviteRecord> invite(
            @Valid @RequestBody InviteRequest body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.status(401).build();
        try {
            ReferralService.InviteRecord rec = service.recordInvite(
                    userDetails.getUser().getId(), body.email());
            return ResponseEntity.ok(rec);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/state")
    public ResponseEntity<ReferralService.InviteState> state(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(service.getInviteState(userDetails.getUser().getId()));
    }

    /**
     * R-162 magic-link landing lookup. Public-by-token (no auth) — the token
     * IS the credential. Used by V3Auth.tsx to render inviter context on
     * <code>/v3/auth?ref=&lt;token&gt;</code>. Always returns 200 with
     * {@code valid=false} on unknown tokens so the frontend can fall back
     * to a generic "you were invited" UX rather than 404.
     */
    @GetMapping("/lookup")
    public ResponseEntity<ReferralService.ReferralLookup> lookup(
            @RequestParam("token") String token) {
        return ResponseEntity.ok(service.lookupByToken(token));
    }

    public record InviteRequest(
            @NotBlank @Email String email
    ) {}
}
