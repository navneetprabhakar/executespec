package com.navneet.executespec.v3.referral;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.v3.credit.LifetimeCreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReferralServiceTest {

    private AppUserRepository repo;
    private LifetimeCreditService credit;
    private ReferralService svc;

    @BeforeEach
    void setUp() {
        repo = mock(AppUserRepository.class);
        credit = mock(LifetimeCreditService.class);
        svc = new ReferralService(repo, credit);
        when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AppUser user(String id, String email) {
        AppUser u = AppUser.builder()
                .id(id).email(email).firstName("X").tier("FREE").build();
        when(repo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void records_pending_invite_for_unknown_email() {
        user("u1", "alice@test");
        when(repo.findAll()).thenReturn(List.of());

        ReferralService.InviteRecord rec = svc.recordInvite("u1", "bob@test");

        assertEquals("u1", rec.referrerUserId());
        assertEquals("bob@test", rec.refereeEmail());
        assertEquals(ReferralService.InviteStatus.PENDING, rec.status());
    }

    @Test
    void short_circuits_when_referee_already_registered() {
        user("u1", "alice@test");
        AppUser bob = user("u2", "bob@test");
        when(repo.findAll()).thenReturn(List.of(bob));

        ReferralService.InviteRecord rec = svc.recordInvite("u1", "bob@test");

        assertEquals(ReferralService.InviteStatus.ALREADY_EXISTS, rec.status());
        // pending count should NOT include this invite
        ReferralService.InviteState st = svc.getInviteState("u1");
        assertEquals(0, st.pendingCount());
    }

    @Test
    void rejects_self_invite() {
        user("u1", "alice@test");
        assertThrows(IllegalArgumentException.class,
                () -> svc.recordInvite("u1", "alice@test"));
    }

    @Test
    void rejects_blank_email() {
        user("u1", "alice@test");
        assertThrows(IllegalArgumentException.class,
                () -> svc.recordInvite("u1", "  "));
    }

    @Test
    void bind_referral_stamps_referee_referred_by_user_id() {
        user("u1", "alice@test");
        AppUser bob = user("u2", "bob@test");
        when(repo.findAll()).thenReturn(List.of());

        svc.recordInvite("u1", "bob@test");
        boolean bound = svc.bindReferral("u2", "bob@test");

        assertTrue(bound);
        assertEquals("u1", bob.getReferredByUserId());
        verify(repo).save(bob);
    }

    @Test
    void bind_referral_idempotent_when_already_bound() {
        AppUser bob = user("u2", "bob@test");
        bob.setReferredByUserId("someone-else");
        // No pending invite required for the second-call test
        // since we're checking that an already-bound user isn't
        // re-bound when a stale invite exists.
        boolean bound = svc.bindReferral("u2", "bob@test");

        assertFalse(bound, "must not overwrite an existing referral binding");
        assertEquals("someone-else", bob.getReferredByUserId());
    }

    @Test
    void on_referee_first_ready_run_grants_credit_to_referrer() {
        AppUser bob = user("u2", "bob@test");
        bob.setReferredByUserId("u1");
        when(credit.grantInviteCredit("u1")).thenReturn(10);

        boolean granted = svc.onRefereeFirstReadyRun("u2");

        assertTrue(granted);
        verify(credit).grantInviteCredit("u1");
    }

    @Test
    void on_first_ready_run_no_op_when_no_referral() {
        AppUser bob = user("u2", "bob@test");
        // No referredByUserId set
        boolean granted = svc.onRefereeFirstReadyRun("u2");
        assertFalse(granted);
        verify(credit, never()).grantInviteCredit(anyString());
    }

    @Test
    void invite_state_counts_only_caller_pending_invites() {
        user("u1", "alice@test");
        user("u3", "carol@test");
        when(repo.findAll()).thenReturn(List.of());

        svc.recordInvite("u1", "bob@test");
        svc.recordInvite("u1", "dan@test");
        svc.recordInvite("u3", "eve@test");

        assertEquals(2, svc.getInviteState("u1").pendingCount());
        assertEquals(1, svc.getInviteState("u3").pendingCount());
    }
}
