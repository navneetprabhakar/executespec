package com.navneet.executespec.standards;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the V136 standards entities — focused on the
 * forward-compat enum normalisers (NULL/blank/unknown → safe default).
 * Same pattern as ProjectRepo.FolderStatus from E1.1.
 */
class StandardsEntityTest {

    @Test
    void orgConfigRepo_storageMode_normalisesNullAndUnknown_toDatabase() {
        OrgConfigRepo r = new OrgConfigRepo();
        r.setStorageMode(null);
        assertThat(r.storageModeEnum()).isEqualTo(OrgConfigRepo.StorageMode.DATABASE);
        r.setStorageMode("FUTURE_MODE");
        assertThat(r.storageModeEnum()).isEqualTo(OrgConfigRepo.StorageMode.DATABASE);
        r.setStorageMode("REPO");
        assertThat(r.storageModeEnum()).isEqualTo(OrgConfigRepo.StorageMode.REPO);
        r.setStorageMode("DATABASE");
        assertThat(r.storageModeEnum()).isEqualTo(OrgConfigRepo.StorageMode.DATABASE);
    }

    @Test
    void projectStandardOverride_overrideType_normalisesUnknown_toNotApplicable() {
        ProjectStandardOverride o = new ProjectStandardOverride();
        o.setOverrideType(null);
        assertThat(o.overrideTypeEnum()).isEqualTo(ProjectStandardOverride.OverrideType.NOT_APPLICABLE);
        o.setOverrideType("FUTURE_OVERRIDE");
        assertThat(o.overrideTypeEnum()).isEqualTo(ProjectStandardOverride.OverrideType.NOT_APPLICABLE);
        o.setOverrideType("SEVERITY_DOWNGRADE");
        assertThat(o.overrideTypeEnum()).isEqualTo(ProjectStandardOverride.OverrideType.SEVERITY_DOWNGRADE);
    }

    @Test
    void orgStandard_orgLevelLocked_defaultsToFalse() {
        OrgStandard s = new OrgStandard();
        assertThat(s.isOrgLevelLocked()).isFalse();
    }

    @Test
    void standardsViolation_overrideAppliedDefaultsToFalse() {
        StandardsViolation v = new StandardsViolation();
        assertThat(v.isOverrideApplied()).isFalse();
        assertThat(v.getOverrideAt()).isNull();
    }
}
