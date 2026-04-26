package com.navneet.executespec.folder;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ProjectConfigPathResolver} (E1.7) — verifies the
 * 4-way decision tree (DATABASE / FOLDER_FLAT / FOLDER_NAMESPACED /
 * defensive-DATABASE-on-missing-project) end-to-end.
 */
@ExtendWith(MockitoExtension.class)
class ProjectConfigPathResolverTest {

    @Mock ProjectRepository projectRepository;
    @Mock ProjectRepoLinkRepository linkRepo;

    @InjectMocks ProjectConfigPathResolver resolver;

    private String projectId;
    private UUID repoId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID().toString();
        repoId = UUID.randomUUID();
    }

    @Test
    void resolve_databaseStorage_routesAwayFromCustomerRepo() {
        Project p = projectWith("Acme Web", Project.ConfigStorage.DATABASE);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));

        ProjectConfigPathResolver.Resolution res = resolver.resolve(projectId, repoId);

        assertThat(res.strategy()).isEqualTo(ProjectConfigPathResolver.Strategy.DATABASE);
        assertThat(res.basePath()).isNull();
        assertThat(res.projectSlug()).isNull();
        verifyNoInteractions(linkRepo); // no need to consult links when DATABASE
    }

    @Test
    void resolve_singleProjectRepo_returnsFolderFlat() {
        Project p = projectWith("Acme Web", null);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(linkRepo.findByRepoId(repoId)).thenReturn(List.of(linkFor(projectId)));

        ProjectConfigPathResolver.Resolution res = resolver.resolve(projectId, repoId);

        assertThat(res.strategy()).isEqualTo(ProjectConfigPathResolver.Strategy.FOLDER_FLAT);
        assertThat(res.basePath()).isEqualTo(".executespec/config/");
        assertThat(res.projectSlug()).isEqualTo("acme-web");
    }

    @Test
    void resolve_multiProjectRepo_returnsFolderNamespaced() {
        Project p = projectWith("Acme Web", null);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(linkRepo.findByRepoId(repoId)).thenReturn(List.of(
                linkFor(projectId),
                linkFor(UUID.randomUUID().toString())));

        ProjectConfigPathResolver.Resolution res = resolver.resolve(projectId, repoId);

        assertThat(res.strategy()).isEqualTo(ProjectConfigPathResolver.Strategy.FOLDER_NAMESPACED);
        assertThat(res.basePath()).isEqualTo(".executespec/config/acme-web/");
        assertThat(res.projectSlug()).isEqualTo("acme-web");
    }

    @Test
    void resolve_missingProject_failsClosedToDatabaseMode() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        ProjectConfigPathResolver.Resolution res = resolver.resolve(projectId, repoId);

        // Defensive — never accidentally write to a customer repo for a
        // project that has gone missing.
        assertThat(res.strategy()).isEqualTo(ProjectConfigPathResolver.Strategy.DATABASE);
    }

    @Test
    void resolve_explicitFolderConfig_treatedSameAsNullDefault() {
        Project p = projectWith("Acme Web", Project.ConfigStorage.FOLDER);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(linkRepo.findByRepoId(repoId)).thenReturn(List.of(linkFor(projectId)));

        ProjectConfigPathResolver.Resolution res = resolver.resolve(projectId, repoId);

        assertThat(res.strategy()).isEqualTo(ProjectConfigPathResolver.Strategy.FOLDER_FLAT);
    }

    @Test
    void resolveConfigFilePath_buildsExpectedPath_orNullForDatabase() {
        Project p = projectWith("Acme Web", null);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(linkRepo.findByRepoId(repoId)).thenReturn(List.of(linkFor(projectId)));

        String path = resolver.resolveConfigFilePath(projectId, repoId, "standards.yaml");
        assertThat(path).isEqualTo(".executespec/config/standards.yaml");

        Project pConfidential = projectWith("Acme Web", Project.ConfigStorage.DATABASE);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(pConfidential));

        String pathDb = resolver.resolveConfigFilePath(projectId, repoId, "standards.yaml");
        assertThat(pathDb).isNull();
    }

    @Test
    void project_configStorageEnum_normalisesNullAndUnknown() {
        Project p = new Project();
        p.setConfigStorage(null);
        assertThat(p.configStorageEnum()).isEqualTo(Project.ConfigStorage.FOLDER);

        p.setConfigStorage("");
        assertThat(p.configStorageEnum()).isEqualTo(Project.ConfigStorage.FOLDER);

        p.setConfigStorage("FUTURE_VALUE");
        assertThat(p.configStorageEnum()).isEqualTo(Project.ConfigStorage.FOLDER);

        p.setConfigStorage("DATABASE");
        assertThat(p.configStorageEnum()).isEqualTo(Project.ConfigStorage.DATABASE);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Project projectWith(String name, Project.ConfigStorage storage) {
        Project p = new Project();
        p.setId(projectId);
        p.setName(name);
        if (storage != null) p.setConfigStorage(storage.name());
        return p;
    }

    private ProjectRepoLink linkFor(String projId) {
        return ProjectRepoLink.builder()
                .id(UUID.randomUUID())
                .projectId(projId)
                .repoId(repoId)
                .build();
    }
}
