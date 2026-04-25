package com.navneet.executespec.repo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonorepoDetectorTest {

    private final MonorepoDetector detector = new MonorepoDetector();

    @Test
    void emptyFileList_isNotMonorepo() {
        MonorepoDetector.Verdict v = detector.detect(List.of());
        assertThat(v.isMonorepo()).isFalse();
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.NONE);
    }

    @Test
    void nx_detectedFromNxJsonAtRoot() {
        MonorepoDetector.Verdict v = detector.detect(List.of(
                "nx.json", "package.json", "apps/web/index.tsx", "apps/api/server.ts",
                "libs/util/index.ts"));
        assertThat(v.isMonorepo()).isTrue();
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.NX);
        assertThat(v.packageCount()).isEqualTo(3); // apps/web, apps/api, libs/util
        assertThat(v.uiHint()).contains("Nx monorepo detected (3 packages)");
    }

    @Test
    void pnpmWorkspaces_detected() {
        MonorepoDetector.Verdict v = detector.detect(List.of(
                "pnpm-workspace.yaml", "package.json", "packages/a/index.ts",
                "packages/b/index.ts"));
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.PNPM_WORKSPACES);
        assertThat(v.packageCount()).isEqualTo(2);
    }

    @Test
    void turbo_detectedAndLabelled() {
        MonorepoDetector.Verdict v = detector.detect(List.of("turbo.json"));
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.TURBO);
        assertThat(v.uiHint()).startsWith("Turbo monorepo detected");
    }

    @Test
    void lerna_detected() {
        MonorepoDetector.Verdict v = detector.detect(List.of("lerna.json", "packages/a/index.ts"));
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.LERNA);
    }

    @Test
    void bazel_detected() {
        MonorepoDetector.Verdict v = detector.detect(List.of(
                "WORKSPACE", "services/foo/BUILD.bazel"));
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.BAZEL);
        assertThat(v.packageCount()).isEqualTo(1);
    }

    @Test
    void plainSinglePackageRepo_isNotMonorepo() {
        MonorepoDetector.Verdict v = detector.detect(List.of(
                "package.json", "src/index.ts", "README.md"));
        assertThat(v.isMonorepo()).isFalse();
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.NONE);
    }

    @Test
    void packageCount_dedupesEntriesWithinSamePackageDir() {
        // packages/a has multiple files but counts as ONE package
        MonorepoDetector.Verdict v = detector.detect(List.of(
                "pnpm-workspace.yaml",
                "packages/a/index.ts",
                "packages/a/util.ts",
                "packages/a/test/x.ts",
                "packages/b/index.ts"));
        assertThat(v.packageCount()).isEqualTo(2);
    }

    @Test
    void uiHint_excludesPackageCount_whenZero() {
        // Detected flavor but no workspace dirs populated yet
        MonorepoDetector.Verdict v = detector.detect(List.of("nx.json", "README.md"));
        assertThat(v.isMonorepo()).isTrue();
        assertThat(v.uiHint()).doesNotContain("packages)");
        assertThat(v.uiHint()).contains("Nx monorepo detected — scope-aware planning");
    }

    @Test
    void firstFlavorWins_whenMultipleSignaturesPresent() {
        // Order of files in the list determines first-match.
        MonorepoDetector.Verdict v = detector.detect(List.of(
                "nx.json", "turbo.json", "lerna.json"));
        assertThat(v.flavor()).isEqualTo(MonorepoDetector.Flavor.NX);
    }
}
