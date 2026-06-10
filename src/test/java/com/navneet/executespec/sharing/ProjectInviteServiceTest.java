package com.navneet.executespec.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.notification.InAppNotificationService;
import com.navneet.executespec.sharing.entity.ProjectInvite;
import com.navneet.executespec.sharing.repository.ProjectInviteRepository;
import com.navneet.executespec.sharing.service.ProjectInviteService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProjectInviteServiceTest {

  private final ProjectInviteRepository inviteRepo = mock(ProjectInviteRepository.class);
  private final ProjectInviteService service =
      new ProjectInviteService(
          inviteRepo,
          mock(ProjectRepository.class),
          mock(ProjectMemberRepository.class),
          mock(AppUserRepository.class),
          mock(TierConfigRepository.class),
          mock(OrgRepository.class),
          mock(OrgMemberRepository.class),
          mock(MailService.class),
          mock(ActivityEventEmitter.class),
          mock(InAppNotificationService.class));

  @Test
  void revokeRejectsInviteFromAnotherProject() {
    ProjectInvite invite = invite("project-a");
    when(inviteRepo.findById(invite.getId())).thenReturn(Optional.of(invite));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.revoke("project-b", invite.getId(), actor()));

    assertEquals(404, ex.getStatusCode().value());
    verify(inviteRepo, never()).save(invite);
  }

  @Test
  void revokeMarksSameProjectInviteRevoked() {
    ProjectInvite invite = invite("project-a");
    when(inviteRepo.findById(invite.getId())).thenReturn(Optional.of(invite));

    service.revoke("project-a", invite.getId(), actor());

    assertEquals("REVOKED", invite.getStatus());
    assertEquals("actor-1", invite.getRevokedByUserId());
    verify(inviteRepo).save(invite);
  }

  private static ProjectInvite invite(String projectId) {
    return ProjectInvite.builder()
        .id(UUID.randomUUID())
        .projectId(projectId)
        .email("invitee@example.com")
        .token("token")
        .invitedByUserId("inviter")
        .status("PENDING")
        .expiresAt(Instant.now().plusSeconds(3600))
        .build();
  }

  private static AppUser actor() {
    return AppUser.builder()
        .id("actor-1")
        .email("actor@example.com")
        .firstName("Actor")
        .status("ACTIVE")
        .build();
  }
}
