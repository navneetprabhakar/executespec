package com.navneet.executespec.contracts.injection;

import com.navneet.executespec.contracts.ContractConsumer;
import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.ContractVersion;
import com.navneet.executespec.contracts.ContractVersionRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractInjectionServiceTest {

    @Mock ContractRegistryRepository registryRepo;
    @Mock ContractVersionRepository versionRepo;
    @Mock ContractConsumerRepository consumerRepo;
    @Mock ProjectRepoLinkRepository linkRepo;
    @Mock ProjectRepoRepository projectRepoRepo;

    @InjectMocks ContractInjectionService service;

    String projectId;
    UUID repoId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID().toString();
        repoId = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "fullBodyThreshold", 15);
        ProjectRepo repo = new ProjectRepo();
        repo.setId(repoId);
        repo.setGithubRepoFullName("acme/widget");
        lenient().when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    }

    @Test
    void emptyMode_whenNoContractsForProject() {
        when(linkRepo.findByProjectId(projectId)).thenReturn(List.of());
        when(consumerRepo.findByDeclaredInProjectId(projectId)).thenReturn(List.of());

        ContractInjectionService.InjectionResult res = service.compose(projectId);

        assertThat(res.mode()).isEqualTo(ContractInjectionService.Mode.EMPTY);
        assertThat(res.block()).isEmpty();
        assertThat(res.totalContracts()).isZero();
    }

    @Test
    void fullBodyMode_whenAtOrUnderThreshold() {
        ContractRegistry c = contract("widget-api", 3);
        when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(link(repoId)));
        when(registryRepo.findByRepoId(repoId)).thenReturn(List.of(c));
        when(consumerRepo.findByDeclaredInProjectId(projectId)).thenReturn(List.of());
        when(versionRepo.findFirstByContractIdOrderByRegistryVersionDesc(c.getId()))
                .thenReturn(Optional.of(ContractVersion.builder()
                        .contractId(c.getId()).registryVersion(3)
                        .contentHash("h").inlineContent("openapi: 3.0\n")
                        .build()));

        ContractInjectionService.InjectionResult res = service.compose(projectId);

        assertThat(res.mode()).isEqualTo(ContractInjectionService.Mode.FULL_BODY);
        assertThat(res.block()).contains("=== CONTRACTS ===");
        assertThat(res.block()).contains("widget-api");
        assertThat(res.block()).contains("openapi: 3.0");
        assertThat(res.totalContracts()).isEqualTo(1);
    }

    @Test
    void indexOnlyMode_whenAboveThreshold() {
        // 20 contracts > threshold 15 → INDEX_ONLY mode
        List<ContractRegistry> many = IntStream.range(0, 20)
                .mapToObj(i -> contract("contract-" + i, 1)).toList();
        when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(link(repoId)));
        when(registryRepo.findByRepoId(repoId)).thenReturn(many);
        when(consumerRepo.findByDeclaredInProjectId(projectId)).thenReturn(List.of());

        ContractInjectionService.InjectionResult res = service.compose(projectId);

        assertThat(res.mode()).isEqualTo(ContractInjectionService.Mode.INDEX_ONLY);
        assertThat(res.block()).contains("=== CONTRACTS_INDEX ===");
        assertThat(res.block()).contains("Use the getContract(id, version) tool");
        assertThat(res.totalContracts()).isEqualTo(20);
        assertThat(res.contractIds()).hasSize(20);
        // Verify versionRepo never invoked (no body fetch in INDEX_ONLY mode)
        verifyNoInteractions(versionRepo);
    }

    @Test
    void contractsFromConsumerDeclaration_alsoIncluded() {
        ContractRegistry c = contract("payments-api", 1);
        when(linkRepo.findByProjectId(projectId)).thenReturn(List.of()); // no producing repos
        when(consumerRepo.findByDeclaredInProjectId(projectId))
                .thenReturn(List.of(ContractConsumer.builder()
                        .id(UUID.randomUUID())
                        .contractId(c.getId())
                        .consumerRepoId(UUID.randomUUID())
                        .declaredInProjectId(projectId).build()));
        when(registryRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(versionRepo.findFirstByContractIdOrderByRegistryVersionDesc(c.getId()))
                .thenReturn(Optional.empty());

        ContractInjectionService.InjectionResult res = service.compose(projectId);

        assertThat(res.totalContracts()).isEqualTo(1);
        assertThat(res.block()).contains("payments-api");
        assertThat(res.block()).contains("(content not inlined");
    }

    @Test
    void deduplicates_whenContractAppearsViaBothProducerAndConsumerPaths() {
        ContractRegistry c = contract("shared-api", 1);
        when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(link(repoId)));
        when(registryRepo.findByRepoId(repoId)).thenReturn(List.of(c));
        when(consumerRepo.findByDeclaredInProjectId(projectId))
                .thenReturn(List.of(ContractConsumer.builder()
                        .contractId(c.getId())
                        .consumerRepoId(UUID.randomUUID())
                        .declaredInProjectId(projectId).build()));
        when(versionRepo.findFirstByContractIdOrderByRegistryVersionDesc(any()))
                .thenReturn(Optional.empty());

        ContractInjectionService.InjectionResult res = service.compose(projectId);

        assertThat(res.totalContracts()).isEqualTo(1);
        assertThat(res.contractIds()).containsExactly(c.getId());
    }

    private ContractRegistry contract(String name, int version) {
        return ContractRegistry.builder()
                .id(UUID.randomUUID())
                .repoId(repoId)
                .name(name)
                .type(ContractRegistry.ContractType.OPENAPI.name())
                .currentRegistryVersion(version)
                .currentContentHash("h" + version)
                .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
                .build();
    }

    private ProjectRepoLink link(UUID rId) {
        return ProjectRepoLink.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .repoId(rId).build();
    }
}
