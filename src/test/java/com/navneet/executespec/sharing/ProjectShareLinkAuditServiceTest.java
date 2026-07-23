package com.navneet.executespec.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.entity.ProjectShareLinkAudit;
import com.navneet.executespec.sharing.repository.ProjectShareLinkAuditRepository;
import com.navneet.executespec.sharing.service.ProjectShareLinkAuditService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectShareLinkAuditServiceTest {

  @Test
  void persistsOnlyKeyedRequestFingerprints() {
    ProjectShareLinkAuditRepository repository = mock(ProjectShareLinkAuditRepository.class);
    ProjectShareLinkAuditService service =
        new ProjectShareLinkAuditService(repository, "audit-test-secret");
    ProjectShareLink link =
        ProjectShareLink.builder()
            .id(UUID.randomUUID())
            .projectId("project-1")
            .surface(ProjectShareLink.Surface.PROJECT_OVERVIEW)
            .build();

    service.record(
        link,
        "ACCESS",
        "ALLOWED",
        null,
        new ProjectShareLinkAuditService.AccessContext("203.0.113.8", "sensitive-agent"));

    ArgumentCaptor<ProjectShareLinkAudit> captor =
        ArgumentCaptor.forClass(ProjectShareLinkAudit.class);
    verify(repository).save(captor.capture());
    ProjectShareLinkAudit event = captor.getValue();
    assertThat(event.getClientIpHash()).hasSize(64).doesNotContain("203.0.113.8");
    assertThat(event.getUserAgentHash()).hasSize(64).doesNotContain("sensitive-agent");
    assertThat(event.getEventType()).isEqualTo("ACCESS");
    assertThat(event.getOccurredAt()).isBeforeOrEqualTo(Instant.now());
  }
}
