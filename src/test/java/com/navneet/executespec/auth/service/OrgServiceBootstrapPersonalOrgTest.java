package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.gateway.StripePaymentGateway;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V162 — unit tests for {@link OrgService#bootstrapPersonalOrg(String)}.
 *
 * <p>Validates the solo-user bootstrap fix for the R-343 P0:
 * <ul>
 *   <li>Fresh user → personal org created, marked is_personal=true</li>
 *   <li>OrgMember row created with role=ORG_ADMIN (so the user passes the
 *       {@code POST /api/projects} role gate)</li>
 *   <li>{@code app_user.org_id} updated so the SandboxProvisioningService
 *       no longer hits NULL on insert</li>
 *   <li>Idempotent: calling twice doesn't double-create</li>
 *   <li>Existing real-org member is a no-op (don't replace real orgs)</li>
 *   <li>Stale orgId reference re-bootstraps cleanly</li>
 *   <li>Unknown user → 404</li>
 * </ul>
 *
 * <p>Pure Mockito; no Spring context.
 */
class OrgServiceBootstrapPersonalOrgTest {

    private OrgRepository orgRepo;
    private OrgMemberRepository orgMemberRepo;
    private AppUserRepository userRepo;
    private OrgService svc;

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrgRepository.class);
        orgMemberRepo = mock(OrgMemberRepository.class);
        userRepo = mock(AppUserRepository.class);
        svc = new OrgService(
                orgRepo,
                orgMemberRepo,
                userRepo,
                mock(PaymentAuditService.class),
                mock(ActivityAuditService.class),
                mock(MailService.class),
                mock(BillingSubscriptionRepository.class),
                mock(StripePaymentGateway.class));
    }

    private AppUser freshUser() {
        return AppUser.builder()
                .id("u-1")
                .email("casey@example.com")
                .firstName("Casey")
                .lastName("Vibe")
                .globalRole("USER")
                .tier("FREE")
                .status("ACTIVE")
                .orgId(null)
                .build();
    }

    @Test
    void bootstrap_freshUser_createsPersonalOrgWithOrgAdminMembership() {
        AppUser user = freshUser();
        when(userRepo.findById("u-1")).thenReturn(Optional.of(user));
        when(orgRepo.save(any(Org.class))).thenAnswer(inv -> inv.getArgument(0));

        Org result = svc.bootstrapPersonalOrg("u-1");

        // Returned org carries the right shape
        ArgumentCaptor<Org> orgCap = ArgumentCaptor.forClass(Org.class);
        verify(orgRepo).save(orgCap.capture());
        Org saved = orgCap.getValue();
        assertThat(saved.isPersonal()).isTrue();
        assertThat(saved.getCreatedByUserId()).isEqualTo("u-1");
        assertThat(saved.getTier()).isEqualTo("FREE");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getName()).isEqualTo("Casey's workspace");
        assertThat(saved.getAdminEmail()).isEqualTo("casey@example.com");

        // ORG_ADMIN row created so the role-gate on POST /api/projects passes
        ArgumentCaptor<OrgMember> memberCap = ArgumentCaptor.forClass(OrgMember.class);
        verify(orgMemberRepo).save(memberCap.capture());
        OrgMember member = memberCap.getValue();
        assertThat(member.getOrgRole()).isEqualTo("ORG_ADMIN");
        assertThat(member.getOrgId()).isEqualTo(saved.getId());
        assertThat(member.getUserId()).isEqualTo("u-1");

        // user.orgId stamped so SandboxProvisioningService.orgId(user.getOrgId())
        // no longer crashes on Project.orgId NOT NULL
        ArgumentCaptor<AppUser> userCap = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(userCap.capture());
        assertThat(userCap.getValue().getOrgId()).isEqualTo(saved.getId());

        assertThat(result).isSameAs(saved);
    }

    @Test
    void bootstrap_isIdempotent_existingOrgReturnsThatOrg() {
        AppUser user = freshUser();
        user.setOrgId("existing-org-id");
        Org existing = Org.builder()
                .id("existing-org-id").name("Real Co").tier("TEAM").build();
        when(userRepo.findById("u-1")).thenReturn(Optional.of(user));
        when(orgRepo.findById("existing-org-id")).thenReturn(Optional.of(existing));

        Org result = svc.bootstrapPersonalOrg("u-1");

        assertThat(result).isSameAs(existing);
        // No new org created — protects against double-bootstrap on retry
        verify(orgRepo, never()).save(any(Org.class));
        verify(orgMemberRepo, never()).save(any(OrgMember.class));
    }

    @Test
    void bootstrap_existingRealOrg_doesNotReplaceWithPersonal() {
        // User joined a paid org via accept-invite. Their orgId points at a real
        // (non-personal) org. Calling bootstrap again must NOT replace that
        // membership with a personal scope — the user should keep their team.
        AppUser user = freshUser();
        user.setOrgId("paid-org-id");
        Org paid = Org.builder()
                .id("paid-org-id").name("Paid Co").tier("ENTERPRISE")
                .isPersonal(false).build();
        when(userRepo.findById("u-1")).thenReturn(Optional.of(user));
        when(orgRepo.findById("paid-org-id")).thenReturn(Optional.of(paid));

        Org result = svc.bootstrapPersonalOrg("u-1");

        assertThat(result.isPersonal()).isFalse();
        assertThat(result.getId()).isEqualTo("paid-org-id");
        verify(orgRepo, never()).save(any(Org.class));
    }

    @Test
    void bootstrap_staleOrgId_rebootstrapsCleanly() {
        // user.orgId references an org that was deleted (e.g. a SUPER_ADMIN
        // tore down a stale personal org). We should treat this as no org and
        // create a fresh one.
        AppUser user = freshUser();
        user.setOrgId("deleted-org-id");
        when(userRepo.findById("u-1")).thenReturn(Optional.of(user));
        when(orgRepo.findById("deleted-org-id")).thenReturn(Optional.empty());
        when(orgRepo.save(any(Org.class))).thenAnswer(inv -> inv.getArgument(0));

        Org result = svc.bootstrapPersonalOrg("u-1");

        assertThat(result.isPersonal()).isTrue();
        assertThat(result.getId()).isNotEqualTo("deleted-org-id");
        verify(orgRepo).save(any(Org.class));
        verify(orgMemberRepo).save(any(OrgMember.class));
    }

    @Test
    void bootstrap_userNotFound_throws404() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.bootstrapPersonalOrg("ghost"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("User not found");
        verify(orgRepo, never()).save(any(Org.class));
    }

    @Test
    void bootstrap_emptyFirstName_fallsBackToEmailLocalPart() {
        AppUser user = freshUser();
        user.setFirstName(null);
        when(userRepo.findById("u-1")).thenReturn(Optional.of(user));
        when(orgRepo.save(any(Org.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.bootstrapPersonalOrg("u-1");

        ArgumentCaptor<Org> orgCap = ArgumentCaptor.forClass(Org.class);
        verify(orgRepo).save(orgCap.capture());
        // "casey@example.com".split("@")[0] = "casey"
        assertThat(orgCap.getValue().getName()).isEqualTo("casey's workspace");
    }
}
