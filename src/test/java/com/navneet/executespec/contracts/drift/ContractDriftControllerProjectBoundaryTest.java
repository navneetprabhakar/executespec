package com.navneet.executespec.contracts.drift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.contracts.ContractDriftEventRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.folder.ExecutespecFolderWriter;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class ContractDriftControllerProjectBoundaryTest {

  @Test
  void rejectsContractThatIsNotLinkedToGuardedProjectBeforeCheckingDrift() {
    DriftDetectionService driftService = mock(DriftDetectionService.class);
    ContractRegistryRepository registryRepo = mock(ContractRegistryRepository.class);
    ProjectRepoLinkRepository linkRepo = mock(ProjectRepoLinkRepository.class);
    ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
    AppUserRepository userRepo = mock(AppUserRepository.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ActivityEventEmitter> emitterProvider = mock(ObjectProvider.class);
    ContractDriftController controller =
        new ContractDriftController(
            driftService,
            registryRepo,
            mock(ContractDriftEventRepository.class),
            mock(ProjectRepoRepository.class),
            linkRepo,
            mock(ExecutespecFolderWriter.class),
            mock(GitHubRepoService.class),
            userRepo,
            mock(PullRequestRecordService.class),
            accessGuard,
            emitterProvider);

    String projectId = UUID.randomUUID().toString();
    UUID contractId = UUID.randomUUID();
    UUID foreignRepoId = UUID.randomUUID();
    ContractRegistry contract =
        ContractRegistry.builder().id(contractId).repoId(foreignRepoId).build();
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    when(linkRepo.findByProjectIdAndRepoId(projectId, foreignRepoId)).thenReturn(Optional.empty());
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(mock(AppUserDetails.class));

    ResponseStatusException failure =
        assertThrows(
            ResponseStatusException.class,
            () -> controller.checkContract(projectId, contractId, auth));

    assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
    verify(accessGuard).assertCanWrite(projectId, (AppUserDetails) auth.getPrincipal());
    verify(accessGuard, never())
        .assertCanRead(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(AppUserDetails.class));
    verify(driftService, never()).checkContract(contractId, UUID.fromString(projectId), null);
  }
}
