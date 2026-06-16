package com.navneet.executespec.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.memory.entity.ProjectChatMessage;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProjectChatControllerProjectMemberTest {

  private final ProjectChatService chatService = mock(ProjectChatService.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final ProjectMemberRepository projectMemberRepo = mock(ProjectMemberRepository.class);
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private final TierConfigRepository tierConfigRepo = mock(TierConfigRepository.class);
  private final ModelResolver modelResolver = mock(ModelResolver.class);
  private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
  private final ProjectChatController controller =
      new ProjectChatController(
          chatService,
          projectRepo,
          projectMemberRepo,
          orgRepo,
          tierConfigRepo,
          modelResolver,
          modelRegistry);

  @Test
  void infoAllowsFreeInvitedMemberWhenHostProjectOrgTierAllowsChat() {
    AppUser invitee = invitedFreeUser();
    Project project = hostEnterpriseProject();
    TierConfig enterprise = new TierConfig();
    enterprise.setTier("ENTERPRISE");
    enterprise.setDisplayName("Enterprise");
    enterprise.setExecutionViewEnabled(true);

    when(projectRepo.findById("project-1")).thenReturn(Optional.of(project));
    when(projectMemberRepo.existsByProjectIdAndUserId("project-1", "invitee")).thenReturn(true);
    when(orgRepo.findById("host-org"))
        .thenReturn(
            Optional.of(Org.builder().id("host-org").name("Host").tier("ENTERPRISE").build()));
    when(tierConfigRepo.findById("ENTERPRISE")).thenReturn(Optional.of(enterprise));
    when(modelResolver.resolve(
            "ProjectChatAgent",
            LlmProvider.ANTHROPIC,
            com.navneet.executespec.router.AgentTier.PLANNING))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.ANTHROPIC)
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude")
                .agentTier(com.navneet.executespec.router.AgentTier.PLANNING)
                .build());
    when(modelRegistry.contextLimitForOrPrefixFallback("claude")).thenReturn(200000);

    var response = controller.info(new AppUserDetails(invitee, null), "project-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("claude", response.getBody().model());
  }

  @Test
  void sendPersistsChatUnderHostProjectOrgForInvitedMember() {
    AppUser invitee = invitedFreeUser();
    Project project = hostEnterpriseProject();
    TierConfig enterprise = new TierConfig();
    enterprise.setTier("ENTERPRISE");
    enterprise.setDisplayName("Enterprise");
    enterprise.setExecutionViewEnabled(true);
    ProjectChatMessage reply =
        ProjectChatMessage.builder()
            .projectId("project-1")
            .orgId("host-org")
            .userId("invitee")
            .role("ASSISTANT")
            .content("ok")
            .build();

    when(projectRepo.findById("project-1")).thenReturn(Optional.of(project));
    when(projectMemberRepo.existsByProjectIdAndUserId("project-1", "invitee")).thenReturn(true);
    when(orgRepo.findById("host-org"))
        .thenReturn(
            Optional.of(Org.builder().id("host-org").name("Host").tier("ENTERPRISE").build()));
    when(tierConfigRepo.findById("ENTERPRISE")).thenReturn(Optional.of(enterprise));
    when(chatService.send(eq("project-1"), eq("host-org"), eq("invitee"), eq("hello"), eq(null)))
        .thenReturn(reply);

    var response =
        controller.send(
            new AppUserDetails(invitee, null),
            "project-1",
            new ProjectChatController.ChatRequest("hello"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("host-org", response.getBody().getOrgId());
    verify(chatService).send("project-1", "host-org", "invitee", "hello", null);
  }

  private static AppUser invitedFreeUser() {
    return AppUser.builder()
        .id("invitee")
        .email("invitee@example.com")
        .firstName("Invitee")
        .globalRole("USER")
        .tier("FREE")
        .status("ACTIVE")
        .orgId("invitee-org")
        .build();
  }

  private static Project hostEnterpriseProject() {
    return Project.builder()
        .id("project-1")
        .orgId("host-org")
        .name("Project")
        .aiProvider(LlmProvider.ANTHROPIC)
        .build();
  }
}
