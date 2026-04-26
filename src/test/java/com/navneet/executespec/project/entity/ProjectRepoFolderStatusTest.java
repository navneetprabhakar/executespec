package com.navneet.executespec.project.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ProjectRepo#folderStatusEnum()} normaliser added in
 * E1.1 (V131 migration). Existing project_repo rows that pre-date V131 have
 * NULL for the new {@code executespec_folder_status} column; the entity must
 * report them as {@link ProjectRepo.FolderStatus#NOT_BOOTSTRAPPED} so the
 * dashboard can render a consistent "needs bootstrap" CTA without a backfill.
 */
class ProjectRepoFolderStatusTest {

    @Test
    void nullStatus_normalisesTo_NOT_BOOTSTRAPPED() {
        ProjectRepo repo = new ProjectRepo();
        repo.setExecutespecFolderStatus(null);
        assertThat(repo.folderStatusEnum())
                .isEqualTo(ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED);
    }

    @Test
    void blankStatus_normalisesTo_NOT_BOOTSTRAPPED() {
        ProjectRepo repo = new ProjectRepo();
        repo.setExecutespecFolderStatus("   ");
        assertThat(repo.folderStatusEnum())
                .isEqualTo(ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED);
    }

    @Test
    void allKnownStatusValues_roundTrip() {
        for (ProjectRepo.FolderStatus status : ProjectRepo.FolderStatus.values()) {
            ProjectRepo repo = new ProjectRepo();
            repo.setExecutespecFolderStatus(status.name());
            assertThat(repo.folderStatusEnum())
                    .as("round-trip for %s", status)
                    .isEqualTo(status);
        }
    }

    @Test
    void unknownStatus_fromForwardCompatNode_normalisesTo_NOT_BOOTSTRAPPED() {
        // A future node could write a value an older node doesn't recognise.
        // Older nodes must stay readable and treat it as needing re-evaluation
        // rather than crashing the API.
        ProjectRepo repo = new ProjectRepo();
        repo.setExecutespecFolderStatus("FUTURE_VALUE_NOT_YET_DEFINED");
        assertThat(repo.folderStatusEnum())
                .isEqualTo(ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED);
    }

    @Test
    void allOtherFieldsDefaultSafely_whenJustInstantiated() {
        ProjectRepo repo = new ProjectRepo();
        assertThat(repo.getExecutespecFolderStatus()).isNull();
        assertThat(repo.getExecutespecFolderSha()).isNull();
        assertThat(repo.getExecutespecBootstrapPrUrl()).isNull();
        assertThat(repo.getExecutespecLastSyncedAt()).isNull();
    }
}
